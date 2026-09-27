package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiInstructionGate.CaptureIntent
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiInstructionGate.Decision
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiInstructionGate.DialogIdSource
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiInstructionGate.Input
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiInstructionGate.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已安装闸门的纯源码逻辑覆盖。
 *
 * 这层测试证明的是“给定这些字段值时判定如何”，**不**证明 Xposed 真的挂上了宿主方法，也不证明
 * 宿主真的按预期传入了这些字段。后者只能靠真机日志（`nexus-x-log`）确认，两层覆盖不可互相代替。
 */
class XiaoaiInstructionGateTest {

    private val allowed = setOf("Nlp.UpdateStreamProperties", "SpeechRecognizer.RecognizeResult", "Template.Query")
    private val containerFullName = "Template.FrontendPage"

    private fun input(
        fullName: String?,
        injectedByNexus: Boolean = false,
        dialogId: String? = "dialog-1",
        dialogIdSource: DialogIdSource = DialogIdSource.INSTRUCTION,
        ownsInjectedTurn: Boolean = true,
        allowedInstructionFullNames: Set<String> = allowed,
    ) = Input(
        fullName = fullName,
        injectedByNexus = injectedByNexus,
        dialogId = dialogId,
        dialogIdSource = dialogIdSource,
        allowedInstructionFullNames = allowedInstructionFullNames,
        ownsInjectedTurn = ownsInjectedTurn,
    )

    @Test
    fun finalAsrIsAllowedAndCollected() {
        val gateInput = input(XiaoaiInstructionGate.FINAL_ASR_FULL_NAME)
        assertEquals(CaptureIntent.FINAL_ASR, gateInput.captureIntent)
        val decision = XiaoaiInstructionGate.decide(gateInput)
        assertTrue(decision is Decision.Allow)
        assertEquals(Reason.FINAL_ASR, decision.reason)
    }

    @Test
    fun finalAsrIsAllowedEvenInsideOwnedTurn() {
        // ASR 属于麦克风/VAD 生命周期，即使 Nexus 已接管该轮次也必须继续，否则识别链路会断。
        val decision = XiaoaiInstructionGate.decide(
            input(XiaoaiInstructionGate.FINAL_ASR_FULL_NAME, ownsInjectedTurn = true)
        )
        assertTrue(decision is Decision.Allow)
        assertEquals(Reason.FINAL_ASR, decision.reason)
    }

    @Test
    fun templateQueryIsAllowedAndCollected() {
        val gateInput = input(XiaoaiInstructionGate.TEMPLATE_QUERY_FULL_NAME)
        assertEquals(CaptureIntent.TEMPLATE_QUERY, gateInput.captureIntent)
        val decision = XiaoaiInstructionGate.decide(gateInput)
        assertTrue(decision is Decision.Allow)
        assertEquals(Reason.TEMPLATE_QUERY, decision.reason)
    }

    @Test
    fun injectedInstructionIsAllowedAndNotCollected() {
        val gateInput = input(containerFullName, injectedByNexus = true)
        assertEquals(CaptureIntent.NONE, gateInput.captureIntent)
        val decision = XiaoaiInstructionGate.decide(gateInput)
        assertTrue(decision is Decision.Allow)
        assertEquals(Reason.INJECTED_BY_NEXUS, decision.reason)
    }

    @Test
    fun missingInstructionDialogIdDoesNotBlock() {
        // 指令自身没带 ID：无法关联轮次，保持不干预，而不是误拦。
        val decision = XiaoaiInstructionGate.decide(
            input(
                fullName = "Application.Operate",
                dialogId = null,
                dialogIdSource = DialogIdSource.NONE,
                ownsInjectedTurn = false,
            )
        )
        assertTrue(decision is Decision.Allow)
        assertEquals(Reason.DIALOG_ID_UNRESOLVED, decision.reason)
    }

    @Test
    fun targetIdFallbackStillEvaluatesOwnership() {
        // 回退到 target 的 ID 仍可判定归属：归属时拦截，不归属时放行。
        val owned = XiaoaiInstructionGate.decide(
            input(
                fullName = "Application.Operate",
                dialogId = "dialog-from-target",
                dialogIdSource = DialogIdSource.TARGET_FALLBACK,
                ownsInjectedTurn = true,
            )
        )
        assertEquals(Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED), owned)

