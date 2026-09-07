package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.mod.feat.AssistantCapturedInput
import com.niki914.nexus.agentic.mod.feat.AssistantInputSource
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import com.niki914.nexus.xposed.api.util.xlog
import com.niki914.nexus.xposed.runtime.util.call
import com.niki914.nexus.xposed.runtime.util.getTag
import de.robv.android.xposed.XC_MethodHook

/**
 * Captures the query from Template.Query before XiaoAi can execute a following
 * local Application.Operate instruction. Some local-command turns never call
 * OperationManager.setQueryInfo, so the regular input hook cannot see them.
 */
class CaptureInstructionInputHook(
    private val onInput: (AssistantCapturedInput) -> Unit,
) : SubHook() {

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.CaptureInstructionInput.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val instruction = param.args.firstOrNull() ?: return
        if (instruction.getTag<Boolean>(injectedFlagKey()) == true) return

        val fullName = instruction.call<String>("getFullName") ?: return
        if (fullName == TEMPLATE_QUERY) {
            val dialogId = resolveDialogId(instruction, param.thisObject)
            val payload = instruction.call<Any>("getPayload")
            val query = payload?.call<String>("getText")
            if (!dialogId.isNullOrBlank() && !query.isNullOrBlank()) {
                // The callback establishes the provisional InjectedLLM turn synchronously.
                // Native blocking must live in this same Xposed callback because callback
                // ordering between two hooks on this method is not stable across runtimes.
                onInput(
                    AssistantCapturedInput(
                        roomId = dialogId,
                        query = query,
                        source = AssistantInputSource.TEMPLATE_QUERY,
                    )
                )
            }
            return
        }

        val dialogId = resolveDialogId(instruction, param.thisObject) ?: return
        if (!ActiveTurnStore.ownsInjectedRoom(dialogId)) return
        if (fullName in XiaoaiConfigProvider.BlockNativeInstructionWhitelist.allowedInstructionFullNames) return

        param.result = true
        xlog("[$name] instruction_blocked fullName=$fullName")
    }

    private companion object {
        const val TEMPLATE_QUERY = "Template.Query"
    }
}
