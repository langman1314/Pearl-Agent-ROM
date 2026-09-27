package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ToolCallDispatcher(
    private val builtinToolExecutor: BuiltinToolExecutor = BuiltinToolExecutor(),
    private val customToolExecutor: CustomToolExecutor = CustomToolExecutor(),
    private val actionGuard: RoundActionGuard = RoundActionGuard(),
    private val currentTools: () -> ResolvedTools?,
) {
    private val phoneExecutionMutex = Mutex()

    /** Starts a new agent round: clears duplicate suppression and the unknown-outcome latch. */
    fun beginRound() {
        actionGuard.beginRound()
    }

    fun findCustomTool(name: String): LocalTool.Custom? {
        return currentTools()
            ?.customTools
            .orEmpty()
            .filterIsInstance<LocalTool.Custom>()
            .firstOrNull { it.name == name }
    }

    suspend fun executeCustomTool(tool: LocalTool.Custom): String {
        return customToolExecutor.execute(tool)
    }

    suspend fun executeLocalTool(
        name: String,
        argumentsJson: String,
        callId: String? = null,
    ): String {
        val tools = currentTools()
        val builtinTool = tools
            ?.builtinTools
            .orEmpty()
            .filterIsInstance<LocalTool.Builtin>()
            .firstOrNull { it.name == name }
        if (builtinTool != null) {
            return if (builtinTool.name in PHONE_EXECUTION_TOOLS) {
                // Phone tools go through one mutex, so the guard's read-then-execute sequence
                // cannot interleave with another phone action.
                phoneExecutionMutex.withLock {
                    guardedExecute(toolName = name, argumentsJson = argumentsJson, callId = callId) {
                        builtinToolExecutor.execute(
                            tool = builtinTool.tool,
                            argumentsJson = argumentsJson,
                            callId = callId,
                        )
                    }
                }
            } else {
                builtinToolExecutor.execute(
                    tool = builtinTool.tool,
                    argumentsJson = argumentsJson,
                    callId = callId,
                )
            }
        }

        val customTool = tools
            ?.customTools
            .orEmpty()
            .filterIsInstance<LocalTool.Custom>()
            .firstOrNull { it.name == name }
        if (customTool != null) {
            return executeCustomTool(customTool)
        }

        return BuiltinToolResult.failure(
            code = "LOCAL_TOOL_NOT_EXECUTABLE",
            message = "Local tool '$name' is not executable in current runtime.",
            hint = "Check builtin_tool_flags or custom_tools configuration.",
        ).toJsonString()
    }

    /**
     * Runs a phone action unless the round is already poisoned or this exact call already ran.
     *
     * The guard is consulted and updated inside the caller's mutex so two concurrent calls with
     * the same id cannot both reach the device.
     */
    private suspend fun guardedExecute(
        toolName: String,
        argumentsJson: String,
        callId: String?,
        block: suspend () -> String,
    ): String {
        return when (val decision = actionGuard.beforeCall(toolName, argumentsJson, callId)) {
            is RoundActionGuard.Decision.Replay -> decision.resultJson
            is RoundActionGuard.Decision.Refused -> decision.resultJson
            RoundActionGuard.Decision.Proceed -> {
                val resultJson = block()
                actionGuard.afterCall(toolName, argumentsJson, callId, resultJson)
                resultJson
            }
        }
    }

    private companion object {
        val PHONE_EXECUTION_TOOLS = setOf(
            "launch_app",
            "open_uri",
            "screen_content",
            "search_nodes",
            "node_action",
            "gesture",
            "key_event",
        )
    }
}
