package com.niki914.nexus.agentic.chat.agentic.accessibility

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome

/**
 * Result of one shell command issued on behalf of a phone action.
 *
 * [mayHaveTakenEffect] is the field that decides how a failure is reported: a command that was
 * already handed to the shell may have executed, so it can never be reported as a clean failure
 * the caller is free to retry.
 */
internal data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val mayHaveTakenEffect: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0
}

/**
 * Decides how a failed shell action must be reported.
 *
 * Kept separate from [AccessibilityController] so the rule can be tested directly: the controller
 * owns the Android plumbing, this owns the judgement.
 */
internal object ShellActionClassifier {
    /**
     * A command that never reached the device is an ordinary failure — nothing happened, so the
     * agent may retry. A command that had already been dispatched is reported as
     * [ToolOutcome.UNKNOWN], because Nexus cannot tell whether it took effect.
     */
    fun classifyFailure(
        code: String,
        action: String,
        result: ShellResult,
    ): BuiltinToolResult {
        if (!result.mayHaveTakenEffect) {
            return BuiltinToolResult.failure(code, "$action failed: ${result.stderr}")
        }
        return BuiltinToolResult.unknown(
            message = "$action was already sent to the device but its result could not be " +
                "confirmed (${result.stderr}). Nexus will not send it again.",
        )
    }
}

/**
 * Round-scoped record of the first action whose effect could not be confirmed.
 *
 * Once an action is recorded, [refusal] blocks every further side-effecting action for the rest of
 * the round. Finding out what actually happened stays possible — re-reading the screen is not a
 * side effect — but nothing new is dispatched, because the recorded action may already have landed
 * and a retry would risk doing it twice.
 *
 * [reset] is called at end of turn, which is what makes the guard round-scoped rather than
 * permanent. This is in-memory state: it cannot detect an action that was interrupted by a process
 * death, so it is not a restart-safe exactly-once guarantee.
 */
internal class IndeterminateActionTracker {
    private var recorded: String? = null

    val isLatched: Boolean get() = recorded != null

    /** Description of the action that latched the round, or null when nothing is pending. */
    val recordedDescription: String? get() = recorded

    /** Records the first unconfirmed action; later ones never happen, so they are not kept. */
    fun record(description: String) {
        if (recorded == null) {
            recorded = description
        }
    }

    /**
     * The refusal to report for [tool], or null when the round is clean and the action may proceed.
     *
     * The refusal is a plain failure, never [ToolOutcome.UNKNOWN]: nothing was dispatched.
     */
    fun refusal(tool: String): BuiltinToolResult? {
        val pending = recorded ?: return null
        return BuiltinToolResult.failure(
            code = "ACTION_STATE_INDETERMINATE",
            message = "$tool was not sent because a previous action's result is still unconfirmed " +
                "($pending). Nexus stopped all further side effects for this round instead of " +
                "retrying an operation that may already have taken effect.",
            hint = "Re-read the screen with screen_content to see what actually happened, then " +
                "retry in a new round. " + BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
        )
    }

    fun reset() {
        recorded = null
    }
}
