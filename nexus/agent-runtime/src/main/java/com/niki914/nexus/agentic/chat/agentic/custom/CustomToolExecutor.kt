package com.niki914.nexus.agentic.chat.agentic.custom

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolExecution
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolResultSource
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CustomToolExecutor(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
    private val commandExecutor: suspend (command: String, timeoutMs: Long) -> CustomCommandExecutionResult =
        ::executeTerminalCommand,
) {
    suspend fun execute(tool: LocalTool.Custom): String {
        return executeWithEvidence(tool).resultJson
    }

    /**
     * Runs the custom tool's command and reports the stage its own result proves.
     *
     * The evidence is built from the *real* execution result, never from the command's own stdout: a
     * command printing "done" has proved nothing about whether it ran. Every exit before the
     * executor call is a refusal; every exit after it is at least dispatched, and an exit that
     * cannot prove a definite result stays unconfirmed.
     */
    suspend fun executeWithEvidence(tool: LocalTool.Custom): ToolExecution {
        val command = tool.command.trim()
        if (command.isBlank()) {
            return refused(
                buildFailureJson(
                    command = command,
                    message = "Custom tool '${tool.name}' has empty command.",
                )
            )
        }
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return refused(
                buildFailureJson(
                    command = command,
                    message = decision.reason.ifBlank { "Command blocked by safety policy." },
                )
            )
        }
        val result = try {
            commandExecutor(command, timeoutMs)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // The executor threw rather than answering, which says nothing about whether the command
            // reached the terminal: unconfirmed, not a retryable failure.
            return unconfirmed(
                BuiltinToolResult.unknown(
                    message = "Custom tool '${tool.name}' failed while running its command, so its " +
                        "effect could not be confirmed: ${throwable.message ?: "unknown error"}",
                    hint = "Re-read the screen with screen_content to see what actually happened, " +
                        "then continue in a new round. " + BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                ).toJsonString()
            )
        }
        return if (result.ok) {
            // A definite success: the command ran and reported exit code 0.
            ToolExecution(
                resultJson = buildSuccessJson(command = command, stdout = result.stdout),
                stage = DispatchStage.DISPATCHED_CONFIRMED,
                source = ToolResultSource.CUSTOM_COMMAND,
            )
        } else if (result.unconfirmed) {
            // The command had already been handed to the terminal when this happened, so its effect
            // is unknown rather than failed: it may still be running, or may already have landed.
            // Reporting a retryable failure here would invite running it twice.
            unconfirmed(
                BuiltinToolResult.unknown(
                    message = "Custom tool '${tool.name}' did not come back with a confirmed " +
                        "result: ${result.message}",
                    hint = "Re-read the screen with screen_content to see what actually happened, " +
                        "then continue in a new round. " + BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                    data = JsonObject(mapOf("command" to JsonPrimitive(command))),
                ).toJsonString()
            )
        } else {
            // A definite failure: the command really ran and really reported one, so the caller may
            // retry it. This is a decided result even though the outcome is a failure — the stage
            // records that the call was dispatched, not that it succeeded.
            ToolExecution(
                resultJson = buildFailureJson(command = command, message = result.message),
                stage = DispatchStage.DISPATCHED_CONFIRMED,
                source = ToolResultSource.CUSTOM_COMMAND,
            )
        }
    }

    private fun refused(json: String) = ToolExecution(
        resultJson = json,
        stage = DispatchStage.NOT_DISPATCHED,
        source = ToolResultSource.CUSTOM_COMMAND,
    )

    private fun unconfirmed(json: String) = ToolExecution(
        resultJson = json,
        stage = DispatchStage.DISPATCHED_UNCONFIRMED,
        source = ToolResultSource.CUSTOM_COMMAND,
    )

    private fun buildSuccessJson(command: String, stdout: String): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(true),
                "command" to JsonPrimitive(command),
                "stdout" to JsonPrimitive(stdout),
            )
        ).toString()
    }

    private fun buildFailureJson(command: String, message: String): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(false),
                "command" to JsonPrimitive(command),
                "message" to JsonPrimitive(message),
            )
        ).toString()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    }
}

data class CustomCommandExecutionResult(
    val ok: Boolean,
    val stdout: String = "",
    val message: String = "",
    /**
     * The command was already dispatched when this result was produced, and it did not report a
     * confirmed verdict: a timeout or a crash of the command channel. Retrying it could repeat an
     * action that already took effect, so it must not be offered as a plain failure.
     */
    val unconfirmed: Boolean = false,
)

private suspend fun executeTerminalCommand(
    command: String,
    timeoutMs: Long
): CustomCommandExecutionResult {
    return when (val outcome =
        TerminalSessionPool.executeCustomCommand(command = command, timeoutMs = timeoutMs)) {
        is TerminalCommandOutcome.Success -> {
            val exitCode = outcome.result.exitCode
            val stdout = outcome.result.stdout.toByteArray().decodeToString() +
                    outcome.result.stderr.toByteArray().decodeToString()
            when {
                exitCode == null -> CustomCommandExecutionResult(
                    ok = false,
                    // A completion credential that is missing proves nothing about the result, so
                    // this cannot be reported as the definite failure a retry would assume.
                    unconfirmed = true,
                    message = stdout.ifBlank {
                        "The command finished without reporting an exit code."
                    },
                )

                exitCode == 0 -> CustomCommandExecutionResult(ok = true, stdout = stdout)

                else -> CustomCommandExecutionResult(
                    ok = false,
                    message = stdout.ifBlank { "Command exited with code $exitCode." },
                )
            }
        }

        is TerminalCommandOutcome.Timeout -> CustomCommandExecutionResult(
            ok = false,
            // The command was already running: a timeout says nothing about whether its effect
            // landed, so this is an unconfirmed outcome, not a retryable failure.
            unconfirmed = true,
            message = "Command timed out after ${timeoutMs}ms.",
        )

        is TerminalCommandOutcome.Failure -> CustomCommandExecutionResult(
            ok = false,
            // This runs inside an open-then-execute composite, so the command may already have been
            // handed over before the failure surfaced: not a definite result.
            unconfirmed = true,
            message = outcome.failure.message ?: "Terminal command failed.",
        )

        is TerminalCommandOutcome.SessionNotFound -> CustomCommandExecutionResult(
            ok = false,
            // The open step of this composite call may already have happened, so a later lookup
            // failure does not prove the command never ran.
            unconfirmed = true,
            message = "Terminal session '${outcome.session}' not found.",
        )

        is TerminalCommandOutcome.Busy -> CustomCommandExecutionResult(
            ok = false,
            unconfirmed = true,
            message = "Another custom tool command is already running.",
        )

        is TerminalCommandOutcome.UnexpectedError -> CustomCommandExecutionResult(
            ok = false,
            // The command channel broke after the command was handed over: the same reasoning as a
            // timeout applies, so this stays unconfirmed rather than retryable.
            unconfirmed = true,
            message = outcome.throwable.message ?: "Unexpected terminal error.",
        )
    }
}
