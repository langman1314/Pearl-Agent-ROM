package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundActionGuardTest {
    @Test
    fun firstCallProceeds() {
        val guard = RoundActionGuard()

        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("node_action", """{"index":1}""", "call-1"),
        )
        assertFalse(guard.isLatched)
        assertNull(guard.latchSource)
    }

    @Test
    fun repeatedCallIdIsReplayedInsteadOfExecutedAgain() {
        val guard = RoundActionGuard()
        val resultJson = BuiltinToolResult.success("tapped").toJsonString()

        guard.beforeCall("node_action", """{"index":1}""", "call-1")
        guard.afterCall("node_action", """{"index":1}""", "call-1", resultJson)

        val decision = guard.beforeCall("node_action", """{"index":1}""", "call-1")

        assertTrue(decision is RoundActionGuard.Decision.Replay)
        assertEquals(resultJson, (decision as RoundActionGuard.Decision.Replay).resultJson)
    }

    @Test
    fun repeatedCallIdIsReplayedEvenWithIdenticalTwinCallsInFlight() {
        val guard = RoundActionGuard()
        val resultJson = BuiltinToolResult.success("sent").toJsonString()

        // First dispatch completes, then the model re-emits the same call id.
        guard.afterCall("key_event", """{"key":4}""", "call-7", resultJson)
        val second = guard.beforeCall("key_event", """{"key":4}""", "call-7")

        assertTrue(second is RoundActionGuard.Decision.Replay)
    }

    @Test
    fun sameArgumentsWithoutACallIdIsStillADuplicate() {
        val guard = RoundActionGuard()
        val resultJson = BuiltinToolResult.success("tapped").toJsonString()

        guard.afterCall("node_action", """{"index":1}""", null, resultJson)

        val decision = guard.beforeCall("node_action", """{"index":1}""", null)

        assertTrue(decision is RoundActionGuard.Decision.Replay)
    }

    @Test
    fun differentCallIdsAndArgumentsAreNotDuplicates() {
        val guard = RoundActionGuard()

        guard.afterCall(
            "node_action", """{"index":1}""", "call-1",
            BuiltinToolResult.success("tapped").toJsonString(),
        )

        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("node_action", """{"index":2}""", "call-2"),
        )
    }

    @Test
    fun unknownOutcomeLatchesTheRound() {
        val guard = RoundActionGuard()

        guard.afterCall(
            "node_action", """{"index":1}""", "call-1",
            BuiltinToolResult.unknown(message = "tap may have landed").toJsonString(),
        )

        assertTrue(guard.isLatched)
        assertEquals("node_action", guard.latchSource)
    }

    @Test
    fun afterUnknownFurtherSideEffectsAreRefused() {
        val guard = RoundActionGuard()
        guard.afterCall(
            "node_action", """{"index":1}""", "call-1",
            BuiltinToolResult.unknown(message = "tap may have landed").toJsonString(),
        )

        listOf("node_action", "gesture", "key_event", "launch_app", "open_uri").forEach { tool ->
            val decision = guard.beforeCall(tool, """{"index":99}""", "call-$tool")
            assertTrue("$tool should be refused", decision is RoundActionGuard.Decision.Refused)
            val json = Json.parseToJsonElement(
                (decision as RoundActionGuard.Decision.Refused).resultJson
            ).jsonObject
            assertEquals("ACTION_STATE_INDETERMINATE", json["code"]!!.jsonPrimitive.content)
            // Refusals are ordinary failures: nothing was dispatched, so nothing is left unknown.
            assertEquals("failure", json["outcome"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun afterUnknownReadOnlyObservationStaysAvailable() {
        val guard = RoundActionGuard()
        guard.afterCall(
            "node_action", """{"index":1}""", "call-1",
            BuiltinToolResult.unknown(message = "tap may have landed").toJsonString(),
        )

        // Re-reading the screen is how the agent finds out what actually happened.
        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("screen_content", "{}", "call-2"),
        )
        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("search_nodes", """{"keywords":["sent"]}""", "call-3"),
        )
    }

    @Test
    fun unconfirmedObservationOutputNeverLatches() {
        val guard = RoundActionGuard()

        // screen_content returns YAML, not a Nexus envelope: it carries no verdict.
        guard.afterCall("screen_content", "{}", "call-1", "snapshot_id: 7\nroot:\n")

        assertFalse(guard.isLatched)
        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("node_action", """{"index":1}""", "call-2"),
        )
    }

    @Test
    fun explicitFailureDoesNotLatchTheRound() {
        val guard = RoundActionGuard()

        guard.afterCall(
            "node_action", """{"index":1}""", "call-1",
            BuiltinToolResult.failure("NODE_NOT_FOUND", "Node 1 not found").toJsonString(),
        )

        assertFalse(guard.isLatched)
        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("node_action", """{"index":2}""", "call-2"),
        )
    }

    @Test
    fun beginRoundClearsDedupeAndLatch() {
        val guard = RoundActionGuard()
        guard.afterCall(
            "node_action", """{"index":1}""", "call-1",
            BuiltinToolResult.unknown(message = "unconfirmed").toJsonString(),
        )

        guard.beginRound()

        assertFalse(guard.isLatched)
        assertNull(guard.latchSource)
        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("node_action", """{"index":1}""", "call-1"),
        )
    }

    @Test
    fun duplicateOfAnUnconfirmedCallIsReplayedAsUnknownRatherThanReExecuted() {
        val guard = RoundActionGuard()
        val unconfirmed = BuiltinToolResult.unknown(message = "tap may have landed").toJsonString()
        guard.afterCall("node_action", """{"index":1}""", "call-1", unconfirmed)

        val decision = guard.beforeCall("node_action", """{"index":1}""", "call-1")

        assertTrue(decision is RoundActionGuard.Decision.Replay)
        assertEquals(unconfirmed, (decision as RoundActionGuard.Decision.Replay).resultJson)
        assertEquals(
            ToolOutcome.UNKNOWN,
            BuiltinToolResult.fromJsonString(decision.resultJson)?.outcome,
        )
    }
}
