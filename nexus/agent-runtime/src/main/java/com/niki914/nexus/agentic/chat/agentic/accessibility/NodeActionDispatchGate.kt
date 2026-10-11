package com.niki914.nexus.agentic.chat.agentic.accessibility

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult

/**
 * The gate a node action passes through before anything is dispatched.
 *
 * This is a real production boundary, not a test fixture: [AccessibilityController.executeNodeAction]
 * calls it with the labels of the node it is about to act on and its own dispatch step. Extracting it
 * means the "was anything dispatched?" question can be answered by counting fakes on the JVM, instead
 * of needing an Android framework node — the framework node itself is still only verifiable on a
 * device.
 *
 * Order matters and is the point:
 *
 * 1. the sensitive-label gate runs *first*, so a final-effect control is refused before the
 *    indeterminate check or any dispatch step is reached;
 * 2. an unconfirmed earlier action refuses everything else this round;
 * 3. only then may [dispatch] run at all.
 */
internal object NodeActionDispatchGate {
    suspend fun run(
        action: NodeAction,
        text: CharSequence?,
        contentDescription: CharSequence?,
        indeterminateRefusal: () -> BuiltinToolResult?,
        dispatch: suspend () -> BuiltinToolResult,
    ): BuiltinToolResult {
        // Final-effect controls are never tapped by the agent; the user confirms them manually.
        SensitiveUiActionPolicy.clickGate(action, text, contentDescription)?.let { return it }

        // Nothing may be dispatched while an earlier action this round is still unconfirmed.
        indeterminateRefusal()?.let { return it }

        return dispatch()
    }
}
