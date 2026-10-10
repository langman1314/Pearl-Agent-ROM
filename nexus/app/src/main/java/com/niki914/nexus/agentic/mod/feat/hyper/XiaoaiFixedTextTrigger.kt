package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.xposed.api.util.xlog

/** Called only by native captured input and response-target hooks, never by rendered Agent text. */
internal class XiaoaiFixedTextTrigger(
    private val adapter: XiaoaiFixedTextAdapter,
    private val enabled: () -> Boolean,
    private val version: () -> Long,
    private val begin: (String) -> XiaoaiFixedTextAdapter.Binding,
    private val current: (XiaoaiFixedTextAdapter.Binding) -> Boolean,
    private val targetLive: (String) -> Boolean,
    private val end: (XiaoaiFixedTextAdapter.Binding) -> Unit,
) {
    companion object { const val QUERY = "Nexus 播报自检" }
    private val lock = Any()
    private var pending: XiaoaiFixedTextAdapter.Binding? = null

    /** True skips the normal reserve/model/tool pipeline; non-matching or disabled inputs pass through. */
    fun onInput(dialogId: String, query: String): Boolean = synchronized(lock) {
        if (query.trim() != QUERY || !enabled()) return@synchronized false
        if (!adapter.backendReady) {
            xlog("[XiaoaiFixedTextTrigger] denied reason=BACKEND_UNVERIFIED")
            return@synchronized true
        }
        if (version() != XiaoaiFixedTextGate.VERIFIED_HOST_VERSION_CODE || dialogId.isBlank()) {
            xlog("[XiaoaiFixedTextTrigger] denied reason=VERSION_OR_DIALOG")
            return@synchronized true
        }
        if (pending != null || adapter.isInFlight()) return@synchronized true
        pending = begin(dialogId)
        // May already have a still-live target. Otherwise the installed capture callback dispatches once.
        if (targetLive(dialogId)) onTargetCaptured(dialogId)
        true
    }

    fun onTargetCaptured(dialogId: String) = synchronized(lock) {
        val binding = pending ?: return@synchronized
        if (binding.dialogId != dialogId) return@synchronized
        pending = null // consume before dispatch, including synchronous host callback / re-entry
        val outcome = adapter.broadcastFixedText(enabled(), binding,
            sessionCurrent = { current(binding) }, targetLive = { targetLive(dialogId) },
            settled = { end(binding) })
        if (outcome !is XiaoaiFixedTextBroadcast.BroadcastOutcome.Dispatched) end(binding)
    }

    fun onSessionEnd() = synchronized(lock) {
        pending?.let(end)
        pending = null
        adapter.cancelIfOwned(version) // Idle reset never reads version; handoff cannot stop a successor.
    }
}
