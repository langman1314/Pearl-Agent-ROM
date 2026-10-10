package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.xposed.api.util.xlog

/** Async voice adapter. Method return is not playback completion; no timer unlocks ownership. */
internal class XiaoaiFixedTextAdapter(private val host: Host) {
    interface Host {
        val lifecycleVerified: Boolean
        /** Throws only if no playback started. Never accepts dynamic text. */
        fun speakFixedText(onFinish: (Boolean) -> Unit)
        /** True only for acknowledged termination of this owned playback, not a queued request. */
        fun stopOwnedPlayback(): Boolean
    }
    data class Binding(val turnId: Long, val dialogId: String, val versionCode: Long)
    private class Owner(val binding: Binding, val settled: () -> Unit)
    private val lock = Any()
    private var owner: Owner? = null
    val backendReady: Boolean get() = host.lifecycleVerified
    fun isInFlight(): Boolean = synchronized(lock) { owner != null }

    fun broadcastFixedText(
        enabled: Boolean, binding: Binding, sessionCurrent: () -> Boolean,
        targetLive: () -> Boolean, settled: () -> Unit,
    ): XiaoaiFixedTextBroadcast.BroadcastOutcome = synchronized(lock) {
        val decision = XiaoaiFixedTextGate.decide(XiaoaiFixedTextGate.Input(
            enabled, binding.versionCode, XiaoaiFixedTextGate.FIXED_TEST_TEXT,
            sessionCurrent(), targetLive(), owner != null,
        ))
        if (decision is XiaoaiFixedTextGate.Decision.Deny) return@synchronized denied(decision.reason)
        if (!host.lifecycleVerified) return@synchronized denied(XiaoaiFixedTextGate.Reason.BACKEND_UNVERIFIED)
        val acquired = Owner(binding, settled)
        owner = acquired
        // Re-read turn/dialog and live target after claiming, immediately before host dispatch.
        if (!sessionCurrent()) {
            release(acquired)
            return@synchronized denied(XiaoaiFixedTextGate.Reason.SESSION_NOT_BOUND)
        }
        if (!targetLive()) {
            release(acquired)
            return@synchronized denied(XiaoaiFixedTextGate.Reason.TARGET_INVALIDATED)
        }
        try {
            host.speakFixedText { interrupted -> synchronized(lock) {
                if (owner === acquired) {
                    xlog("[XiaoaiFixedTextBroadcast] host_finish interrupted=$interrupted")
                    release(acquired)
                } else xlog("[XiaoaiFixedTextBroadcast] late_callback_ignored")
            } }
            xlog("[XiaoaiFixedTextBroadcast] fixed_text_dispatched note=dispatch_only_not_audible_proof")
            XiaoaiFixedTextBroadcast.BroadcastOutcome.Dispatched
        } catch (_: Throwable) {
            release(acquired)
            XiaoaiFixedTextBroadcast.BroadcastOutcome.DispatchFailed
        }
    }

    /** Snapshot opaque owner identity before evaluating a possibly waiting version service.
     * Never evaluate it for idle reset; a handoff during evaluation must not stop the successor,
     * even if a caller accidentally reuses the same turn/dialog binding.
     */
    fun cancelIfOwned(version: () -> Long): Boolean {
        val expectedOwner = synchronized(lock) { owner } ?: return false
        val versionCode = version()
        return synchronized(lock) {
            if (owner !== expectedOwner) false else cancel(versionCode, expectedOwner.binding)
        }
    }

    fun cancel(versionCode: Long, expected: Binding? = null): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        if (versionCode != XiaoaiFixedTextGate.VERIFIED_HOST_VERSION_CODE ||
            current.binding.versionCode != versionCode ||
            (expected != null && expected != current.binding)) return@synchronized false
        val confirmed = try { host.stopOwnedPlayback() } catch (_: Throwable) { false }
        // Unconfirmed stop retains ownership. Late finish cannot release a successor owner.
        if (confirmed) release(current)
        confirmed
    }
    private fun release(expected: Owner) {
        if (owner !== expected) return
        owner = null
        try { expected.settled() } catch (_: Throwable) {
            xlog("[XiaoaiFixedTextBroadcast] settlement_callback_failed")
        }
    }
    private fun denied(reason: XiaoaiFixedTextGate.Reason): XiaoaiFixedTextBroadcast.BroadcastOutcome {
        xlog("[XiaoaiFixedTextBroadcast] fixed_text_denied reason=$reason")
        return XiaoaiFixedTextBroadcast.BroadcastOutcome.Denied(reason)
    }
}

/**
 * Installed facade: audited dh0.u returns no session identity; its stop is not safely
 * acknowledged per playback. Config cannot override this hard lock. No guessed host bridge,
 * null callback dispatch, global stop or timer release. See hyperos-xiaoai.md evidence.
 */
object XiaoaiFixedTextBroadcast {
    const val HOST_BACKEND_ENABLEABLE = false
    internal val adapter = XiaoaiFixedTextAdapter(object : XiaoaiFixedTextAdapter.Host {
        override val lifecycleVerified = HOST_BACKEND_ENABLEABLE
        override fun speakFixedText(onFinish: (Boolean) -> Unit): Unit = error("Host lifecycle unverified")
        override fun stopOwnedPlayback() = false
    })
    sealed interface BroadcastOutcome {
        data object Dispatched : BroadcastOutcome
        data class Denied(val reason: XiaoaiFixedTextGate.Reason) : BroadcastOutcome
        data object DispatchFailed : BroadcastOutcome
    }
}