        val notOwned = XiaoaiInstructionGate.decide(
            input(
                fullName = "Application.Operate",
                dialogId = "dialog-from-target",
                dialogIdSource = DialogIdSource.TARGET_FALLBACK,
                ownsInjectedTurn = false,
            )
        )
        assertEquals(Decision.Allow(Reason.DIALOG_NOT_OWNED_BY_INJECTED_TURN), notOwned)
    }

    @Test
    fun unrelatedDialogIsNotAffected() {
        val decision = XiaoaiInstructionGate.decide(
            input(
                fullName = "Application.Operate",
                dialogId = "other-dialog",
                dialogIdSource = DialogIdSource.INSTRUCTION,
                ownsInjectedTurn = false,
            )
        )
        assertTrue(decision is Decision.Allow)
        assertEquals(Reason.DIALOG_NOT_OWNED_BY_INJECTED_TURN, decision.reason)
    }

    @Test
    fun ownedApplicationOperateIsBlocked() {
        // Application.Operate 不在白名单内；在接管轮次里必须拦，否则宿主会执行原生操作覆盖回答。
        val decision = XiaoaiInstructionGate.decide(input("Application.Operate"))
        assertEquals(Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED), decision)
        assertFalse("Application.Operate" in allowed)
    }

    @Test
    fun whitelistedInstructionInsideOwnedTurnIsAllowed() {
        val decision = XiaoaiInstructionGate.decide(input("Nlp.UpdateStreamProperties"))
        assertEquals(Decision.Allow(Reason.ALLOWED_INSTRUCTION), decision)
    }

    @Test
    fun containerShapeDoesNotGrantAllowPath() {
        // 正常容器（Template.FrontendPage，URL 含 stream.bundle）在内层指令存在性未知时仍然拦截：
        // 子串命中不足以证明内层没有副作用，因此不移植旧的放行分支。
        val decision = XiaoaiInstructionGate.decide(input(containerFullName))
        assertEquals(Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED), decision)
    }

    @Test
    fun containerWithSmuggledInnerInstructionsIsBlocked() {
        val shape = XiaoaiStreamContainerInstruction.Shape(
            loadType = "URL",
            paramType = "json",
            loadUrlPresent = true,
            loadUrlMatchesMarker = true,
            loadHtmlPresent = false,
            cardTypePresent = false,
            innerInstructionCount = 2,
        )
        assertTrue(shape.innerInstructionsPossiblyPresent)
        val decision = XiaoaiInstructionGate.decide(input(containerFullName))
        assertEquals(Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED), decision)
    }

    @Test
    fun disguisedUrlContainerIsBlocked() {
        // URL 里出现 stream.bundle 但形状不匹配（例如 html 载入），同样拦截。
        val shape = XiaoaiStreamContainerInstruction.Shape(
            loadType = "HTML",
            paramType = "html",
            loadUrlPresent = true,
            loadUrlMatchesMarker = true,
            loadHtmlPresent = true,
            cardTypePresent = false,
            innerInstructionCount = 0,
        )
        assertFalse(shape.innerInstructionsPossiblyPresent)
        val decision = XiaoaiInstructionGate.decide(input(containerFullName))
        assertEquals(Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED), decision)
    }

    @Test
    fun unknownFullNameInsideOwnedTurnIsBlocked() {
        // fullName 不是采集分支、不在白名单：按默认拦截处理。
        val decision = XiaoaiInstructionGate.decide(input("Some.Unknown.Card"))
        assertEquals(Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED), decision)
    }

    @Test
    fun unreadableFullNameStaysNonIntervening() {
        val decision = XiaoaiInstructionGate.decide(input(fullName = null, ownsInjectedTurn = true))
        assertTrue(decision is Decision.Allow)
        assertEquals(Reason.DIALOG_ID_UNRESOLVED, decision.reason)
    }

    @Test
    fun ownedTurnWithoutAnyWhitelistStillBlocksNativeInstruction() {
        // 配置缺白名单时不退化为“全放行”。
        val decision = XiaoaiInstructionGate.decide(
            input(fullName = "Application.Operate", allowedInstructionFullNames = emptySet())
        )
        assertEquals(Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED), decision)
    }
}
