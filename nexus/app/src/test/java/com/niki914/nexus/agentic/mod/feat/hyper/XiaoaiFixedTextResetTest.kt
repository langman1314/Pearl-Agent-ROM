package com.niki914.nexus.agentic.mod.feat.hyper

import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual trigger reset seam, with immediate probes (no blocking/sleep). */
class XiaoaiFixedTextResetTest {
    private class Host : XiaoaiFixedTextAdapter.Host {
        override var lifecycleVerified = true
        var stopCalls = 0
        var confirmsStop = true
        val finishes = mutableListOf<(Boolean) -> Unit>()
        override fun speakFixedText(onFinish: (Boolean) -> Unit) { finishes += onFinish }
        override fun stopOwnedPlayback(): Boolean { stopCalls++; return confirmsStop }
    }
    private val host = Host()
    private val adapter = XiaoaiFixedTextAdapter(host)
    private val binding = XiaoaiFixedTextAdapter.Binding(1, "room", 507012002)
    private var versionCalls = 0
    private var endCalls = 0
    private var enabled = true
    private var targetReady = true
    private var versionProbe: () -> Long = { 507012002L }
    private val trigger = XiaoaiFixedTextTrigger(adapter, { enabled },
        { versionCalls++; versionProbe() }, { binding }, { true }, { targetReady }, { endCalls++ })
    private fun arm() { assertTrue(trigger.onInput("room", XiaoaiFixedTextTrigger.QUERY)) }
    private fun failIfVersionRead() { versionProbe = { error("Unused version service was evaluated") } }

    @Test fun neverBroadcastResetDoesNotReadVersion() {
        failIfVersionRead(); trigger.onSessionEnd()
        assertEquals(0, versionCalls); assertEquals(0, host.stopCalls); assertEquals(0, endCalls)
    }
    @Test fun disabledResetDoesNotReadVersion() {
        enabled = false; failIfVersionRead(); trigger.onSessionEnd()
        assertEquals(0, versionCalls); assertEquals(0, host.stopCalls)
    }
    @Test fun hardLockedResetDoesNotReadVersion() {
        host.lifecycleVerified = false; failIfVersionRead(); arm(); trigger.onSessionEnd()
        assertEquals(0, versionCalls); assertEquals(0, host.stopCalls); assertEquals(0, endCalls)
    }
    @Test fun pendingCleanupOccursOnceWithoutVersionLookup() {
        targetReady = false; arm(); assertEquals(1, versionCalls)
        failIfVersionRead(); trigger.onSessionEnd(); trigger.onSessionEnd()
        assertEquals(1, versionCalls); assertEquals(1, endCalls); assertEquals(0, host.stopCalls)
        trigger.onTargetCaptured("room"); assertTrue(host.finishes.isEmpty())
    }
    @Test fun ownerStillCancelledAfterConfigurationDisabled() {
        arm(); enabled = false; host.lifecycleVerified = false; trigger.onSessionEnd()
        assertEquals(2, versionCalls); assertEquals(1, host.stopCalls)
        assertEquals(1, endCalls); assertFalse(adapter.isInFlight())
    }
    @Test fun wrongVersionDoesNotStopOwner() {
        arm(); versionProbe = { 42L }; trigger.onSessionEnd()
        assertEquals(2, versionCalls); assertEquals(0, host.stopCalls)
        assertEquals(0, endCalls); assertTrue(adapter.isInFlight())
    }
    @Test fun unconfirmedStopRetainsOwnerAndLateFinishCannotClearSuccessor() {
        arm(); host.confirmsStop = false; trigger.onSessionEnd()
        assertEquals(2, versionCalls); assertEquals(1, host.stopCalls)
        assertEquals(0, endCalls); assertTrue(adapter.isInFlight())
        val old = host.finishes[0]; old(false); assertEquals(1, endCalls)
        arm(); old(false); assertTrue(adapter.isInFlight()); assertEquals(1, endCalls)
        host.finishes[1](false); assertFalse(adapter.isInFlight()); assertEquals(2, endCalls)
    }
    @Test fun ownerHandoffDuringVersionReadCannotStopSuccessorEvenWithSameBinding() {
        arm(); val old = host.finishes[0]
        versionProbe = {
            old(false)
            adapter.broadcastFixedText(true, binding, { true }, { true }) { endCalls++ }
            507012002L
        }
        trigger.onSessionEnd()
        assertEquals(2, versionCalls); assertEquals(0, host.stopCalls)
        assertEquals(1, endCalls); assertTrue(adapter.isInFlight())
        old(false); assertTrue(adapter.isInFlight()); assertEquals(1, endCalls)
        host.finishes[1](false); assertEquals(2, endCalls); assertFalse(adapter.isInFlight())
    }
}
