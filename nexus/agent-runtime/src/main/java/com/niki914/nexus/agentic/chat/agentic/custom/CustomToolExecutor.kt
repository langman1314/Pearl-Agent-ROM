package com.niki914.nexus.agentic.chat.agentic.custom

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CustomToolExecutor(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
    private val commandExecutor: suspend (command: String, timeoutMs: Long) -> CustomCommandExecutionResult =
        ::executeTerminalCommand,
) {
    suspend fun execute(tool: LocalTool.Custom): String {
        val command = tool.command.trim()
        if (command.isBlank()) {
            return buildFailureJson(
                command = command,
                message = "Custom tool '${tool.name}' has empty command.",
            )
        }
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return buildFailureJson(
                command = command,
                message = decision.reason.ifBlank { "Command blocked by safety policy." },
            )
        }
        val result = commandExecutor(command, timeoutMs)
        return if (result.ok) {
            buildSuccessJson(command = command, stdout = result.stdout)
        } else if (result.unconfirmed) {
            // The command had already been handed to the terminal when this happened, so its effect
            // is unknown rather than failed: it may still be running, or may already have landed.
            // Reporting a retryable failure here would invite running it twice.
            BuiltinToolResult.unknown(
                message = "Custom tool '${tool.name}' did not come back with a confirmed result: " +
                    result.message,
                hint = "Re-read the screen with screen_content to see what actually happened, then " +
                    "continue in a new round. " + BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                data = JsonObject(mapOf("command" to JsonPrimitive(command))),
            ).toJsonString()
        } else {
            buildFailureJson(
                command = command,
                message = result.message,
            )
        }
    }

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
            val exitCode = outcome.result.exitCode ?: -1
            val stdout = outcome.result.stdout.toByteArray().decodeToString() +
                    outcome.result.stderr.toByteArray().decodeToString()
            if (exitCode == 0) {
                CustomCommandExecutionResult(ok = true, stdout = stdout)
            } else {
                CustomCommandExecutionResult(
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
            message = outcome.failure.message ?: "Terminal command failed.",
        )

        is TerminalCommandOutcome.SessionNotFound -> CustomCommandExecutionResult(
            ok = false,
            message = "Terminal session '${outcome.session}' not found.",
        )

        is TerminalCommandOutcome.Busy -> CustomCommandExecutionResult(
            ok = false,
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
