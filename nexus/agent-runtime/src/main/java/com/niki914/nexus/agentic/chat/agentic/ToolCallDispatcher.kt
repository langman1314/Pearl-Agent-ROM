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
    private val currentTools: () -> ResolvedTools?
) {
    private val phoneExecutionMutex = Mutex()
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
    ): String {
        val tools = currentTools()
        val builtinTool = tools
            ?.builtinTools
            .orEmpty()
            .filterIsInstance<LocalTool.Builtin>()
            .firstOrNull { it.name == name }
        if (builtinTool != null) {
            return if (builtinTool.name in PHONE_EXECUTION_TOOLS) {
                phoneExecutionMutex.withLock {
                    builtinToolExecutor.execute(
                        tool = builtinTool.tool,
                        argumentsJson = argumentsJson,
                    )
                }
            } else {
                builtinToolExecutor.execute(
                    tool = builtinTool.tool,
                    argumentsJson = argumentsJson,
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
