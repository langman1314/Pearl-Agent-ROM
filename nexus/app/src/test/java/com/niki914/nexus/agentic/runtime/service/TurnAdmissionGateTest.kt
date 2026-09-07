package com.niki914.nexus.agentic.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnAdmissionGateTest {
    @Test
    fun reservationDoesNotActivateWork() {
        val gate = TurnAdmissionGate<String>(1_000L)

        assertTrue(gate.reserve("request-1"))
        assertTrue(gate.isReserved("request-1"))
        assertNull(gate.active())
    }

    @Test
    fun sameReservationIsIdempotentAndDifferentRequestIsRejected() {
        val gate = TurnAdmissionGate<String>(1_000L)

        assertTrue(gate.reserve("request-1"))
        assertTrue(gate.reserve("request-1"))
        assertFalse(gate.reserve("request-2"))
    }

    @Test
    fun onlyReservationOwnerCanCommit() {
        val gate = TurnAdmissionGate<String>(1_000L)

        assertTrue(gate.reserve("request-1"))
        assertFalse(gate.commit("request-2", "wrong"))
        assertTrue(gate.commit("request-1", "active"))
        assertEquals("active", gate.active())
        assertFalse(gate.commit("request-1", "duplicate"))
    }

    @Test
    fun releasedReservationRejectsLateCommit() {
        val gate = TurnAdmissionGate<String>(1_000L)

        assertTrue(gate.reserve("request-1"))
        assertTrue(gate.releaseReservation("request-1"))
        assertFalse(gate.commit("request-1", "late"))
        assertNull(gate.active())
    }

    @Test
    fun expiredReservationRejectsLateCommitAndAllowsAnotherOwner() {
        var now = 100L
        val gate = TurnAdmissionGate<String>(50L) { now }

        assertTrue(gate.reserve("request-1"))
        now = 150L
        assertFalse(gate.commit("request-1", "late"))
        assertTrue(gate.reserve("request-2"))
    }

    @Test
    fun activeTurnBlocksOtherReservationsUntilOwnerClearsIt() {
        val gate = TurnAdmissionGate<String>(1_000L)

        assertTrue(gate.reserve("request-1"))
        assertTrue(gate.commit("request-1", "active"))
        assertFalse(gate.reserve("request-2"))
        assertNull(gate.clearActive("request-2"))
        assertEquals("active", gate.clearActive("request-1"))
        assertTrue(gate.reserve("request-2"))
    }
}
