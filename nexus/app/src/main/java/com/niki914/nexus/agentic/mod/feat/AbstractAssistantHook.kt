package com.niki914.nexus.agentic.mod.feat

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.ConversationTurnState
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.client.AssistantTextSource
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget
import com.niki914.nexus.agentic.takeover.TakeoverDecision
import com.niki914.nexus.agentic.takeover.TakeoverResolver
import com.niki914.nexus.xposed.api.xevent.XEvent
import com.niki914.nexus.xposed.api.xevent.XEventContext
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class AbstractAssistantHook(
    protected val scope: CoroutineScope,
    protected val textSource: AssistantTextSource,
) : Hook {
    protected open val floatResumeGraceWindowMs: Long = 1500L

    protected fun installFloatScreenDetachHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        detachTarget: HookTarget?,
        resumeTarget: HookTarget?
    ) {
        FloatScreenResetDetector(
            graceWindowMs = floatResumeGraceWindowMs,
            onReset = { scope.launch { onSessionReset() } }
        ).install(
            lpparam = lpparam,
            detachTarget = detachTarget,
            resumeTarget = resumeTarget
        )
    }

    final override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        onBeforeInstallHooks(lpparam)
        installSessionHooks(lpparam)
        installInputHooks(lpparam) { roomId, query ->
            // Establish the default Nexus takeover synchronously inside the host's input
            // before-hook. Local XiaoAi commands can emit an instruction before a
            // coroutine has time to read settings; without this provisional state the
            // native command races past the response blockers.
            val provisionalTurn = ConversationTurnState().nextTurn(
                roomId = roomId,
                query = query,
                mode = TurnMode.InjectedLLM,
            )
            val requestId = "${provisionalTurn.turnId}:$roomId"
            if (!textSource.reserve(requestId)) {
                return@installInputHooks
            }
            ActiveTurnStore.setCurrent(provisionalTurn)
            scope.launch {
                handleCapturedQuery(roomId, query, provisionalTurn, requestId)
            }
        }
        installResponseHooks(lpparam)
    }

    protected open fun onBeforeInstallHooks(lpparam: XC_LoadPackage.LoadPackageParam) = Unit

    private suspend fun handleCapturedQuery(
        roomId: String,
        query: String,
        provisionalTurn: ConversationTurnState,
        requestId: String,
    ) {
        val takeoverDecision = resolveTakeover(query)
        val turnMode = when (takeoverDecision.target) {
            RuntimeTakeoverTarget.NATIVE_ASSISTANT -> TurnMode.NativeTakeover
            RuntimeTakeoverTarget.NEXUS -> TurnMode.InjectedLLM
        }
        val nextTurnState = provisionalTurn.copy(mode = turnMode)
        if (ActiveTurnStore.getCurrent()?.turnId != provisionalTurn.turnId) {
            textSource.releaseReservation(requestId)
            return
        }
        ActiveTurnStore.setCurrent(nextTurnState)
        com.niki914.nexus.xposed.api.util.xlog(
            "[$name] takeover decided mode=${nextTurnState.mode.eventName()} turnId=${nextTurnState.turnId}"
        )
        val takeoverFields = mapOf(
            "mode" to nextTurnState.mode.eventName(),
            "takeoverTarget" to takeoverDecision.target.name,
            "matchedRuleId" to takeoverDecision.matchedRuleId.orEmpty(),
            "matchedRuleName" to takeoverDecision.matchedRuleName.orEmpty(),
        )
        val eventContext = XEventContext(
            roomId = roomId,
            turnId = nextTurnState.turnId,
            fields = takeoverFields
        )
        XEvent.setContext(eventContext)
        XEvent.withContext(eventContext) {
            onTurnStateChanged(nextTurnState)
            XEvent.inputCaptured(
                fields = mapOf("queryLength" to query.length)
            )
            XEvent.turnDecided(
                fields = takeoverFields + mapOf(
                    "queryLength" to query.length
                )
            )

            if (nextTurnState.mode == TurnMode.NativeTakeover) {
                textSource.releaseReservation(requestId)
                ActiveTurnStore.clear()
                return@withContext
            }

            dispatchQueryToLLM(
                turnId = nextTurnState.turnId,
                roomId = roomId,
                requestId = requestId,
                query = query
            )
        }
    }

    protected open suspend fun onTurnStateChanged(state: ConversationTurnState) = Unit

    protected open suspend fun resolveTakeover(query: String): TakeoverDecision {
        val rules = XRepo.takeoverRules.list()
        val defaultTarget = XRepo.takeoverRules.getDefaultTarget()
        return TakeoverResolver.resolve(query, rules, defaultTarget)
    }

    private fun TurnMode.eventName(): String = when (this) {
        TurnMode.InjectedLLM -> "InjectedLLM"
        TurnMode.NativeTakeover -> "NativeTakeover"
    }

    protected open suspend fun onSessionReset() {
        textSource.resetConversation()
        ActiveTurnStore.clear()
        XEvent.clearContext()
    }

    protected abstract fun installSessionHooks(lpparam: XC_LoadPackage.LoadPackageParam)

    protected abstract fun installResponseHooks(lpparam: XC_LoadPackage.LoadPackageParam)

    protected abstract fun installInputHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        onInput: (roomId: String, query: String) -> Unit
    )

    // 默认通过 textSource 提交查询并渲染；子类可覆盖以插入宿主特定的等待逻辑
    protected open suspend fun dispatchQueryToLLM(
        turnId: Long,
        roomId: String,
        requestId: String,
        query: String,
    ) {
        val eventContext = XEvent.snapshotContext()
        XEvent.withContext(eventContext) {
            try {
                textSource.submitReserved(requestId, query).collect { frame ->
                    renderStreamCard(turnId, roomId, frame.text, frame.isFirst, frame.isFinal)
                }
            } catch (e: Exception) {
                renderStreamCard(
                    turnId, roomId,
                    e.message ?: "Service unavailable",
                    true, true,
                )
            }
        }
    }

    /** 将流式文本帧渲染到宿主 UI。Breeno 全量刷新单卡片，XiaoAi 流式注入文本节点。 */
    protected abstract suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    )
}
