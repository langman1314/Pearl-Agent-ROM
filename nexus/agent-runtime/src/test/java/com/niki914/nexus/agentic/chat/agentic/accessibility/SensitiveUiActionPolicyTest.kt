package com.niki914.nexus.agentic.chat.agentic.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveUiActionPolicyTest {
    @Test
    fun blocksExplicitFinalEffectControls() {
        listOf(
            "Send", "Delete", "Pay", "Place order", "Transfer", "Call", "Allow",
            "发送", "删除", "支付", "确认订单", "转账", "拨打", "授权",
        ).forEach { label ->
            assertTrue(label, SensitiveUiActionPolicy.requiresManualConfirmation(label))
        }
    }

    @Test
    fun allowsNavigationAndDraftPreparation() {
        listOf(
            "Messages", "Cart", "Order history", "Payment methods", "Recipient",
            "消息", "购物车", "订单记录", "支付方式", "收款人", "下一步",
        ).forEach { label ->
            assertFalse(label, SensitiveUiActionPolicy.requiresManualConfirmation(label))
        }
    }

    @Test
    fun checksContentDescriptionAndRejectsOnlyExplicitLabels() {
        assertTrue(SensitiveUiActionPolicy.requiresManualConfirmation(null, "Send message"))
        assertFalse(SensitiveUiActionPolicy.requiresManualConfirmation(null, "Message details"))
    }

    @Test
    fun blocksChineseCommitPhrasesWithAModifierOrVerbForm() {
        listOf(
            "确认支付", "立即发送", "确认删除", "拨打电话",
            "确定付款", "马上提交订单", "一键下单", "确认转账", "继续支付", "确定购买",
        ).forEach { label ->
            assertTrue(label, SensitiveUiActionPolicy.requiresManualConfirmation(label))
        }
    }

    @Test
    fun allowsChineseLabelsThatOnlyContainCommitCharacters() {
        listOf(
            "支付方式", "发送记录", "下一步",
            "付款信息", "转账记录", "通话记录", "发送时间", "充值记录", "授权管理",
        ).forEach { label ->
            assertFalse(label, SensitiveUiActionPolicy.requiresManualConfirmation(label))
        }
    }

    @Test
    fun stillBlocksDestructiveVerbsFollowedByANavigationTail() {
        listOf("删除记录", "删除历史", "清空列表", "移除记录").forEach { label ->
            assertTrue(label, SensitiveUiActionPolicy.requiresManualConfirmation(label))
        }
    }

    @Test
    fun keepsEnglishWordBoundaries() {
        // Substrings must not trip the English keywords.
        listOf(
            "Payment methods", "Messages", "Postal code", "Recipient", "Allergy",
            "Removal instructions", "Sending log", "Deleted items folder", "Payload details",
        ).forEach { label ->
            assertFalse(label, SensitiveUiActionPolicy.requiresManualConfirmation(label))
        }

        // A standalone keyword still blocks, wherever it appears in the label.
        listOf(
            "Send", "send", "SEND", "Send message", "Delete", "Pay now", "Call back",
            "Delete draft history",
        ).forEach { label ->
            assertTrue(label, SensitiveUiActionPolicy.requiresManualConfirmation(label))
        }
    }

    @Test
    fun handlesWhitespaceCaseAndPunctuation() {
        assertTrue(SensitiveUiActionPolicy.requiresManualConfirmation("  确认支付  "))
        assertTrue(SensitiveUiActionPolicy.requiresManualConfirmation("确认支付 »"))
        assertTrue(SensitiveUiActionPolicy.requiresManualConfirmation("Send message:"))
        assertTrue(SensitiveUiActionPolicy.requiresManualConfirmation("PAY NOW"))
        assertFalse(SensitiveUiActionPolicy.requiresManualConfirmation(""))
        assertFalse(SensitiveUiActionPolicy.requiresManualConfirmation("   "))
        assertFalse(SensitiveUiActionPolicy.requiresManualConfirmation(null))
    }

    @Test
    fun checksEverySuppliedLabel() {
        assertTrue(
            SensitiveUiActionPolicy.requiresManualConfirmation(
                "Draft",
                "确认删除",
            )
        )
        assertTrue(
            SensitiveUiActionPolicy.requiresManualConfirmation(
                "Draft",
                null,
                "Delete",
            )
        )
        assertFalse(
            SensitiveUiActionPolicy.requiresManualConfirmation("Draft", null, "Cancel")
        )
    }

    @Test
    fun clickGateRefusesSensitiveControlsBeforeAnythingIsDispatched() {
        listOf("确认支付", "立即发送", "确认删除", "Delete", "Pay now").forEach { label ->
            val refusal = SensitiveUiActionPolicy.clickGate(NodeAction.CLICK, label, null)
            assertNotNull("$label should be refused", refusal)
            assertEquals("SENSITIVE_ACTION_CONFIRMATION_REQUIRED", refusal!!.code)
            assertFalse(refusal.ok)
        }
    }

    @Test
    fun clickGateAlsoReadsContentDescription() {
        val refusal = SensitiveUiActionPolicy.clickGate(
            NodeAction.CLICK,
            text = null,
            contentDescription = "Send message",
        )

        assertNotNull(refusal)
        assertEquals("SENSITIVE_ACTION_CONFIRMATION_REQUIRED", refusal!!.code)
    }

    @Test
    fun clickGateRefusesLongClickOnSensitiveControls() {
        assertNotNull(SensitiveUiActionPolicy.clickGate(NodeAction.LONG_CLICK, "删除", null))
        assertNull(SensitiveUiActionPolicy.clickGate(NodeAction.LONG_CLICK, "Draft", null))
    }

    @Test
    fun clickGateLetsOrdinaryNavigationThrough() {
        listOf("下一步", "支付方式", "发送记录", "Messages", "Cart", "Cancel").forEach { label ->
            assertNull(
                "$label should be allowed",
                SensitiveUiActionPolicy.clickGate(NodeAction.CLICK, label, null),
            )
        }
    }

    @Test
    fun clickGateDoesNotBlockNonCommittingActions() {
        // Scrolling and typing cannot send, order, pay, or delete, so gating them would only
        // break ordinary navigation and text entry.
        listOf(
            NodeAction.SET_TEXT,
            NodeAction.SCROLL_FORWARD,
            NodeAction.SCROLL_BACKWARD,
        ).forEach { action ->
            assertNull(
                "$action should not be gated",
                SensitiveUiActionPolicy.clickGate(action, "确认支付", "Send message"),
            )
        }
    }
}
