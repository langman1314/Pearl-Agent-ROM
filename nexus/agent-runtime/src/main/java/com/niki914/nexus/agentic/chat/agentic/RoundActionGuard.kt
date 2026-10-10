package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome

/**
 * Tracks side-effecting tool calls inside a single agent round.
 *
 * Two jobs, both about not doing something twice:
 *
 * 1. **Duplicate suppression.** A stable call id identifies exactly one call: re-emitting that id
 *    replays its recorded result instead of dispatching the action again. A *different* call id is a
 *    different call, so repeating the same arguments (a second `key_event` BACK, another scroll,
 *    the same node index after a fresh observation) really executes again — only the call id proves
 *    a duplicate.
 * 2. **Unknown latch.** Once an action's outcome is [ToolOutcome.UNKNOWN] the operation may
 *    already have reached the phone, so every later side-effecting call in the round is refused.
 *    Read-only observation stays available, because finding out what actually happened is exactly
 *    what the agent should do next.
 *
 * Scope is deliberately one round: [beginRound] clears everything and advances [generation].
 * A result that arrives after the round it was dispatched in has been superseded is a *late
 * result*: it never contributes duplicate-suppression state to the new round, because that state
 * would describe a call the new round never made. Its unconfirmed outcome still latches, because
 * "this may already have reached the phone" is a fact about the device, not round-local
 * bookkeeping, and silently dropping it would invite exactly the duplicate this guard prevents.
 *
 * This is an in-memory guard, so it protects a single agent round, not a process restart — a crash
 * between dispatch and result cannot be detected from here. See the task notes on restart-safe
 * dedupe.
 *
 * Callers on the phone-execution path use this under a mutex; on its own it is not thread-safe.
 */
class RoundActionGuard {
    private data class Recorded(
        val toolName: String,
        val argumentsJson: String,
        val outcome: ToolOutcome,
        val resultJson: String,
    )

    private val lock = Any()
    private val recordedByCallId = LinkedHashMap<String, Recorded>()

    /**
     * Monotonic round counter, advanced by [beginRound].
     *
     * Callers capture it before dispatching and hand it back when the call finishes, so a result
     * that arrives after its own round ended can be recognised as late.
     */
    var generation: Int = 0
        private set

    /**
     * The most recent call recorded *without* a call id, if any.
     *
     * Callers that pass no id leave the guard unable to tell a retry from a deliberate repeat, so
     * the no-id policy is deliberately narrow and bounded: only an immediate, verbatim repeat of the
     * previous id-less call is treated as a duplicate, and it is consumed by that one replay. An
     * identical action later in the round is a separate call, never a silently folded-in one.
     */
    private var lastIdlessCall: Recorded? = null

    private var latchedBy: Recorded? = null

    /** Starts a fresh round; the previous round's dedupe and latch state is discarded. */
    fun beginRound() = synchronized(lock) {
        recordedByCallId.clear()
        lastIdlessCall = null
        latchedBy = null
        // Advancing the round counter is what makes a late result recognisable: without it a call
        // whose round already ended would be recorded as if this round had made it.
        generation++
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

        resolveDuplicate(toolName, argumentsJson, callId)?.let { return@synchronized it }

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
        round: Int = generation,
    ) = synchronized(lock) {
        if (round != generation) {
            // Late result: its round is over, so its bookkeeping would describe a call this round
            // never made. The device fact it carries still counts.
            if (BuiltinToolResult.fromJsonString(resultJson)?.outcome == ToolOutcome.UNKNOWN) {
                latch(toolName, "'$toolName' went unconfirmed in the previous round")
            }
            return@synchronized
        }
        val decoded = BuiltinToolResult.fromJsonString(resultJson)
        if (decoded == null) return@synchronized
        val record = Recorded(
            toolName = toolName,
            argumentsJson = argumentsJson,
            outcome = decoded.outcome,
            resultJson = resultJson,
        )
        if (toolName !in READ_ONLY_TOOLS) {
            val id = callId?.takeIf { it.isNotBlank() }
            if (id != null) {
                recordedByCallId[id] = record
                // A call that carries an id is not the id-less repeat this narrow policy covers.
                lastIdlessCall = null
            } else {
                lastIdlessCall = record
            }
        }
        if (record.outcome == ToolOutcome.UNKNOWN) {
            latch(toolName, "'$toolName' returned an unconfirmed outcome")
        }
    }

    /**
     * Latches the round because [toolName] was already dispatched and its real effect is unknown.
     *
     * This is the path for a call that never produced a result to record — it was cancelled or threw
     * after dispatch. Reporting it as a clean failure would be wrong for the same reason an explicit
     * [ToolOutcome.UNKNOWN] is wrong: the action may already have landed, so a retry could do it
     * twice.
     */
    fun recordIndeterminate(toolName: String, description: String) = synchronized(lock) {
        latch(toolName, description)
    }

    private fun latch(toolName: String, description: String) {
        if (latchedBy != null) return
        latchedBy = Recorded(
            toolName = toolName,
            argumentsJson = "",
            outcome = ToolOutcome.UNKNOWN,
            resultJson = BuiltinToolResult.unknown(message = description).toJsonString(),
        )
    }

    /**
     * Reports what a repeated call id must reuse, or refuses a call id that came back describing a
     * different call. Returns null when the call is not a known duplicate.
     */
    private fun resolveDuplicate(
        toolName: String,
        argumentsJson: String,
        callId: String?,
    ): Decision? {
        val id = callId?.takeIf { it.isNotBlank() }
        if (id != null) {
            val recorded = recordedByCallId[id] ?: return null
            if (recorded.toolName != toolName || recorded.argumentsJson != argumentsJson) {
                return Decision.Refused(
                    BuiltinToolResult.failure(
                        code = "ACTION_CALL_ID_CONFLICT",
                        message = "Call id '$id' already belongs to " +
                            "'${recorded.toolName}' with different arguments, so this call is not " +
                            "the one that id identifies. Nexus did not send it, because reporting " +
                            "the earlier result for a different action would be misleading.",
                        hint = "Repeat the action with a fresh call id, or report the original " +
                            "call's result as-is.",
                    ).toJsonString(),
                )
            }
            return Decision.Replay(recorded.resultJson)
        }

        // No usable id: one immediate verbatim repeat of the previous id-less call is a duplicate.
        val pending = lastIdlessCall ?: return null
        if (pending.toolName != toolName || pending.argumentsJson != argumentsJson) return null
        // Consume it, so a third identical id-less call is a new call rather than being folded in.
        lastIdlessCall = null
        return Decision.Replay(pending.resultJson)
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
