package com.niki914.nexus.agentic.chat.agentic.buildin

import kotlinx.coroutines.CancellationException

class BuiltinToolExecutor(
    private val registry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    fun find(name: String): BuiltinTool? {
        return registry.find(name)
    }

    suspend fun execute(
        name: String,
        argumentsJson: String,
        callId: String? = null,
    ): String {
        val tool = find(name)
            ?: return BuiltinToolResult.failure(
                code = "LOCAL_TOOL_NOT_EXECUTABLE",
                message = "Local tool '$name' is not executable in current runtime.",
                hint = "Check builtin_tool_flags or custom_tools configuration.",
            ).toJsonString()

        return execute(tool = tool, argumentsJson = argumentsJson, callId = callId)
    }

    suspend fun execute(
        tool: BuiltinTool,
        argumentsJson: String,
        callId: String? = null,
    ): String {
        if (tool is RawJsonBuiltinTool) {
            return try {
                tool.invokeRawJson(
                    BuiltinToolRequest(
                        name = tool.name,
                        argumentsJson = argumentsJson,
                        callId = callId,
                    )
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                UnexpectedToolFailure(tool.name, throwable).toResultJson()
            }
        }
        return try {
            tool.invoke(
                BuiltinToolRequest(
                    name = tool.name,
                    argumentsJson = argumentsJson,
                    callId = callId,
                )
            ).toJsonString()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            UnexpectedToolFailure(tool.name, throwable).toResultJson()
        }
    }

    /**
     * A crash inside a tool that already had its arguments accepted is *not* proof that nothing
     * happened: the action may have been dispatched before the exception surfaced. It is reported
     * as [ToolOutcome.UNKNOWN] so the caller re-observes instead of replaying it.
     */
    private class UnexpectedToolFailure(private val toolName: String, throwable: Throwable) {
        private val message = throwable.message ?: "Builtin tool '$toolName' failed."

        fun toResultJson(): String = BuiltinToolResult.unknown(
            message = "Builtin tool '$toolName' failed after being invoked, so its effect could " +
                "not be confirmed: $message",
            hint = "Re-read the screen with screen_content to check the actual state before " +
                "retrying. " + BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
        ).toJsonString()
    }
}
