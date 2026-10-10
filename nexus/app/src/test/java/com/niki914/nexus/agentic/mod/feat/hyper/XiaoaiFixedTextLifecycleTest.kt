package com.niki914.nexus.agentic.mod.feat.hyper

import org.junit.Assert.*
import org.junit.Test

class XiaoaiFixedTextLifecycleTest {
    private class FakeHost : XiaoaiFixedTextAdapter.Host {
        override var lifecycleVerified = true
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var stops = 0
        var confirmsStop = true
        var throwsBeforeStart = false
        override fun speakFixedText(onFinish: (Boolean) -> Unit) {
            if (throwsBeforeStart) error("not started")
            callbacks.add(onFinish)
        }
        override fun stopOwnedPlayback(): Boolean { stops++; return confirmsStop }
    }
    private val host = FakeHost()
    private val adapter = XiaoaiFixedTextAdapter(host)
    private val binding = XiaoaiFixedTextAdapter.Binding(1, "dialog", 507012002)
    private var ends = 0
    private fun dispatch(b: XiaoaiFixedTextAdapter.Binding = binding,
        session: () -> Boolean = { true }, target: () -> Boolean = { true }) =
        adapter.broadcastFixedText(true, b, session, target) { ends++ }

    @Test fun returnDoesNotReleaseOwnership() {
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.Dispatched, dispatch())
        assertTrue(adapter.isInFlight()); assertEquals(0, ends)
        val thread = Thread { host.callbacks[0](false) }; thread.start(); thread.join()
        assertFalse(adapter.isInFlight()); assertEquals(1, ends)
    }
    @Test fun repeatedDispatchDeniedUntilGenuineEnd() {
        dispatch()
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.Denied(XiaoaiFixedTextGate.Reason.ALREADY_IN_FLIGHT), dispatch())
        assertEquals(1, host.callbacks.size)
        host.callbacks[0](false)
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.Dispatched, dispatch(binding.copy(turnId = 2)))
    }
    @Test fun lateCallbackCannotReleaseNewOwner() {
        dispatch(); val old = host.callbacks[0]
        assertTrue(adapter.cancel(507012002, binding))
        dispatch(binding.copy(turnId = 2)); old(false)
        assertTrue(adapter.isInFlight()); assertEquals(1, ends)
        host.callbacks[1](false); assertEquals(2, ends)
    }
    @Test fun noOwnerNeverStopsHost() { assertFalse(adapter.cancel(507012002)); assertEquals(0, host.stops) }
    @Test fun wrongVersionNeverStopsHost() {
        dispatch(); assertFalse(adapter.cancel(507012003)); assertEquals(0, host.stops); assertTrue(adapter.isInFlight())
    }
    @Test fun wrongOwnershipNeverStopsHost() {
        dispatch(); assertFalse(adapter.cancel(507012002, binding.copy(turnId = 99))); assertEquals(0, host.stops)
    }
    @Test fun unconfirmedStopDoesNotUnlock() {
        dispatch(); host.confirmsStop = false
        assertFalse(adapter.cancel(507012002)); assertTrue(adapter.isInFlight()); assertEquals(0, ends)
        host.callbacks[0](true); assertFalse(adapter.isInFlight())
    }
    @Test fun turnInvalidatedBetweenClaimAndDispatch() {
        var reads = 0
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.Denied(XiaoaiFixedTextGate.Reason.SESSION_NOT_BOUND),
            dispatch(session = { ++reads == 1 }))
        assertTrue(host.callbacks.isEmpty()); assertFalse(adapter.isInFlight())
    }
    @Test fun targetInvalidatedBetweenClaimAndDispatch() {
        var reads = 0
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.Denied(XiaoaiFixedTextGate.Reason.TARGET_INVALIDATED),
            dispatch(target = { ++reads == 1 }))
        assertTrue(host.callbacks.isEmpty())
    }
    @Test fun wrongDispatchVersionDenied() {
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.Denied(XiaoaiFixedTextGate.Reason.VERSION_MISMATCH),
            dispatch(binding.copy(versionCode = 12)))
        assertTrue(host.callbacks.isEmpty())
    }
    @Test fun dispatchFailureReleases() {
        host.throwsBeforeStart = true
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.DispatchFailed, dispatch())
        assertFalse(adapter.isInFlight()); assertEquals(1, ends)
    }
    @Test fun productionBackendCannotBeEnabledByConfiguration() {
        assertFalse(XiaoaiFixedTextBroadcast.HOST_BACKEND_ENABLEABLE)
        assertEquals(XiaoaiFixedTextBroadcast.BroadcastOutcome.Denied(XiaoaiFixedTextGate.Reason.BACKEND_UNVERIFIED),
            XiaoaiFixedTextBroadcast.adapter.broadcastFixedText(true, binding, { true }, { true }) {})
    }
    @Test fun triggerToAdapterSkipsNormalRequestAndIsOneShot() {
        var active: XiaoaiFixedTextAdapter.Binding? = null
        var ready = false
        var normalRequests = 0
        val trigger = XiaoaiFixedTextTrigger(adapter, { true }, { 507012002 },
            { binding.also { active = it } }, { active == it }, { ready },
            { if (active == it) active = null })
        fun input(query: String) { if (!trigger.onInput("dialog", query)) normalRequests++ }
        input(XiaoaiFixedTextTrigger.QUERY)
        assertTrue(host.callbacks.isEmpty()); assertEquals(0, normalRequests)
        trigger.onTargetCaptured("other"); assertTrue(host.callbacks.isEmpty())
        ready = true; trigger.onTargetCaptured("dialog"); trigger.onTargetCaptured("dialog")
        input(XiaoaiFixedTextTrigger.QUERY)
        assertEquals(1, host.callbacks.size); assertEquals(0, normalRequests)
        host.callbacks[0](false); assertNull(active)
        input("dynamic Agent answer"); assertEquals(1, normalRequests)
    }
    @Test fun disabledTriggerDoesNotConsumeInput() {
        val trigger = XiaoaiFixedTextTrigger(adapter, { false }, { 507012002 },
            { error("must not begin") }, { true }, { true }, {})
        assertFalse(trigger.onInput("dialog", XiaoaiFixedTextTrigger.QUERY))
        assertTrue(host.callbacks.isEmpty())
    }
    @Test fun installedEndSeamCancelsOnlyOwnedBroadcast() {
        val trigger = XiaoaiFixedTextTrigger(adapter, { true }, { 507012002 },
            { binding }, { true }, { true }, {})
        trigger.onSessionEnd(); assertEquals(0, host.stops)
        trigger.onInput("dialog", XiaoaiFixedTextTrigger.QUERY)
        trigger.onSessionEnd(); assertEquals(1, host.stops); assertFalse(adapter.isInFlight())
    }
    @Test fun responseTargetExpiryIsCheckedSynchronously() {
        var now = 0L
        val registry = XiaoaiResponseTargetRegistry(500) { now }
        registry.capture("dialog", Any()); assertTrue(registry.hasLiveTarget("dialog"))
        now = 501; assertFalse(registry.hasLiveTarget("dialog"))
    }
}
