package com.niki914.nexus.agentic.chat.agentic.accessibility

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensitive-action gate at the production controller boundary.
 *
 * [NodeActionDispatchGate] is what `AccessibilityController.executeNodeAction` really calls, so these
 * tests exercise the same gate→dispatch boundary the phone path uses and count whether anything was
 * dispatched at all. What they cannot cover on the JVM is the Android framework node itself — the
 * label values are supplied here rather than read from an `AccessibilityNodeInfo`, which still needs
 * device evidence.
 */
class SensitiveActionDispatchTest {
    /** Counts every call that would have reached the device. */
    private class CountingDispatch {
        var dispatches: Int = 0
            private set

        fun run(): BuiltinToolResult {
            dispatches++
            return BuiltinToolResult.success("dispatched")
        }
    }

    private fun gate(
        action: NodeAction,
        text: CharSequence?,
        contentDescription: CharSequence? = null,
        latched: Boolean = false,
    ): Pair<BuiltinToolResult, Int> {
        val counter = CountingDispatch()
        val result = runBlocking {
            NodeActionDispatchGate.run(
                action = action,
                text = text,
                contentDescription = contentDescription,
                indeterminateRefusal = {
                    if (latched) {
                        BuiltinToolResult.failure(
                            code = "ACTION_STATE_INDETERMINATE",
                            message = "A previous action is unconfirmed.",
                        )
                    } else {
                        null
                    }
                },
                dispatch = { counter.run() },
            )
        }
        return result to counter.dispatches
    }

    // Row 8: a sensitive click or long-click must be refused with nothing dispatched.
    @Test
    fun sensitiveClickAndLongClickDispatchNothing() {
        val sensitiveLabels = listOf(
            "发送", "确认支付", "删除全部照片", "立即下单", "转账", "拨打电话", "允许",
            "Send", "Delete", "Pay now", "Place order", "Transfer", "Grant access",
        )

        for (label in sensitiveLabels) {
            for (action in listOf(NodeAction.CLICK, NodeAction.LONG_CLICK)) {
                val (result, dispatches) = gate(action, label)

                assertEquals(
                    "'$label' as ${action.name} must be refused, not dispatched",
                    "SENSITIVE_ACTION_CONFIRMATION_REQUIRED",
                    result.code,
                )
                assertEquals(
                    "'$label' as ${action.name} must reach the device zero times",
                    0,
                    dispatches,
                )
            }
        }
    }

    @Test
    fun sensitiveLabelInContentDescriptionAloneAlsoBlocksDispatch() {
        val (result, dispatches) = gate(
            action = NodeAction.CLICK,
            text = null,
            contentDescription = "确认支付",
        )

        assertEquals("SENSITIVE_ACTION_CONFIRMATION_REQUIRED", result.code)
        assertEquals(0, dispatches)
    }

    // The four commit phrases the accepted matrix names explicitly.
    @Test
    fun theFourCommitPhrasesDispatchNothing() {
        for (label in listOf("发送", "删除", "支付", "下单")) {
            val (result, dispatches) = gate(NodeAction.CLICK, label)
            assertEquals("'$label' commits and must be confirmed", "SENSITIVE_ACTION_CONFIRMATION_REQUIRED", result.code)
            assertEquals(0, dispatches)
        }
    }

    // Navigation counterexamples: ordinary controls must still work, or the agent cannot navigate.
    @Test
    fun navigationLabelsStillDispatch() {
        // `Delete history` is deliberately *not* here: a destructive verb stays a commit even with a
        // lookup tail (`删除记录` needs confirmation), while `支付方式`/`发送记录` are lookups.
        for (label in listOf("支付方式", "发送记录", "Payment methods", "Messages")) {
            val (result, dispatches) = gate(NodeAction.CLICK, label)

            assertEquals("'$label' is navigation and must be allowed", ToolOutcome.SUCCESS, result.outcome)
            assertEquals("'$label' must reach the dispatch step exactly once", 1, dispatches)
        }
    }

    @Test
    fun destructiveVerbKeepsACommitEvenBeforeALookupTail() {
        val (result, dispatches) = gate(NodeAction.CLICK, "删除记录")

        assertEquals("SENSITIVE_ACTION_CONFIRMATION_REQUIRED", result.code)
        assertEquals(0, dispatches)
    }

    @Test
    fun nonCommittingActionsAreNotGated() {
        for (action in listOf(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD, NodeAction.SET_TEXT)) {
            val (result, dispatches) = gate(action, "发送")

            assertEquals(
                "${action.name} cannot send, order or delete, so it must not be blocked",
                ToolOutcome.SUCCESS,
                result.outcome,
            )
            assertEquals(1, dispatches)
        }
    }

    // The barrier is downstream of the label gate, and still prevents dispatch.
    @Test
    fun indeterminateRoundRefusesDispatchEvenForAnOrdinaryLabel() {
        val (result, dispatches) = gate(NodeAction.CLICK, "设置", latched = true)

        assertEquals("ACTION_STATE_INDETERMINATE", result.code)
        assertEquals("nothing may be dispatched while a round is latched", 0, dispatches)
    }

    @Test
    fun sensitiveGateWinsOverAnUnlatchedRoundAndReportsAReason() {
        val (result, dispatches) = gate(NodeAction.CLICK, "确认支付", latched = false)

        assertEquals("SENSITIVE_ACTION_CONFIRMATION_REQUIRED", result.code)
        assertNotNull(result.message)
        assertTrue(result.message.isNotBlank())
        assertEquals(0, dispatches)
    }
}
