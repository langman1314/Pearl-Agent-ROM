package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiInstructionGate
import com.niki914.nexus.xposed.runtime.util.call

fun injectedFlagKey(): String = XiaoaiConfigProvider.RenderTextStreamCard.injectedFlagKey

/** dialog id 及其来源。来源决定“这条指令与当前轮次相关”的证据强度，因此单独回报。 */
data class ResolvedDialogId(
    val value: String?,
    val source: XiaoaiInstructionGate.DialogIdSource,
)

fun resolveDialogId(instruction: Any, target: Any?): String? {
    return resolveDialogId(
        instruction = instruction,
        target = target,
        instructionDialogIdGetter = XiaoaiConfigProvider.BlockNativeTextStream.instructionDialogIdGetter,
        optionalHasValueMethod = XiaoaiConfigProvider.BlockNativeTextStream.optionalHasValueMethod,
        optionalValueGetter = XiaoaiConfigProvider.BlockNativeTextStream.optionalValueGetter,
        targetDialogIdGetter = XiaoaiConfigProvider.BlockNativeTextStream.targetDialogIdGetter,
    )
}

fun resolveDialogId(
    instruction: Any,
    target: Any?,
    instructionDialogIdGetter: String,
    optionalHasValueMethod: String,
    optionalValueGetter: String,
    targetDialogIdGetter: String,
): String? = resolveDialogIdWithSource(
    instruction = instruction,
    target = target,
    instructionDialogIdGetter = instructionDialogIdGetter,
    optionalHasValueMethod = optionalHasValueMethod,
    optionalValueGetter = optionalValueGetter,
    targetDialogIdGetter = targetDialogIdGetter,
).value

/**
 * 与 [resolveDialogId] 同逻辑，但额外报告 ID 是来自指令自身还是回退自 target。
 * 指令自身没有 ID 时回退 target 仍可让生命周期继续，但这条指令并未自证属于该 dialog。
 */
fun resolveDialogIdWithSource(
    instruction: Any,
    target: Any?,
    instructionDialogIdGetter: String,
    optionalHasValueMethod: String,
    optionalValueGetter: String,
    targetDialogIdGetter: String,
): ResolvedDialogId {
    val dialogIdOptional = instruction.call<Any>(instructionDialogIdGetter)
    val dialogId = dialogIdOptional
        ?.takeIf { it.call<Boolean>(optionalHasValueMethod) == true }
        ?.call<String>(optionalValueGetter)
    if (!dialogId.isNullOrBlank()) {
        return ResolvedDialogId(dialogId, XiaoaiInstructionGate.DialogIdSource.INSTRUCTION)
    }
    val fallback = target?.call<String>(targetDialogIdGetter)
    return if (!fallback.isNullOrBlank()) {
        ResolvedDialogId(fallback, XiaoaiInstructionGate.DialogIdSource.TARGET_FALLBACK)
    } else {
        ResolvedDialogId(null, XiaoaiInstructionGate.DialogIdSource.NONE)
    }
}
