package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.mod.feat.AssistantCapturedInput
import com.niki914.nexus.agentic.mod.feat.AssistantInputSource
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.FinalAsrPayloadDecoder
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiInstructionGate
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiStreamContainerInstruction
import com.niki914.nexus.xposed.api.util.xlog
import com.niki914.nexus.xposed.runtime.util.call
import com.niki914.nexus.xposed.runtime.util.getTag
import de.robv.android.xposed.XC_MethodHook

/**
 * Captures the query from Template.Query before XiaoAi can execute a following
 * local Application.Operate instruction. Some local-command turns never call
 * OperationManager.setQueryInfo, so the regular input hook cannot see them.
 *
 * 本 Hook 同时是已安装的原生指令闸门：接管轮次内，除白名单外的原生指令一律拦截，避免宿主用原生
 * 回复覆盖 Nexus 的回答。判定逻辑放在 [XiaoaiInstructionGate]，本类只做反射读取与动作执行。
 */
class CaptureInstructionInputHook(
    private val onInput: (AssistantCapturedInput) -> Unit,
) : SubHook() {

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.CaptureInstructionInput.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val instruction = param.args.firstOrNull() ?: return

        val config = XiaoaiConfigProvider.BlockNativeInstructionWhitelist
        val resolvedDialogId = resolveDialogIdWithSource(
            instruction = instruction,
            target = param.thisObject,
            instructionDialogIdGetter = config.instructionDialogIdGetter,
            optionalHasValueMethod = config.optionalHasValueMethod,
            optionalValueGetter = config.optionalValueGetter,
            targetDialogIdGetter = config.targetDialogIdGetter,
        )
        val ownsInjectedTurn = resolvedDialogId.value?.let { ActiveTurnStore.ownsInjectedRoom(it) } == true

        val gateInput = XiaoaiInstructionGate.Input(
            fullName = instruction.call<String>("getFullName"),
            injectedByNexus = instruction.getTag<Boolean>(injectedFlagKey()) == true,
            dialogId = resolvedDialogId.value,
            dialogIdSource = resolvedDialogId.source,
            allowedInstructionFullNames = config.allowedInstructionFullNames,
            ownsInjectedTurn = ownsInjectedTurn,
        )
        val decision = XiaoaiInstructionGate.decide(gateInput)

        when (decision.reason) {
            // 采集分支：命中即采集并放行，绝不拦截。
            XiaoaiInstructionGate.Reason.FINAL_ASR -> {
                captureFinalAsr(instruction, param.thisObject)
                return
            }

            XiaoaiInstructionGate.Reason.TEMPLATE_QUERY -> {
                captureTemplateQuery(instruction, resolvedDialogId.value)
                return
            }

            else -> Unit
        }

        logDecision(decision, gateInput, resolvedDialogId, ownsInjectedTurn)

        if (decision is XiaoaiInstructionGate.Decision.Block) {
            param.result = true
            logBlockedContainerShape(instruction, gateInput.fullName)
        }
    }

    private fun captureTemplateQuery(instruction: Any, dialogId: String?) {
        if (dialogId.isNullOrBlank()) return
        val query = instruction.call<Any>("getPayload")?.call<String>("getText")
        if (query.isNullOrBlank()) return
        // 回调会同步建立临时 InjectedLLM 轮次；原生拦截必须留在同一个 Xposed 回调内，
        // 因为同一方法上两个 Hook 的回调顺序在不同运行时并不稳定。
        onInput(
            AssistantCapturedInput(
                roomId = dialogId,
                query = query,
                source = AssistantInputSource.TEMPLATE_QUERY,
            )
        )
    }

    private fun captureFinalAsr(instruction: Any, target: Any?) {
        val dialogId = resolveDialogId(instruction, target) ?: return
        val payload = instruction.call<Any>("getPayload") ?: return
        val query = FinalAsrPayloadDecoder.decode(payload) ?: return
        onInput(
            AssistantCapturedInput(
                roomId = dialogId,
                query = query,
                source = AssistantInputSource.FINAL_ASR,
            )
        )
    }

    /**
     * 决策日志只写脱敏字段：指令 fullName（schema 常量，非用户数据）、dialog 是否归属当前接管轮次、
     * 判定原因。dialog id 本身以 hash 呈现，不落原文。
     */
    private fun logDecision(
        decision: XiaoaiInstructionGate.Decision,
        gateInput: XiaoaiInstructionGate.Input,
        resolved: ResolvedDialogId,
        ownsInjectedTurn: Boolean,
    ) {
        xlog(
            "[$name] gate reason=${decision.reason} fullName=${gateInput.fullName.orEmpty()} " +
                "dialogIdSource=${resolved.source} ownsTurn=$ownsInjectedTurn " +
                "dialogHash=${resolved.value?.hashCode() ?: 0}"
        )
    }

    /**
     * 被拦截的容器额外记录脱敏 shape，便于在真机日志里区分“正常容器”与“夹带内层指令/伪装 URL 的
     * 容器”。只记录布尔量、schema 枚举名和内层指令数量；不记录 URL、HTML 或卡片正文。
     */
    private fun logBlockedContainerShape(instruction: Any, fullName: String?) {
        val config = XiaoaiConfigProvider.BlockNativeInstructionWhitelist
        if (fullName != config.streamContainerInstructionFullName) return
        val shape = XiaoaiStreamContainerInstruction.inspectShape(
            instruction,
            config.streamContainerLoadUrlMarker,
        )
        xlog(
            "[$name] container_shape loadType=${shape?.loadType.orEmpty()} " +
                "paramType=${shape?.paramType.orEmpty()} urlPresent=${shape?.loadUrlPresent} " +
                "urlMarker=${shape?.loadUrlMatchesMarker} htmlPresent=${shape?.loadHtmlPresent} " +
                "cardTypePresent=${shape?.cardTypePresent} " +
                "innerInstructionCount=${shape?.innerInstructionCount ?: XiaoaiStreamContainerInstruction.INNER_INSTRUCTION_COUNT_UNKNOWN}"
        )
    }
}
