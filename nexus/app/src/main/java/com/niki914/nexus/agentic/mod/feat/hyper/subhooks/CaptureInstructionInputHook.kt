package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import com.niki914.nexus.xposed.runtime.util.call
import de.robv.android.xposed.XC_MethodHook

/**
 * Captures the query from Template.Query before XiaoAi can execute a following
 * local Application.Operate instruction. Some local-command turns never call
 * OperationManager.setQueryInfo, so the regular input hook cannot see them.
 */
class CaptureInstructionInputHook(
    private val onInput: (dialogId: String, query: String) -> Unit,
) : SubHook() {

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.CaptureResponseTarget.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val instruction = param.args.firstOrNull() ?: return
        if (instruction.call<String>("getFullName") != TEMPLATE_QUERY) return

        val dialogId = resolveDialogId(instruction, param.thisObject)
        val payload = instruction.call<Any>("getPayload")
        val query = payload?.call<String>("getText")
        if (dialogId.isNullOrBlank() || query.isNullOrBlank()) return

        onInput(dialogId, query)
    }

    private companion object {
        const val TEMPLATE_QUERY = "Template.Query"
    }
}
