package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.mod.feat.AbstractAssistantHook
import com.niki914.nexus.agentic.mod.feat.AssistantCapturedInput
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.CaptureInputHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.CaptureInstructionInputHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.CaptureResponseTargetHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.RenderTextStreamCardHook
import com.niki914.nexus.agentic.runtime.client.AssistantTextSource
import com.niki914.nexus.xposed.api.xevent.XEvent
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

class XiaoaiChatHook(
    scope: CoroutineScope,
    textSource: AssistantTextSource,
) : AbstractAssistantHook(scope, textSource) {
    override val name: String = "XiaoaiChatHook"

    private var renderTextStreamCardHook: RenderTextStreamCardHook? = null

    private val responseTargets = XiaoaiResponseTargetRegistry()
    private val inputDeduplicator = XiaoaiInputDeduplicator()

    override suspend fun onSessionReset() {
        super.onSessionReset()
        responseTargets.reset()
        inputDeduplicator.reset()
        renderTextStreamCardHook?.reset()
    }

    override fun installSessionHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        installFloatScreenDetachHooks(
            lpparam = lpparam,
            detachTarget = XiaoaiConfigProvider.FloatScreenDetach.detachTarget,
            resumeTarget = XiaoaiConfigProvider.FloatScreenDetach.resumeTarget
        )
    }

    override fun installResponseHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        CaptureResponseTargetHook(
            onCaptured = { target, dialogId ->
                com.niki914.nexus.xposed.api.util.xlog(
                    "[$name] response target captured dialogHash=${dialogId.hashCode()} " +
                        "target=${target.javaClass.name}"
                )
                responseTargets.capture(dialogId, target)
            }
        ).onHook(lpparam)

        renderTextStreamCardHook = RenderTextStreamCardHook()
            .also { it.onHook(lpparam) }
    }

    override fun installInputHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        onInput: (AssistantCapturedInput) -> Unit
    ) {
        val deduplicatedInput: (AssistantCapturedInput) -> Unit = { input ->
            if (inputDeduplicator.shouldDeliver(input)) onInput(input)
        }
        CaptureInstructionInputHook(onInput = deduplicatedInput).onHook(lpparam)
        CaptureInputHook(onInput = deduplicatedInput).onHook(lpparam)
    }

    // 渲染前等待宿主 UI 卡片；超时必须 fail-open，避免 Hook 失配时挂死或吞掉原生回答。
    override suspend fun dispatchQueryToLLM(
        turnId: Long,
        roomId: String,
        requestId: String,
        query: String,
    ) {
        val eventContext = XEvent.snapshotContext()
        XEvent.withContext(eventContext) {
            try {
                textSource.submitReserved(requestId, query).collect { frame ->
                    val target = responseTargets.await(roomId, RESPONSE_TARGET_TIMEOUT_MS)
                        ?: throw ResponseTargetTimeoutException()
                    renderToTarget(
                        turnId, roomId, target,
                        frame.text, frame.isFirst, frame.isFinal,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ResponseTargetTimeoutException) {
                com.niki914.nexus.xposed.api.util.xlog(
                    "[$name] response target timeout turnId=$turnId dialogHash=${roomId.hashCode()}"
                )
                failOpenToNativeAssistant(turnId, roomId)
            } catch (e: Exception) {
                val target = responseTargets.await(roomId, RESPONSE_TARGET_TIMEOUT_MS)
                if (target == null) {
                    failOpenToNativeAssistant(turnId, roomId)
                    return@withContext
                }
                renderToTarget(
                    turnId, roomId, target,
                    e.message ?: "Service unavailable",
                    true, true,
                )
            }
        }
    }

    private suspend fun failOpenToNativeAssistant(turnId: Long, roomId: String) {
        responseTargets.clear(roomId)
        if (ActiveTurnStore.clearIfOwner(turnId, roomId)) {
            textSource.cancel()
        }
    }

    private class ResponseTargetTimeoutException : IllegalStateException("XiaoAi response target timed out")

    override suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean,
    ) {
        val target = responseTargets.await(roomId, RESPONSE_TARGET_TIMEOUT_MS) ?: return
        renderToTarget(turnId, roomId, target, chunk, isFirst, isFinal)
    }

    private suspend fun renderToTarget(
        turnId: Long,
        roomId: String,
        target: Any,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    ) {
        if (!ActiveTurnStore.isActiveInjection(turnId, roomId)) {
            return
        }

        com.niki914.nexus.xposed.api.util.xlog(
            "[$name] render frame turnId=$turnId dialogHash=${roomId.hashCode()} " +
                "textLength=${chunk.length} first=$isFirst final=$isFinal"
        )
        renderTextStreamCardHook?.render(
            turnId = turnId,
            dialogId = roomId,
            target = target,
            chunk = chunk,
            isFirst = isFirst,
            isFinal = isFinal
        )
        if (isFinal) {
            responseTargets.clear(roomId)
            ActiveTurnStore.clearIfOwner(turnId, roomId)
        }
    }

    private companion object {
        const val RESPONSE_TARGET_TIMEOUT_MS = 8_000L
    }
}
