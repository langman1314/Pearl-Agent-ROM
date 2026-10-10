package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single gate every local tool call goes through.
 *
 * Two rules, and they are the whole point of this class:
 *
 * 1. **One barrier for every side effect.** Anything that can change the device or the agent's
 *    state — phone actions, `terminal` / `ssh_terminal`, custom tools, config and memory writes —
 *    is checked against [RoundActionGuard] before it is dispatched and recorded after. Only the
 *    tools on [RoundActionGuard.READ_ONLY_TOOLS], which cannot change anything, run unguarded, so
 *    the agent can always re-observe the screen to find out what actually happened.
 * 2. **A dispatched call is never forgotten.** Recording happens on every exit path, not just the
 *    happy one: if the call was already handed over and then threw or was cancelled, the round is
 *    latched as indeterminate instead of leaving the caller free to repeat an action that may
 *    already have landed.
 *
 * Execution is serialised by one mutex, because the guard's check-then-dispatch sequence is only
 * sound if no other call can slip between the two.
 */
class ToolCallDispatcher(
    private val builtinToolExecutor: BuiltinToolExecutor = BuiltinToolExecutor(),
    private val customToolExecutor: CustomToolExecutor = CustomToolExecutor(),
    private val actionGuard: RoundActionGuard = RoundActionGuard(),
    private val currentTools: () -> ResolvedTools?,
) {
    private val executionMutex = Mutex()

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

    suspend fun executeCustomTool(tool: LocalTool.Custom, callId: String? = null): String {
        // A custom tool runs a shell command, so it is a side effect like any other and takes the
        // same barrier. Its arguments live in the tool definition, not in the call, so the call
        // identity is the tool name plus the call id.
        return executeGuarded(toolName = tool.name, argumentsJson = "{}", callId = callId) {
            customToolExecutor.execute(tool)
        }
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
            return executeGuarded(
                toolName = name,
                argumentsJson = argumentsJson,
                callId = callId,
            ) {
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
            return executeGuarded(
                toolName = name,
                argumentsJson = argumentsJson,
                callId = callId,
            ) {
                customToolExecutor.execute(customTool)
            }
        }

        // Nothing is dispatched, so this is an ordinary failure the caller may retry.
        return BuiltinToolResult.failure(
            code = "LOCAL_TOOL_NOT_EXECUTABLE",
            message = "Local tool '$name' is not executable in current runtime.",
            hint = "Check builtin_tool_flags or custom_tools configuration.",
        ).toJsonString()
    }

    /**
     * Runs a local tool unless the round is already poisoned or this exact call already ran.
     *
     * The guard is consulted and updated inside the mutex so two concurrent calls with the same id
     * cannot both reach the device.
     */
    private suspend fun executeGuarded(
        toolName: String,
        argumentsJson: String,
        callId: String?,
        block: suspend () -> String,
    ): String = executionMutex.withLock {
        when (val decision = actionGuard.beforeCall(toolName, argumentsJson, callId)) {
            is RoundActionGuard.Decision.Replay -> decision.resultJson
            is RoundActionGuard.Decision.Refused -> decision.resultJson
            RoundActionGuard.Decision.Proceed -> dispatchAndRecord(
                toolName = toolName,
                argumentsJson = argumentsJson,
                callId = callId,
                block = block,
            )
        }
    }

    /**
     * Dispatches an approved call and records what came of it.
     *
     * The round is captured before dispatch, so a result that only arrives after its round ended is
     * recognisable as late and cannot masquerade as a call of the round now running.
     */
    private suspend fun dispatchAndRecord(
        toolName: String,
        argumentsJson: String,
        callId: String?,
        block: suspend () -> String,
    ): String {
        val round = actionGuard.generation
        val resultJson = try {
            block()
        } catch (cancellation: CancellationException) {
            // The request is already on its way; cancelling this coroutine does not recall it.
            // Record that before propagating, or the next call in the round could repeat it.
            actionGuard.recordIndeterminate(
                toolName = toolName,
                description = "'$toolName' was cancelled after it had been dispatched",
            )
            throw cancellation
        } catch (throwable: Throwable) {
            actionGuard.recordIndeterminate(
                toolName = toolName,
                description = "'$toolName' failed after it had been dispatched " +
                    "(${throwable.message ?: throwable::class.java.simpleName})",
            )
            throw throwable
        }
        actionGuard.afterCall(toolName, argumentsJson, callId, resultJson, round)
        return resultJson
    }
}
