package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.DeclaredOperationProducing
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolExecution
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolResultSource
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
        return executeGuarded(
            toolName = tool.name,
            argumentsJson = "{}",
            callId = callId,
            // A custom tool's arguments live in its definition and running its command is not one of
            // the terminal operations a credential could be bound to, so it declares none — but it
            // does report the stage its own command result proved.
            declaredOperation = null,
            declarationKnown = false,
        ) {
            customToolExecutor.executeWithEvidence(tool)
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
            // Read from the request *before* dispatching, so the operation this call is judged
            // against is what the caller asked for — never something the result reports about
            // itself. A tool that cannot establish one declares nothing.
            val operationProducing = builtinTool.tool as? DeclaredOperationProducing
            val declaredOperation = operationProducing?.declaredOperation(argumentsJson)
            return executeGuarded(
                toolName = name,
                argumentsJson = argumentsJson,
                callId = callId,
                declaredOperation = declaredOperation,
                declarationKnown = operationProducing != null,
            ) {
                builtinToolExecutor.executeWithEvidence(
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
                declaredOperation = null,
                declarationKnown = false,
            ) {
                // The custom tool executor owns the real execution result, so it reports the stage
                // alongside the JSON: a blocked command never ran, and a dispatched one that lost
                // its verdict is unconfirmed rather than retryable.
                customToolExecutor.executeWithEvidence(customTool)
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
        declaredOperation: String?,
        declarationKnown: Boolean,
        block: suspend () -> ToolExecution,
    ): String = executionMutex.withLock {
        when (val decision = actionGuard.beforeCall(toolName, argumentsJson, callId)) {
            is RoundActionGuard.Decision.Replay -> decision.resultJson
            is RoundActionGuard.Decision.Refused -> decision.resultJson
            RoundActionGuard.Decision.Proceed -> dispatchAndRecord(
                toolName = toolName,
                argumentsJson = argumentsJson,
                callId = callId,
                declaredOperation = declaredOperation,
                declarationKnown = declarationKnown,
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
        declaredOperation: String?,
        declarationKnown: Boolean,
        block: suspend () -> ToolExecution,
    ): String {
        val round = actionGuard.generation
        val execution = try {
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
        // The guard returns what the caller must report: for a dispatched call whose payload was
        // empty, truncated, or verdict-less it is a proper `unknown` envelope instead of the raw
        // text, so the caller cannot mistake it for a retryable failure.
        return actionGuard.afterCall(
            toolName = toolName,
            argumentsJson = argumentsJson,
            callId = callId,
            resultJson = execution.resultJson,
            round = round,
            evidence = DispatchEvidence(
                stage = execution.stage,
                // The operation the *request* asked for, taken from the tool's own argument rules
                // before the call ran. A tool that can establish one but could not for this request
                // declares *nothing* — and nothing is not "reuse whatever the exit claims", because
                // that would let a result name the operation that releases it. Only a tool with no
                // notion of operations at all keeps reporting one from its exit.
                operation = if (declarationKnown) {
                    declaredOperation
                } else {
                    execution.operation ?: toolName
                },
                source = execution.source,
            ),
        )
    }
}

/**
 * What the guard needs to know about one call beyond its payload: which stage the tool's own exit
 * proved, which operation the request declared, and which branch produced the result.
 *
 * [stage] is null when the branch that ran reports no stage at all — nothing about the call's
 * dispatch is known, so the stage may not be used to release anything. [operation] is null when no
 * operation could be established from the request, and then it constrains nothing. [source] records
 * the branch that really ran, so a raw body with no exit behind it can never be read as a verdict.
 */
data class DispatchEvidence(
    val stage: DispatchStage?,
    val operation: String?,
    val source: ToolResultSource? = null,
)
