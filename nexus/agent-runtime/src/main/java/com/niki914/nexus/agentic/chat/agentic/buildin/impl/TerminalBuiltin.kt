package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.RawOutcomeProducing
import com.niki914.nexus.agentic.chat.agentic.buildin.RawToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.UNKNOWN_OPERATION
import com.niki914.nexus.agentic.chat.agentic.buildin.rawOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalAsyncReadOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalAsyncStartOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class TerminalBuiltin(
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
) : BuiltinTool(), RawJsonBuiltinTool, RawOutcomeProducing {
    override val name: String = "terminal"

    override val description: String =
        "Manage Android terminal sessions. For one-shot commands, prefer open_and_exec, e.g. " +
                """{"action":"open_and_exec","identity":"user","command":"pwd"}. """ +
                "identity can be user, root, or shizuku. " +
                "Shizuku depends on device support, a running service, and granted authorization. " +
                "Use exec only with the opaque session handle returned by open or open_and_exec. " +
                "Use is_async=true only with exec for long-running commands, then poll read_async_result by async_id. " +
                "If SESSION_NOT_FOUND, call open first or use the returned handle instead of identity names. " +
                "If SESSION_BUSY, wait or poll read_async_result when async_id is present."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.rawJsonSchema(TERMINAL_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "terminal accepts raw JSON requests only.",
            hint = """Example: {"action":"open_and_exec","identity":"user","command":"pwd"}""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        return invokeRawJsonWithEvidence(request).resultJson
    }

    /**
     * The real exit path: each branch reports what it can prove about the stage it reached.
     *
     * The split that carries the safety is between *validation* and *execution*. Parsing the request,
     * `requireX` and the policy check all sit before the handler's first pool call, so a rejection
     * there is the only thing that may claim nothing was dispatched. Everything from the first pool
     * call onwards reports the pool's own verdict, and anything thrown there is unconfirmed — never
     * downgraded to a retryable failure because of its class name.
     */
    override suspend fun invokeRawJsonWithEvidence(
        request: BuiltinToolRequest,
    ): RawToolOutcome {
        val args = try {
            parseArguments(request.argumentsJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return refusal(
                operation = UNKNOWN_OPERATION,
                json = TerminalToolResponse.invalidRequest(
                    (error as? IllegalArgumentException)?.message ?: "Invalid terminal request."
                ),
            )
        }

        return try {
            when (args.action) {
                TerminalAction.OPEN -> handleOpen(args)
                TerminalAction.OPEN_AND_EXEC -> handleOpenAndExec(args)
                TerminalAction.EXEC -> handleExec(args)
                TerminalAction.READ_ASYNC_RESULT -> handleReadAsyncResult(args)
                TerminalAction.CLOSE -> handleClose(args)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Reached without the handler having claimed a stage, so the operation may well have been
            // handed to the pool: unconfirmed, not a retryable failure.
            unconfirmed(
                operation = args.action.operation,
                json = TerminalToolResponse.internalError(error),
            )
        }
    }

    private suspend fun handleOpen(args: TerminalToolArgs): RawToolOutcome {
        val identity: String
        try {
            identity = args.requireIdentity()
        } catch (error: IllegalArgumentException) {
            return refusal(OPEN, invalidRequestJson(error))
        }

        return dispatch(OPEN) {
            when (val outcome = TerminalSessionPool.open(identity = identity, cwd = args.cwd)) {
                is TerminalOpenOutcome.Success -> decided(
                    operation = OPEN,
                    json = TerminalToolResponse.openSuccess(
                        session = outcome.session,
                        identity = outcome.identity,
                    ),
                )

                is TerminalOpenOutcome.Failure -> unconfirmed(
                    operation = OPEN,
                    json = TerminalToolResponse.failure(
                        failure = outcome.failure,
                        elapsedSeconds = outcome.elapsedSeconds,
                        identity = identity,
                    ),
                )

                // Pool-sourced: the same exit also covers a handle collision that already opened a
                // session and compensating-closed it, so it cannot claim nothing was dispatched.
                is TerminalOpenOutcome.InvalidRequest -> unconfirmed(
                    operation = OPEN,
                    json = TerminalToolResponse.invalidRequest(outcome.message),
                )
            }
        }
    }

    private suspend fun handleOpenAndExec(args: TerminalToolArgs): RawToolOutcome {
        val identity: String
        val command: String
        val timeoutMs: Long
        try {
            identity = args.requireIdentity()
            command = args.requireCommand()
            timeoutMs = args.timeoutMs ?: DEFAULT_TIMEOUT_MS
        } catch (error: IllegalArgumentException) {
            return refusal(OPEN_AND_EXEC, invalidRequestJson(error))
        }
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return refusal(OPEN_AND_EXEC, TerminalToolResponse.policyBlocked(decision))
        }

        return dispatch(OPEN_AND_EXEC) {
            when (val outcome = TerminalSessionPool.openAndExecute(
                identity = identity,
                cwd = args.cwd,
                command = command,
                timeoutMs = timeoutMs,
            )) {
                is TerminalCommandOutcome.Success -> decided(
                    operation = OPEN_AND_EXEC,
                    json = TerminalToolResponse.commandSuccess(
                        result = outcome.result,
                        elapsedSeconds = outcome.elapsedSeconds,
                        session = outcome.session,
                        identity = outcome.identity,
                        mergeStderr = args.mergeStderr,
                    ),
                )

                is TerminalCommandOutcome.Timeout -> unconfirmed(
                    operation = OPEN_AND_EXEC,
                    json = TerminalToolResponse.commandTimeout(
                        result = outcome.result,
                        elapsedSeconds = outcome.elapsedSeconds,
                        timeoutMs = timeoutMs,
                        session = outcome.session,
                        identity = outcome.identity,
                        mergeStderr = args.mergeStderr,
                    ),
                )

                is TerminalCommandOutcome.Failure -> unconfirmed(
                    operation = OPEN_AND_EXEC,
                    json = TerminalToolResponse.failure(
                        failure = outcome.failure,
                        elapsedSeconds = outcome.elapsedSeconds,
                        session = outcome.session,
                        identity = outcome.identity,
                    ),
                )

                // The open had already happened by the time this came back, so a lookup or lock that
                // fails on the second step of a composite call does not prove the call never ran.
                is TerminalCommandOutcome.SessionNotFound -> unconfirmed(
                    operation = OPEN_AND_EXEC,
                    json = TerminalToolResponse.sessionNotFound(outcome.session),
                )

                is TerminalCommandOutcome.Busy -> unconfirmed(
                    operation = OPEN_AND_EXEC,
                    json = TerminalToolResponse.sessionBusy(outcome.session, outcome.asyncId),
                )

                is TerminalCommandOutcome.UnexpectedError -> unconfirmed(
                    operation = OPEN_AND_EXEC,
                    json = TerminalToolResponse.internalError(
                        throwable = outcome.throwable,
                        elapsedSeconds = outcome.elapsedSeconds,
                    ),
                )
            }
        }
    }

    private suspend fun handleExec(args: TerminalToolArgs): RawToolOutcome {
        val session: String
        val command: String
        val timeoutMs: Long
        try {
            session = args.requireSession()
            command = args.requireCommand()
            timeoutMs = args.timeoutMs ?: DEFAULT_TIMEOUT_MS
        } catch (error: IllegalArgumentException) {
            return refusal(EXEC, invalidRequestJson(error))
        }
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return refusal(EXEC, TerminalToolResponse.policyBlocked(decision))
        }

        return dispatch(EXEC) {
            if (args.isAsync) {
                when (val outcome = TerminalSessionPool.startAsync(
                    session = session,
                    command = command,
                    timeoutMs = timeoutMs,
                )) {
                    // Accepted means the command was handed over, not that it finished.
                    is TerminalAsyncStartOutcome.Accepted -> unconfirmed(
                        operation = EXEC,
                        json = TerminalToolResponse.asyncAccepted(
                            asyncId = outcome.asyncId,
                            elapsedSeconds = outcome.elapsedSeconds,
                        ),
                    )

                    is TerminalAsyncStartOutcome.SessionNotFound -> refusal(
                        operation = EXEC,
                        json = TerminalToolResponse.sessionNotFound(outcome.session),
                    )

                    is TerminalAsyncStartOutcome.Busy -> refusal(
                        operation = EXEC,
                        json = TerminalToolResponse.sessionBusy(outcome.session, outcome.asyncId),
                    )

                    is TerminalAsyncStartOutcome.InvalidRequest -> unconfirmed(
                        operation = EXEC,
                        json = TerminalToolResponse.invalidRequest(outcome.message),
                    )
                }
            } else {
                when (val outcome = TerminalSessionPool.executeBlocking(
                    session = session,
                    command = command,
                    timeoutMs = timeoutMs,
                )) {
                    is TerminalCommandOutcome.Success -> decided(
                        operation = EXEC,
                        json = TerminalToolResponse.commandSuccess(
                            result = outcome.result,
                            elapsedSeconds = outcome.elapsedSeconds,
                            session = outcome.session,
                            identity = outcome.identity,
                            mergeStderr = args.mergeStderr,
                        ),
                    )

                    is TerminalCommandOutcome.Timeout -> unconfirmed(
                        operation = EXEC,
                        json = TerminalToolResponse.commandTimeout(
                            result = outcome.result,
                            elapsedSeconds = outcome.elapsedSeconds,
                            timeoutMs = timeoutMs,
                            session = outcome.session,
                            identity = outcome.identity,
                            mergeStderr = args.mergeStderr,
                        ),
                    )

                    is TerminalCommandOutcome.Failure -> unconfirmed(
                        operation = EXEC,
                        json = TerminalToolResponse.failure(
                            failure = outcome.failure,
                            elapsedSeconds = outcome.elapsedSeconds,
                            session = outcome.session,
                            identity = outcome.identity,
                        ),
                    )

                    // A standalone exec fails its own session lookup or lock before any command is
                    // handed over, so nothing was dispatched.
                    is TerminalCommandOutcome.SessionNotFound -> refusal(
                        operation = EXEC,
                        json = TerminalToolResponse.sessionNotFound(outcome.session),
                    )

                    is TerminalCommandOutcome.Busy -> refusal(
                        operation = EXEC,
                        json = TerminalToolResponse.sessionBusy(outcome.session, outcome.asyncId),
                    )

                    is TerminalCommandOutcome.UnexpectedError -> unconfirmed(
                        operation = EXEC,
                        json = TerminalToolResponse.internalError(
                            throwable = outcome.throwable,
                            elapsedSeconds = outcome.elapsedSeconds,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun handleReadAsyncResult(args: TerminalToolArgs): RawToolOutcome {
        val session: String
        val asyncId: String
        try {
            session = args.requireSession()
            asyncId = args.requireAsyncId()
        } catch (error: IllegalArgumentException) {
            return refusal(READ_ASYNC_RESULT, invalidRequestJson(error))
        }

        return dispatch(READ_ASYNC_RESULT) {
            when (val outcome =
                TerminalSessionPool.readAsyncResult(session = session, asyncId = asyncId)) {
                is TerminalAsyncReadOutcome.Running -> unconfirmed(
                    operation = READ_ASYNC_RESULT,
                    json = TerminalToolResponse.asyncRunning(
                        stdoutPartial = outcome.stdoutPartial,
                        stderrPartial = outcome.stderrPartial,
                        elapsedSeconds = outcome.elapsedSeconds,
                    ),
                )

                is TerminalAsyncReadOutcome.Completed -> decided(
                    operation = READ_ASYNC_RESULT,
                    json = TerminalToolResponse.commandSuccess(
                        result = outcome.result,
                        elapsedSeconds = outcome.elapsedSeconds,
                        mergeStderr = args.mergeStderr,
                    ),
                )

                is TerminalAsyncReadOutcome.TimedOut -> unconfirmed(
                    operation = READ_ASYNC_RESULT,
                    json = TerminalToolResponse.commandTimeout(
                        result = outcome.result,
                        elapsedSeconds = outcome.elapsedSeconds,
                        timeoutMs = args.timeoutMs ?: DEFAULT_TIMEOUT_MS,
                        mergeStderr = args.mergeStderr,
                    ),
                )

                is TerminalAsyncReadOutcome.Failure -> unconfirmed(
                    operation = READ_ASYNC_RESULT,
                    json = TerminalToolResponse.failure(
                        failure = outcome.failure,
                        elapsedSeconds = outcome.elapsedSeconds,
                        session = session,
                    ),
                )

                is TerminalAsyncReadOutcome.AsyncNotFound -> refusal(
                    operation = READ_ASYNC_RESULT,
                    json = TerminalToolResponse.asyncNotFound(
                        session = outcome.session,
                        asyncId = outcome.asyncId,
                    ),
                )

                is TerminalAsyncReadOutcome.SessionNotFound -> refusal(
                    operation = READ_ASYNC_RESULT,
                    json = TerminalToolResponse.sessionNotFound(outcome.session),
                )

                is TerminalAsyncReadOutcome.UnexpectedError -> unconfirmed(
                    operation = READ_ASYNC_RESULT,
                    json = TerminalToolResponse.internalError(
                        throwable = outcome.throwable,
                        elapsedSeconds = outcome.elapsedSeconds,
                    ),
                )
            }
        }
    }

    private suspend fun handleClose(args: TerminalToolArgs): RawToolOutcome {
        val session: String
        try {
            session = args.requireSession()
        } catch (error: IllegalArgumentException) {
            return refusal(CLOSE, invalidRequestJson(error))
        }

        return dispatch(CLOSE) {
            when (val outcome = TerminalSessionPool.close(session = session)) {
                // Including the no-op close of an already-closed session: a session that is not there
                // cannot have been changed by this call, so it is a confirmed success.
                TerminalCloseOutcome.Closed -> decided(CLOSE, TerminalToolResponse.closeSuccess())
                is TerminalCloseOutcome.UnexpectedError -> unconfirmed(
                    operation = CLOSE,
                    json = TerminalToolResponse.internalError(outcome.throwable),
                )
            }
        }
    }

    /** A rejection proven to have happened before the first pool call. */
    private fun refusal(operation: String, json: String) =
        rawOutcome(operation = operation, stage = DispatchStage.NOT_DISPATCHED, json = json)

    /** A pool verdict about the operation: a real success or a real failure. */
    private fun decided(operation: String, json: String) =
        rawOutcome(operation = operation, stage = DispatchStage.DISPATCHED_CONFIRMED, json = json)

    /** The operation reached the pool and produced no verdict this exit can rely on. */
    private fun unconfirmed(operation: String, json: String) =
        rawOutcome(operation = operation, stage = DispatchStage.DISPATCHED_UNCONFIRMED, json = json)

    /**
     * Runs the pool-call region. A throw here is a *post-dispatch* failure: the handler had already
     * passed its validation, so it is reported unconfirmed rather than as a retryable failure, no
     * matter which exception class came out.
     */
    private suspend fun dispatch(
        operation: String,
        block: suspend () -> RawToolOutcome,
    ): RawToolOutcome = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        unconfirmed(operation, TerminalToolResponse.internalError(error))
    }

    private fun invalidRequestJson(error: IllegalArgumentException): String =
        TerminalToolResponse.invalidRequest(error.message ?: "Invalid terminal request.")

    private fun parseArguments(argumentsJson: String): TerminalToolArgs {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", error)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        obj.requireKnownKeys()
        return TerminalToolArgs(
            action = TerminalAction.from(obj.optionalString("action")),
            identity = obj.optionalString("identity")?.trim(),
            session = obj.optionalString("session")?.trim(),
            command = obj.optionalString("command"),
            cwd = obj.optionalString("cwd"),
            timeoutMs = obj.optionalLong("timeout_ms")?.also { timeoutMs ->
                if (timeoutMs < 1L) {
                    throw IllegalArgumentException("Field 'timeout_ms' must be greater than 0.")
                }
            },
            mergeStderr = obj.optionalBoolean("merge_stderr") ?: false,
            isAsync = obj.optionalBoolean("is_async") ?: false,
            asyncId = obj.optionalString("async_id")?.trim(),
        )
    }

    private fun TerminalToolArgs.requireIdentity(): String {
        val value = identity?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'identity' must be one of user, root, shizuku.")
        if (value !in PUBLIC_IDENTITIES) {
            throw IllegalArgumentException("Field 'identity' must be one of user, root, shizuku.")
        }
        return value
    }

    private fun TerminalToolArgs.requireSession(): String {
        val value = session?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'session' is required for action '${action.wireName()}'.")
        return value
    }

    private fun TerminalToolArgs.requireCommand(): String {
        return command?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'command' must not be blank.")
    }

    private fun TerminalToolArgs.requireAsyncId(): String {
        return asyncId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'async_id' is required for action 'read_async_result'.")
    }

    private fun JsonObject.requireKnownKeys() {
        val unknownKeys = keys - REQUEST_KEYS
        if (unknownKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown terminal request field(s): ${
                    unknownKeys.sorted().joinToString()
                }."
            )
        }
    }

    private fun JsonObject.optionalString(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        val primitive = element.asPrimitive(key)
        return try {
            Json.decodeFromJsonElement<String>(primitive)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Field '$key' must be a string.")
        }
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        return element.asPrimitive(key).booleanOrNull
            ?: throw IllegalArgumentException("Field '$key' must be a boolean.")
    }

    private fun JsonObject.optionalLong(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        return element.asPrimitive(key).longOrNull
            ?: throw IllegalArgumentException("Field '$key' must be an integer.")
    }

    private fun JsonElement.asPrimitive(key: String): JsonPrimitive {
        return runCatching { jsonPrimitive }.getOrElse {
            throw IllegalArgumentException("Field '$key' must be a primitive value.")
        }
    }

    private fun TerminalAction.wireName(): String {
        return when (this) {
            TerminalAction.OPEN -> OPEN
            TerminalAction.OPEN_AND_EXEC -> OPEN_AND_EXEC
            TerminalAction.EXEC -> EXEC
            TerminalAction.READ_ASYNC_RESULT -> READ_ASYNC_RESULT
            TerminalAction.CLOSE -> CLOSE
        }
    }

    /** The operation name this action binds its evidence to. */
    private val TerminalAction.operation: String get() = wireName()

    private data class TerminalToolArgs(
        val action: TerminalAction,
        val identity: String?,
        val session: String?,
        val command: String?,
        val cwd: String?,
        val timeoutMs: Long?,
        val mergeStderr: Boolean,
        val isAsync: Boolean,
        val asyncId: String?,
    )

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        // The operation names carried as dispatch evidence. They are the same wire names the request
        // uses, so the evidence and the parsed request can never describe different operations.
        private const val OPEN = "open"
        private const val OPEN_AND_EXEC = "open_and_exec"
        private const val EXEC = "exec"
        private const val READ_ASYNC_RESULT = "read_async_result"
        private const val CLOSE = "close"
        private val PUBLIC_IDENTITIES = setOf("user", "root", "shizuku")
        private val REQUEST_KEYS = setOf(
            "action",
            "identity",
            "session",
            "command",
            "cwd",
            "timeout_ms",
            "merge_stderr",
            "is_async",
            "async_id",
        )
        private const val TERMINAL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["open", "open_and_exec", "exec", "read_async_result", "close"],
                  "description": "Terminal action to perform. open and open_and_exec create a new session and return an opaque handle. For one-shot commands, prefer open_and_exec with identity and command. Use exec only with the returned session handle."
                },
                "identity": {
                  "type": "string",
                  "enum": ["user", "root", "shizuku"],
                  "description": "Identity used only by open or open_and_exec to choose the user, root, or shizuku terminal. Shizuku requires device support, a running service, and granted authorization."
                },
                "session": {
                  "type": "string",
                  "description": "Opaque session handle returned by open or open_and_exec. Do not pass identity names such as user or root."
                },
                "command": {
                  "type": "string",
                  "description": "Android shell command. Required for open_and_exec and exec."
                },
                "cwd": {
                  "type": "string",
                  "description": "Optional working directory used only when opening a new session with open or open_and_exec."
                },
                "timeout_ms": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Command timeout in milliseconds. Defaults to 30000. If a command times out, increase timeout_ms, narrow the command, or use exec with is_async=true."
                },
                "merge_stderr": {
                  "type": "boolean",
                  "description": "Whether stderr should be appended to stdout in command responses."
                },
                "is_async": {
                  "type": "boolean",
                  "description": "Only supported by exec. When true, start the command in background and poll read_async_result with the returned async_id."
                },
                "async_id": {
                  "type": "string",
                  "description": "Required by read_async_result. Use the async_id returned by exec when is_async=true."
                }
              },
              "required": ["action"]
            }
        """
    }
}
