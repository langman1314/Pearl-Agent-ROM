package com.niki914.nexus.agentic.chat.agentic.accessibility

import org.junit.Assert.assertFalse
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
}
