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
        return executeWithEvidence(tool, argumentsJson, callId).resultJson
    }

    /**
     * Runs the tool once and reports what its exit could prove about the dispatch stage.
     *
     * A tool that implements [RawOutcomeProducing] is asked for its evidence and its result in the
     * same call, so the work never runs twice. Everything else reports no stage: the guard then
     * treats a side effect conservatively instead of accepting a payload's own claim, while the
     * `BuiltinToolResult` envelope that non-raw tools return still speaks for itself.
     */
    suspend fun executeWithEvidence(
        tool: BuiltinTool,
        argumentsJson: String,
        callId: String? = null,
    ): ToolExecution {
        val request = BuiltinToolRequest(
            name = tool.name,
            argumentsJson = argumentsJson,
            callId = callId,
        )
        return try {
            when {
                tool is RawOutcomeProducing -> {
                    val outcome = tool.invokeRawJsonWithEvidence(request)
                    ToolExecution(
                        resultJson = outcome.resultJson,
                        stage = outcome.stage,
                        operation = outcome.operation,
                    )
                }

                tool is RawJsonBuiltinTool -> ToolExecution(resultJson = tool.invokeRawJson(request))

                else -> ToolExecution(resultJson = tool.invoke(request).toJsonString())
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            // The tool threw instead of answering. Whether anything reached the device is exactly
            // what is unknown here, so no stage is claimed and the guard stays conservative.
            ToolExecution(resultJson = UnexpectedToolFailure(tool.name, throwable).toResultJson())
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
