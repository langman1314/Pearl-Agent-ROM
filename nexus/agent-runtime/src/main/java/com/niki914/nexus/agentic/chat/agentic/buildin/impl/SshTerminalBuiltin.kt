package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.RawOutcomeProducing
import com.niki914.nexus.agentic.chat.agentic.buildin.RawToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.UNKNOWN_OPERATION
import com.niki914.nexus.agentic.chat.agentic.buildin.rawOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveReadMode
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveReadOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveWriteOutcome
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

class SshTerminalBuiltin : BuiltinTool(), RawJsonBuiltinTool, RawOutcomeProducing {
    override val name: String = "ssh_terminal"

    override val description: String =
        "Manage interactive SSH terminal sessions. Open a terminal, send input with send_line, write, or interrupt, " +
                "then read terminal output with read. password credentials are passed for this call. " +
                "SSH command completion is unknowable, so exec/open_and_exec are intentionally unsupported. " +
                "Credentials should not be echoed, " +
                "and should not be stored by this tool. Private key authentication is not supported yet."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.rawJsonSchema(SSH_TERMINAL_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "ssh_terminal accepts raw JSON requests only.",
            hint = """Use ssh_terminal with raw JSON, e.g. {"action":"open","host":"192.168.1.10","username":"root","password":"***"} then {"action":"send_line","session":"a3f9","text":"pwd"}.""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        return invokeRawJsonWithEvidence(request).resultJson
    }

    /**
     * The real exit path, with the stage each branch can actually prove.
     *
     * SSH cannot report command completion at all, so every write-shaped exit stays unconfirmed: a
     * receipt saying the bytes reached the terminal's input is not a command result. Only the
     * validation region before the first pool call may claim nothing was dispatched.
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
                    (error as? IllegalArgumentException)?.message ?: "Invalid ssh_terminal request."
                ),
            )
        }

        return try {
            when (args.action) {
                SshTerminalAction.OPEN -> handleOpen(args)
                SshTerminalAction.SEND_LINE -> handleWrite(args, appendNewline = true)
                SshTerminalAction.WRITE -> handleWrite(args, appendNewline = false)
                SshTerminalAction.INTERRUPT -> handleInterrupt(args)
                SshTerminalAction.READ -> handleRead(args)
                SshTerminalAction.CLOSE -> handleClose(args)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            unconfirmed(
                operation = args.action.operation,
                json = TerminalToolResponse.internalError(error),
            )
        }
    }

    private suspend fun handleOpen(args: SshTerminalToolArgs): RawToolOutcome {
        val sshOptions: SshOpenOptions
        try {
            sshOptions = args.requireOpenOptions()
        } catch (error: IllegalArgumentException) {
            return refusal(OPEN, invalidRequestJson(error))
        }

        return dispatch(OPEN) {
            when (val outcome =
                TerminalSessionPool.openSsh(options = sshOptions, cwd = args.cwd)) {
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
                        identity = SSH_PUBLIC_IDENTITY,
                    ),
                )

                is TerminalOpenOutcome.InvalidRequest -> unconfirmed(
                    operation = OPEN,
                    json = TerminalToolResponse.invalidRequest(outcome.message),
                )
            }
        }
    }

    private suspend fun handleWrite(
        args: SshTerminalToolArgs,
        appendNewline: Boolean,
    ): RawToolOutcome {
        val session: String
        val text: String
        try {
            session = args.requireSession()
            text = args.requireText()
        } catch (error: IllegalArgumentException) {
            return refusal(args.action.operation, invalidRequestJson(error))
        }
        val payload = if (appendNewline) "$text\n" else text
        return writePayload(
            operation = args.action.operation,
            session = session,
            payload = payload,
            requestId = args.requestId,
        )
    }

    private suspend fun handleInterrupt(args: SshTerminalToolArgs): RawToolOutcome {
        val session: String
        try {
            session = args.requireSession()
        } catch (error: IllegalArgumentException) {
            return refusal(INTERRUPT, invalidRequestJson(error))
        }
        return writePayload(
            operation = INTERRUPT,
            session = session,
            payload = CTRL_C,
            requestId = args.requestId,
        )
    }

    private suspend fun writePayload(
        operation: String,
        session: String,
        payload: String,
        requestId: String?,
    ): RawToolOutcome = dispatch(operation) {
        when (val outcome = TerminalSessionPool.writeInteractive(
            session = session,
            text = payload,
            requestId = requestId,
        )) {
            // The receipt proves the *write*, and an interactive terminal tells us nothing about
            // whether the command it started finished. `bytes_written`/`sequence`/`replayed` stay
            // readable in the unknown envelope; acceptance is never a completion.
            is TerminalInteractiveWriteOutcome.Accepted -> unconfirmed(
                operation = operation,
                json = JsonObject(
                    mapOf(
                        "accepted" to JsonPrimitive(true),
                        "bytes_written" to JsonPrimitive(outcome.bytesWritten),
                        "sequence" to JsonPrimitive(outcome.sequence),
                        "replayed" to JsonPrimitive(outcome.replayed),
                    )
                ).toString(),
            )

            // A write to a session that does not exist is refused before any input is handed over.
            is TerminalInteractiveWriteOutcome.SessionNotFound -> refusal(
                operation = operation,
                json = TerminalToolResponse.sessionNotFound(outcome.session),
            )

            is TerminalInteractiveWriteOutcome.NotInteractive -> refusal(
                operation = operation,
                json = TerminalToolResponse.invalidRequest(
                    "Session '${outcome.session}' is not an interactive SSH terminal."
                ),
            )

            is TerminalInteractiveWriteOutcome.Busy -> refusal(
                operation = operation,
                json = TerminalToolResponse.sessionBusy(outcome.session, asyncId = null),
            )

            is TerminalInteractiveWriteOutcome.UnexpectedError -> unconfirmed(
                operation = operation,
                json = TerminalToolResponse.internalError(outcome.throwable),
            )
        }
    }

    private fun handleRead(args: SshTerminalToolArgs): RawToolOutcome {
        val session: String
        try {
            session = args.requireSession()
        } catch (error: IllegalArgumentException) {
            return refusal(READ, invalidRequestJson(error))
        }
        val mode = args.readMode ?: TerminalInteractiveReadMode.DELTA
        val maxBytes = args.maxBytes ?: DEFAULT_MAX_BYTES
        return when (val outcome = TerminalSessionPool.readInteractive(
            session = session,
            mode = mode,
            maxBytes = maxBytes,
        )) {
            // Reading the terminal's output buffer is a decided observation of what it holds now.
            is TerminalInteractiveReadOutcome.Success -> decided(
                operation = READ,
                json = JsonObject(
                    mapOf(
                        "stdout" to JsonPrimitive(outcome.stdout),
                        "stderr" to JsonPrimitive(outcome.stderr),
                        "mode" to JsonPrimitive(outcome.mode.wireName),
                        "sequence" to JsonPrimitive(outcome.sequence),
                        "truncated" to JsonPrimitive(outcome.truncated),
                    )
                ).toString(),
            )

            is TerminalInteractiveReadOutcome.SessionNotFound -> refusal(
                operation = READ,
                json = TerminalToolResponse.sessionNotFound(outcome.session),
            )

            is TerminalInteractiveReadOutcome.NotInteractive -> refusal(
                operation = READ,
                json = TerminalToolResponse.invalidRequest(
                    "Session '${outcome.session}' is not an interactive SSH terminal."
                ),
            )
        }
    }

    private suspend fun handleClose(args: SshTerminalToolArgs): RawToolOutcome {
        val session: String
        try {
            session = args.requireSession()
        } catch (error: IllegalArgumentException) {
            return refusal(CLOSE, invalidRequestJson(error))
        }
        return dispatch(CLOSE) {
            when (val outcome = TerminalSessionPool.close(session = session)) {
                // Including the no-op close of a session that is already gone: the call changed
                // nothing, so the close really did succeed.
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

    /** A pool verdict this exit can rely on. */
    private fun decided(operation: String, json: String) =
        rawOutcome(operation = operation, stage = DispatchStage.DISPATCHED_CONFIRMED, json = json)

    /** The operation reached the pool and produced no verdict this exit can rely on. */
    private fun unconfirmed(operation: String, json: String) =
        rawOutcome(operation = operation, stage = DispatchStage.DISPATCHED_UNCONFIRMED, json = json)

    /** Runs the pool-call region; a throw there is post-dispatch and never retryable. */
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
        TerminalToolResponse.invalidRequest(error.message ?: "Invalid ssh_terminal request.")

    private fun parseArguments(argumentsJson: String): SshTerminalToolArgs {
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
        return SshTerminalToolArgs(
            action = SshTerminalAction.from(obj.optionalString("action")),
            session = obj.optionalString("session")?.trim(),
            text = obj.optionalString("text"),
            requestId = obj.optionalString("request_id")?.trim(),
            cwd = obj.optionalString("cwd"),
            host = obj.optionalString("host")?.trim(),
            port = obj.optionalLong("port")?.also { port ->
                require(port in 1L..65535L) { "Field 'port' must be in 1..65535." }
            }?.toInt() ?: SshOpenOptions.DEFAULT_PORT,
            username = obj.optionalString("username")?.trim(),
            password = obj.optionalString("password"),
            hostKeyPolicy = obj.optionalString("host_key_policy")?.trim(),
            knownHostsPath = obj.optionalString("known_hosts_path")?.trim(),
            strictHostKeyChecking = obj.optionalBoolean("strict_host_key_checking"),
            connectTimeoutMs = obj.optionalLong("connect_timeout_ms")?.also { timeoutMs ->
                require(timeoutMs > 0L) { "Field 'connect_timeout_ms' must be greater than 0." }
            }?.toInt() ?: SshOpenOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS,
            serverAliveIntervalMs = obj.optionalLong("server_alive_interval_ms")
                ?.also { intervalMs ->
                    require(intervalMs >= 0L) { "Field 'server_alive_interval_ms' must be greater than or equal to 0." }
                }?.toInt() ?: SshOpenOptions.DEFAULT_SERVER_ALIVE_INTERVAL_MILLIS,
            privateKeyPem = obj.optionalString("private_key_pem"),
            passphrase = obj.optionalString("passphrase"),
            readMode = obj.optionalString("mode")?.let(::parseReadMode),
            maxBytes = obj.optionalLong("max_bytes")?.also { maxBytes ->
                require(maxBytes > 0L) { "Field 'max_bytes' must be greater than 0." }
            }?.toInt(),
        )
    }

    private fun SshTerminalToolArgs.requireOpenOptions(): SshOpenOptions {
        if (!privateKeyPem.isNullOrBlank() || !passphrase.isNullOrBlank()) {
            throw IllegalArgumentException("Private key authentication is not supported yet.")
        }
        val host = host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'host' must not be blank.")
        val username = username?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'username' must not be blank.")
        val password = password?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'password' must not be blank.")
        return SshOpenOptions(
            host = host,
            port = port,
            username = username,
            auth = SshAuth.Password(password),
            hostKeyPolicy = resolveHostKeyPolicy(),
            connectTimeoutMillis = connectTimeoutMs,
            serverAliveIntervalMillis = serverAliveIntervalMs,
        )
    }

    private fun SshTerminalToolArgs.resolveHostKeyPolicy(): SshHostKeyPolicy {
        return when (hostKeyPolicy?.lowercase() ?: HOST_KEY_POLICY_ACCEPT_ANY) {
            HOST_KEY_POLICY_ACCEPT_ANY -> SshHostKeyPolicy.AcceptAny
            HOST_KEY_POLICY_KNOWN_HOSTS_FILE -> {
                val path = knownHostsPath?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException(
                        "Field 'known_hosts_path' is required when host_key_policy is 'known_hosts_file'."
                    )
                SshHostKeyPolicy.KnownHostsFile(
                    path = path,
                    strict = strictHostKeyChecking ?: true,
                )
            }

            else -> throw IllegalArgumentException(
                "Field 'host_key_policy' must be one of accept_any, known_hosts_file."
            )
        }
    }

    private fun SshTerminalToolArgs.requireSession(): String {
        return session?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'session' is required for action '${action.wireName}'.")
    }

    private fun SshTerminalToolArgs.requireText(): String {
        return text
            ?: throw IllegalArgumentException("Field 'text' is required for action '${action.wireName}'.")
    }

    private fun parseReadMode(raw: String): TerminalInteractiveReadMode {
        return when (raw.trim().lowercase()) {
            TerminalInteractiveReadMode.DELTA.wireName -> TerminalInteractiveReadMode.DELTA
            TerminalInteractiveReadMode.SNAPSHOT.wireName -> TerminalInteractiveReadMode.SNAPSHOT
            else -> throw IllegalArgumentException("Field 'mode' must be one of delta, snapshot.")
        }
    }

    private fun JsonObject.requireKnownKeys() {
        val unknownKeys = keys - REQUEST_KEYS
        if (unknownKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown ssh_terminal request field(s): ${unknownKeys.sorted().joinToString()}."
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

    private data class SshTerminalToolArgs(
        val action: SshTerminalAction,
        val session: String?,
        val text: String?,
        val requestId: String?,
        val cwd: String?,
        val host: String?,
        val port: Int,
        val username: String?,
        val password: String?,
        val hostKeyPolicy: String?,
        val knownHostsPath: String?,
        val strictHostKeyChecking: Boolean?,
        val connectTimeoutMs: Int,
        val serverAliveIntervalMs: Int,
        val privateKeyPem: String?,
        val passphrase: String?,
        val readMode: TerminalInteractiveReadMode?,
        val maxBytes: Int?,
    )

    private enum class SshTerminalAction(val wireName: String) {
        OPEN("open"),
        SEND_LINE("send_line"),
        WRITE("write"),
        INTERRUPT("interrupt"),
        READ("read"),
        CLOSE("close"),
        ;

        /** The operation name this action binds its evidence to. */
        val operation: String get() = wireName

        companion object {
            fun from(raw: String?): SshTerminalAction {
                return when (raw?.trim()?.lowercase()) {
                    "open" -> OPEN
                    "send_line" -> SEND_LINE
                    "write" -> WRITE
                    "interrupt" -> INTERRUPT
                    "read" -> READ
                    "close" -> CLOSE
                    else -> throw IllegalArgumentException(
                        "Field 'action' must be one of open, send_line, write, interrupt, read, close. " +
                                "SSH command completion is unknowable; use send_line plus read instead of exec."
                    )
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_BYTES = 8192
        private const val CTRL_C = "\u0003"
        private const val SSH_PUBLIC_IDENTITY = "ssh"

        // The operation names carried as dispatch evidence, taken from the same wire names the
        // request uses so the evidence and the parsed request cannot describe different operations.
        private val OPEN = SshTerminalAction.OPEN.wireName
        private val INTERRUPT = SshTerminalAction.INTERRUPT.wireName
        private val READ = SshTerminalAction.READ.wireName
        private val CLOSE = SshTerminalAction.CLOSE.wireName
        private const val HOST_KEY_POLICY_ACCEPT_ANY = "accept_any"
        private const val HOST_KEY_POLICY_KNOWN_HOSTS_FILE = "known_hosts_file"
        private val REQUEST_KEYS = setOf(
            "action",
            "session",
            "text",
            "request_id",
            "cwd",
            "host",
            "port",
            "username",
            "password",
            "host_key_policy",
            "known_hosts_path",
            "strict_host_key_checking",
            "connect_timeout_ms",
            "server_alive_interval_ms",
            "private_key_pem",
            "passphrase",
            "mode",
            "max_bytes",
            "command",
        )
        private const val SSH_TERMINAL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["open", "send_line", "write", "interrupt", "read", "close"],
                  "description": "Interactive SSH terminal action. Use send_line plus read instead of exec."
                },
                "session": {
                  "type": "string",
                  "description": "Opaque session handle returned by open."
                },
                "text": {
                  "type": "string",
                  "description": "Input to write. send_line appends a newline; write does not."
                },
                "request_id": {
                  "type": "string",
                  "description": "Optional idempotency key for write/send_line retry protection."
                },
                "cwd": {
                  "type": "string",
                  "description": "Optional working directory used only when opening a new SSH session."
                },
                "host": {
                  "type": "string"
                },
                "port": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 65535
                },
                "username": {
                  "type": "string"
                },
                "password": {
                  "type": "string"
                },
                "host_key_policy": {
                  "type": "string",
                  "enum": ["accept_any", "known_hosts_file"]
                },
                "known_hosts_path": {
                  "type": "string"
                },
                "strict_host_key_checking": {
                  "type": "boolean"
                },
                "connect_timeout_ms": {
                  "type": "integer",
                  "minimum": 1
                },
                "server_alive_interval_ms": {
                  "type": "integer",
                  "minimum": 0
                },
                "mode": {
                  "type": "string",
                  "enum": ["delta", "snapshot"],
                  "description": "Read mode. Defaults to delta, which consumes newly collected output."
                },
                "max_bytes": {
                  "type": "integer",
                  "minimum": 1
                },
                "private_key_pem": {
                  "type": "string",
                  "description": "Reserved for future use. Currently unsupported."
                },
                "passphrase": {
                  "type": "string",
                  "description": "Reserved for future use. Currently unsupported."
                }
              },
              "required": ["action"]
            }
        """
    }
}
