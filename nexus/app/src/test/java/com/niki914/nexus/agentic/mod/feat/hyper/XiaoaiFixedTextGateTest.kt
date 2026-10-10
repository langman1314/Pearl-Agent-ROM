package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiFixedTextGate.Decision
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiFixedTextGate.Input
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiFixedTextGate.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定文本播报闸门的纯源码逻辑覆盖。
 *
 * 这些测试证明的是“这些前置条件组合会被拒绝/放行”，**不**证明真机发出了声音，也不证明宿主方法
 * 真的能按此签名反射调用成功。发声与否只能由真机听感确认。
 */
class XiaoaiFixedTextGateTest {

    private fun input(
        enabled: Boolean = true,
        hostVersionCode: Long = XiaoaiFixedTextGate.VERIFIED_HOST_VERSION_CODE,
        text: String = XiaoaiFixedTextGate.FIXED_TEST_TEXT,
        sessionBound: Boolean = true,
        targetValid: Boolean = true,
        inFlight: Boolean = false,
    ) = Input(
        enabled = enabled,
        hostVersionCode = hostVersionCode,
        text = text,
        sessionBound = sessionBound,
        targetValid = targetValid,
        inFlight = inFlight,
    )

    @Test
    fun defaultOffIsDenied() {
        // 默认关闭：即使其它条件都满足也不得发声。
        val decision = XiaoaiFixedTextGate.decide(input(enabled = false))
        assertEquals(Decision.Deny(Reason.DISABLED), decision)
    }

    @Test
    fun allowedOnlyWhenEveryPreconditionHolds() {
        val decision = XiaoaiFixedTextGate.decide(input())
        assertEquals(Decision.Allow(Reason.ALLOWED), decision)
        assertTrue(decision is Decision.Allow)
    }

    @Test
    fun versionMismatchFailsImmediately() {
        val decision = XiaoaiFixedTextGate.decide(input(hostVersionCode = 507013003L))
        assertEquals(Decision.Deny(Reason.VERSION_MISMATCH), decision)
    }

    @Test
    fun dynamicTextIsRejected() {
        val decision = XiaoaiFixedTextGate.decide(input(text = "Agent 动态回答"))
        assertEquals(Decision.Deny(Reason.TEXT_NOT_FIXED_CONSTANT), decision)
    }

    @Test
    fun replayIsRejectedWhileInFlight() {
        // 禁止自动重放：同一时刻只允许一次播报在途。
        val decision = XiaoaiFixedTextGate.decide(input(inFlight = true))
        assertEquals(Decision.Deny(Reason.ALREADY_IN_FLIGHT), decision)
    }

    @Test
    fun unboundSessionIsRejected() {
        val decision = XiaoaiFixedTextGate.decide(input(sessionBound = false))
        assertEquals(Decision.Deny(Reason.SESSION_NOT_BOUND), decision)
    }

    @Test
    fun invalidatedTargetIsRejected() {
        val decision = XiaoaiFixedTextGate.decide(input(targetValid = false))
        assertEquals(Decision.Deny(Reason.TARGET_INVALIDATED), decision)
    }

    @Test
    fun textCheckHappensBeforeSessionChecks() {
        // 即使会话状态异常，非固定文本也不得经此入口流出。
        val decision = XiaoaiFixedTextGate.decide(
            input(text = "Agent 动态回答", sessionBound = false, targetValid = false)
        )
        assertEquals(Decision.Deny(Reason.TEXT_NOT_FIXED_CONSTANT), decision)
    }

    @Test
    fun verifiedVersionMatchesBundledConfig() {
        assertEquals(507012002L, XiaoaiFixedTextGate.VERIFIED_HOST_VERSION_CODE)
    }
}
