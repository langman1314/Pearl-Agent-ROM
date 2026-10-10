package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
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
        /** The decoded verdict, or null when the payload is not a Nexus envelope and claims none. */
        val outcome: ToolOutcome?,
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
     * **Bookkeeping and decoding are separate questions.** A call that was actually dispatched is
     * recorded under its call id whatever its payload looks like, because forgetting it is what
     * makes a duplicate possible: a raw terminal completion (`{"exit_code":0,...}`) is not a Nexus
     * envelope, yet it still proves that call happened and must not be dispatched again.
     *
     * **Latching asks the stronger question — did this prove anything?** A decoded
     * [ToolOutcome.UNKNOWN] latches. So does a *dispatched side-effecting* call whose payload is
     * empty, truncated, or carries no recognizable verdict: it cannot prove the action did not
     * land, so it must not be replayed and must not be offered to the caller as a retryable
     * failure. Raw payloads that are a readable completion — terminal results, session handles,
     * async receipts, observation YAML — are left alone; refusing to read the screen because of
     * them would blind the agent.
     *
     * Returns the result the caller should report, which for an unreadable side-effecting payload
     * is a proper `unknown` envelope rather than the raw text.
     */
    fun afterCall(
        toolName: String,
        argumentsJson: String,
        callId: String?,
        resultJson: String,
        round: Int = generation,
        evidence: DispatchEvidence? = null,
    ): String = synchronized(lock) {
        val readOnly = toolName in READ_ONLY_TOOLS
        val decoded = BuiltinToolResult.fromJsonString(resultJson)
        // The trusted channel wins when it is present: the production branch that really ran says
        // which stage the call reached. A payload's own `stage`/`operation` fields are never read —
        // they would be self-report, and self-report is not evidence. The stage is not taken on
        // faith either: "nothing was dispatched" only counts when the payload really is a readable
        // refusal or failure, so a branch that claimed it while returning a broken body still
        // latches instead of releasing the barrier.
        val payloadIsDecided = decoded != null || ResultContract.provesDecided(resultJson, toolName)
        val notDispatched = evidence?.stage == DispatchStage.NOT_DISPATCHED && payloadIsDecided
        // A side effect that reached the tool but left no decided verdict — empty, truncated,
        // malformed, in flight, an unproven failure, or a credential that cannot carry the meaning
        // it claims — is unconfirmed. A pre-dispatch refusal and a proven completion pass this, and
        // the tool name matters: a handle is only a handle in a tool that can emit it.
        val unreadableSideEffect = !readOnly && !notDispatched &&
            decoded == null &&
            !ResultContract.provesDecided(resultJson, toolName)
        // The stage alone never decides either: a branch that claims a decision still has to hand
        // back a payload that carries one, so a definite stage over an unreadable body stays
        // unconfirmed rather than releasing the barrier.
        val stageUnconfirmed = evidence?.stage == DispatchStage.DISPATCHED_UNCONFIRMED
        val stageUndecided = evidence?.stage == DispatchStage.DISPATCHED_CONFIRMED &&
            decoded == null &&
            !ResultContract.provesDecided(resultJson, toolName)
        val outcome = when {
            // A payload that declares itself unconfirmed stays unconfirmed whatever the stage says:
            // the stage may refuse to *release* a call, never release one the payload flags.
            decoded != null && decoded.outcome == ToolOutcome.UNKNOWN -> ToolOutcome.UNKNOWN
            readOnly -> null
            notDispatched -> null
            stageUnconfirmed || stageUndecided -> ToolOutcome.UNKNOWN
            decoded != null -> decoded.outcome
            // No evidence at all: an unwired raw side effect keeps the conservative payload reading.
            unreadableSideEffect -> ToolOutcome.UNKNOWN
            else -> null
        }
        val unaccounted = unreadableSideEffect || stageUnconfirmed || stageUndecided
        // The caller is handed the canonical result, never the raw payload of an unconfirmed or
        // self-contradicting exit: a payload that says `ok=true` next to `outcome=unknown`, or a
        // verdict-less body, would otherwise be reported as if the action had been confirmed.
        val reported = when {
            decoded != null && decoded.outcome == ToolOutcome.UNKNOWN ->
                BuiltinToolResult.unknown(
                    code = decoded.code.takeIf { it.isNotBlank() }
                        ?: BuiltinToolResult.OUTCOME_UNKNOWN_CODE,
                    message = decoded.message.takeIf { it.isNotBlank() }
                        ?: "'$toolName' did not confirm whether its action took effect.",
                    hint = decoded.hint.takeIf { it.isNotBlank() }
                        ?: BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                    data = decoded.data,
                ).toJsonString()

            unaccounted && decoded != null ->
                // An envelope that carries a verdict the stage contradicts: keep what it said, but
                // report it as unconfirmed.
                BuiltinToolResult.unknown(
                    code = decoded.code.takeIf { it.isNotBlank() }
                        ?: BuiltinToolResult.OUTCOME_UNKNOWN_CODE,
                    message = decoded.message.takeIf { it.isNotBlank() }
                        ?: "'$toolName' did not confirm whether its action took effect.",
                    hint = decoded.hint.takeIf { it.isNotBlank() }
                        ?: BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                    data = decoded.data,
                ).toJsonString()

            unaccounted ->
                BuiltinToolResult.unknown(
                    message = if (evidence?.stage == DispatchStage.DISPATCHED_CONFIRMED) {
                        "'$toolName' was dispatched and returned a result, but that result does " +
                            "not carry a verdict this version can read, so its effect could not " +
                            "be confirmed."
                    } else {
                        "'$toolName' was dispatched but its result arrived empty, truncated, or " +
                            "without a readable verdict, so its effect could not be confirmed."
                    },
                    hint = "Re-read the screen with screen_content to see what actually happened, " +
                        "then continue in a new round. " + BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                    // Whatever the raw body did say — a write receipt, partial output, an error
                    // code — stays readable. Throwing it away would leave the caller with less
                    // information than the payload it replaced, and would hide the diagnostics
                    // that make the failure understandable without re-running anything.
                    data = ResultContract.diagnostics(resultJson),
                ).toJsonString()

            else -> resultJson
        }

        if (round != generation) {
            // Late result: its round is over, so its bookkeeping would describe a call this round
            // never made. The device fact it carries still counts.
            if (outcome == ToolOutcome.UNKNOWN) {
                latch(toolName, "'$toolName' went unconfirmed in the previous round")
            }
            return@synchronized reported
        }

        val record = Recorded(
            toolName = toolName,
            argumentsJson = argumentsJson,
            outcome = outcome,
            // The canonical result, not the raw payload: a replay of this call id must hand back
            // exactly what the first call returned, so the first return and the replay cannot
            // disagree about what the tool proved.
            resultJson = reported,
        )
        if (readOnly) {
            // An observation ends the id-less replay window: whatever the agent does next is a new
            // action, not a repeat of the call it made before looking at the screen.
            lastIdlessCall = null
        } else {
            val id = callId?.takeIf { it.isNotBlank() }
            if (id != null) {
                recordedByCallId[id] = record
                // A call that carries an id is not the id-less repeat this narrow policy covers.
                lastIdlessCall = null
            } else {
                lastIdlessCall = record
            }
        }
        if (outcome == ToolOutcome.UNKNOWN) {
            latch(toolName, "'$toolName' returned an unconfirmed outcome")
        }

        reported
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
