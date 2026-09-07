package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.mod.feat.AssistantCapturedInput
import com.niki914.nexus.agentic.mod.feat.AssistantInputSource
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import de.robv.android.xposed.XC_MethodHook

/** 从宿主输入链路捕获用户 query 与 dialogId，含去重逻辑，回调至 handleCapturedQuery。 */
class CaptureInputHook(
    private val onInput: (AssistantCapturedInput) -> Unit
) : SubHook() {

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.CaptureInput.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val dialogIdArgIndex = XiaoaiConfigProvider.CaptureInput.dialogIdArgIndex
        val queryArgIndex = XiaoaiConfigProvider.CaptureInput.queryArgIndex

        val dialogId = param.args.getOrNull(dialogIdArgIndex) as? String
        val query = param.args.getOrNull(queryArgIndex) as? String

        if (dialogId.isNullOrBlank() || query.isNullOrBlank()) return

        onInput(
            AssistantCapturedInput(
                roomId = dialogId,
                query = query,
                source = AssistantInputSource.QUERY_INFO,
            )
        )
    }
}
