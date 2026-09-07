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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class XiaoaiChatHook(
    scope: CoroutineScope,
    textSource: AssistantTextSource,
) : AbstractAssistantHook(scope, textSource) {
    override val name: String = "XiaoaiChatHook"

    private var renderTextStreamCardHook: RenderTextStreamCardHook? = null

    @Volatile
    private var capturedResponseTarget: Any? = null
    @Volatile
    private var capturedResponseDialogId: String? = null
    private var targetReady = CompletableDeferred<Unit>()
    private val inputDeduplicator = XiaoaiInputDeduplicator()

    override suspend fun onSessionReset() {
        super.onSessionReset()
        targetReady.cancel()
        targetReady = CompletableDeferred()
        capturedResponseTarget = null
        capturedResponseDialogId = null
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
                capturedResponseTarget = target
                capturedResponseDialogId = dialogId
                targetReady.complete(Unit)
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
        if (capturedResponseDialogId != roomId || capturedResponseTarget == null) {
            targetReady.cancel()
            targetReady = CompletableDeferred()
        }

        val eventContext = XEvent.snapshotContext()
        XEvent.withContext(eventContext) {
            try {
                textSource.submitReserved(requestId, query).collect { frame ->
                    if (!awaitResponseTarget()) throw ResponseTargetTimeoutException()
                    renderStreamCard(turnId, roomId, frame.text, frame.isFirst, frame.isFinal)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ResponseTargetTimeoutException) {
                failOpenToNativeAssistant()
            } catch (e: Exception) {
                if (!awaitResponseTarget()) {
                    failOpenToNativeAssistant()
                    return@withContext
                }
                renderStreamCard(
                    turnId, roomId,
                    e.message ?: "Service unavailable",
                    true, true,
                )
            }
        }
    }

    private suspend fun awaitResponseTarget(): Boolean =
        withTimeoutOrNull(RESPONSE_TARGET_TIMEOUT_MS) {
            targetReady.await()
            true
        } == true

    private suspend fun failOpenToNativeAssistant() {
        ActiveTurnStore.clear()
        textSource.cancel()
    }

    private class ResponseTargetTimeoutException : IllegalStateException("XiaoAi response target timed out")

    override suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    ) {
        if (!ActiveTurnStore.isActiveInjection(turnId, roomId)) {
            return
        }

        renderTextStreamCardHook?.render(
            turnId = turnId,
            dialogId = roomId,
            target = capturedResponseTarget,
            chunk = chunk,
            isFirst = isFirst,
            isFinal = isFinal
        )
    }

    private companion object {
        const val RESPONSE_TARGET_TIMEOUT_MS = 8_000L
    }
}
