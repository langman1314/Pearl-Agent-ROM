package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome

/**
 * Tracks side-effecting tool calls inside a single agent round.
 *
 * Two jobs, both about not doing something twice:
 *
 * 1. **Duplicate suppression.** The model, or a retry, may emit the same tool call twice with the
 *    same call id. The first call's result is replayed instead of dispatching the action again.
 * 2. **Unknown latch.** Once an action's outcome is [ToolOutcome.UNKNOWN] the operation may
 *    already have reached the phone, so every later side-effecting call in the round is refused.
 *    Read-only observation stays available, because finding out what actually happened is exactly
 *    what the agent should do next.
 *
 * Scope is deliberately one round: [beginRound] clears everything. This is an in-memory guard, so
 * it protects a single agent round, not a process restart — a crash between dispatch and result
 * cannot be detected from here. See the task notes on restart-safe dedupe.
 *
 * Callers on the phone-execution path use this under a mutex; on its own it is not thread-safe.
 */
class RoundActionGuard {
    private data class Recorded(
        val toolName: String,
        val outcome: ToolOutcome,
        val resultJson: String,
    )

    private val lock = Any()
    private val recordedByCallId = LinkedHashMap<String, Recorded>()
    private val recordedByIdentity = LinkedHashMap<String, Recorded>()
    private var latchedBy: Recorded? = null

    /** Starts a fresh round; the previous round's dedupe and latch state is discarded. */
    fun beginRound() = synchronized(lock) {
        recordedByCallId.clear()
        recordedByIdentity.clear()
        latchedBy = null
    }

    val isLatched: Boolean get() = synchronized(lock) { latchedBy != null }

    /** The tool whose unconfirmed outcome latched this round, if any. */
    val latchSource: String? get() = synchronized(lock) { latchedBy?.toolName }

    /**
     * Decides what to do with an incoming call.
     *
     * Returns [Decision.Proceed] when the call should be dispatched, or a decision carrying the
     * result to report instead of executing anything.
     */
    fun beforeCall(
        toolName: String,
        argumentsJson: String,
        callId: String?,
    ): Decision = synchronized(lock) {
        // Re-observing the screen is how the agent recovers, so it must always really run.
        if (toolName in READ_ONLY_TOOLS) return@synchronized Decision.Proceed

        duplicate(toolName, argumentsJson, callId)?.let { return@synchronized Decision.Replay(it.resultJson) }

        val pending = latchedBy
        if (pending != null) {
            return@synchronized Decision.Refused(
                BuiltinToolResult.failure(
                    code = "ACTION_STATE_INDETERMINATE",
                    message = "Tool '$toolName' was not sent because '${pending.toolName}' may " +
                        "already have taken effect but its result is unconfirmed. Nexus stopped " +
                        "all further side effects for this round instead of risking a duplicate.",
                    hint = "Re-read the screen with screen_content to see what actually happened, " +
                        "then continue in a new round. " + BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                ).toJsonString(),
            )
        }
        Decision.Proceed
    }

    /**
     * Records a finished call so the same call id is never dispatched twice, and latches the round
     * when the outcome could not be confirmed.
     *
     * Only an explicitly decoded [ToolOutcome.UNKNOWN] latches. Raw payloads that are not Nexus
     * envelopes (the YAML from `screen_content` / `search_nodes`, raw terminal output) are left
     * alone — refusing to read the screen because of them would blind the agent.
     */
    fun afterCall(
        toolName: String,
        argumentsJson: String,
        callId: String?,
        resultJson: String,
    ) = synchronized(lock) {
        val decoded = BuiltinToolResult.fromJsonString(resultJson)
        if (decoded == null) return@synchronized
        val record = Recorded(
            toolName = toolName,
            outcome = decoded.outcome,
            resultJson = resultJson,
        )
        if (toolName !in READ_ONLY_TOOLS) {
            callId?.takeIf { it.isNotBlank() }?.let { recordedByCallId[it] = record }
            recordedByIdentity[identity(toolName, argumentsJson)] = record
        }
        if (record.outcome == ToolOutcome.UNKNOWN && latchedBy == null) {
            latchedBy = record
        }
    }

    private fun duplicate(
        toolName: String,
        argumentsJson: String,
        callId: String?,
    ): Recorded? {
        callId?.takeIf { it.isNotBlank() }?.let { id ->
            recordedByCallId[id]?.let { return it }
        }
        // Without a usable call id, a verbatim repeat of the same call is still a duplicate.
        return recordedByIdentity[identity(toolName, argumentsJson)]
    }

    private fun identity(toolName: String, argumentsJson: String): String {
        return "$toolName\n${argumentsJson.trim()}"
    }

    sealed interface Decision {
        /** Dispatch the call normally. */
        data object Proceed : Decision

        /** Report a previously recorded result instead of executing the action again. */
        data class Replay(val resultJson: String) : Decision

        /** The call was refused; [resultJson] explains why. */
        data class Refused(val resultJson: String) : Decision
    }

    companion object {
        /**
         * Tools that only read the screen. They stay usable after an unconfirmed action, since
         * re-observing is how the agent recovers.
         */
        val READ_ONLY_TOOLS = setOf("screen_content", "search_nodes")
    }
}
