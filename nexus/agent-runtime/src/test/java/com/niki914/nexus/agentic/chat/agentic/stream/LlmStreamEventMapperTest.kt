package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.ToolCallKind as SessionToolCallKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmStreamEventMapperTest {
    private val accumulator = StringBuilder()

    private fun mapToolSucceeded(resultJson: String): LlmStreamEvent? {
        return LlmStreamEventMapper.map(
            event = SessionEvent.ToolSucceeded(
                callId = "call-1",
                toolName = "node_action",
                kind = SessionToolCallKind.Local,
                resultJson = resultJson,
            ),
            accumulator = accumulator,
            startedAtMs = System.currentTimeMillis(),
            defaultErrorMessage = "failed",
        )
    }

    @Test
    fun confirmedSuccessMapsToToolSucceeded() {
        val event = mapToolSucceeded(BuiltinToolResult.success("tapped").toJsonString())

        assertTrue(event is LlmStreamEvent.ToolSucceeded)
        assertEquals("call-1", (event as LlmStreamEvent.ToolSucceeded).call.callId)
    }

    @Test
    fun unknownOutcomeIsReportedAsUnconfirmedFailureNotSuccess() {
        val event = mapToolSucceeded(
            BuiltinToolResult.unknown(message = "tap was sent but never confirmed").toJsonString()
        )

        assertTrue(event is LlmStreamEvent.ToolFailed)
        val failed = event as LlmStreamEvent.ToolFailed
        assertTrue(failed.unconfirmed)
        assertTrue(failed.message, failed.message.contains("tap was sent but never confirmed"))
        assertTrue(failed.message, failed.message.contains("未重复操作"))
    }

    @Test
    fun emptyResultIsNeverSuccess() {
        listOf("", "   ").forEach { payload ->
            val event = mapToolSucceeded(payload)
            assertTrue("payload=$payload", event is LlmStreamEvent.ToolFailed)
            assertFalse((event as LlmStreamEvent.ToolFailed).unconfirmed)
        }
    }

    @Test
    fun malformedJsonIsNeverSuccess() {
        val event = mapToolSucceeded("""{"ok":true""")

        assertTrue(event is LlmStreamEvent.ToolFailed)
    }

    @Test
    fun contradictoryResultIsNeverSuccess() {
        // ok=false alongside outcome=success cannot be read as success.
        val event = mapToolSucceeded(
            """{"ok":false,"outcome":"success","code":"OK","message":"tap sent"}"""
        )

        assertTrue(event is LlmStreamEvent.ToolFailed)
        assertTrue((event as LlmStreamEvent.ToolFailed).unconfirmed)
    }

    @Test
    fun legacyFailureEnvelopeStillMapsToFailure() {
        val event = mapToolSucceeded(
            BuiltinToolResult.failure("NODE_NOT_FOUND", "Node 1 not found").toJsonString()
        )

        assertTrue(event is LlmStreamEvent.ToolFailed)
        assertFalse((event as LlmStreamEvent.ToolFailed).unconfirmed)
        assertEquals("Node 1 not found", event.message)
    }

    @Test
    fun plainObservationOutputStillMapsToSuccess() {
        // screen_content and search_nodes return YAML, which carries no verdict of its own.
        val event = mapToolSucceeded("snapshot_id: 7\nroot:\n  - {i: 0, t: button}")

        assertTrue(event is LlmStreamEvent.ToolSucceeded)
    }

    @Test
    fun rawSearchErrorStillMapsToFailure() {
        val event = mapToolSucceeded("error: keywords must be a non-empty array of strings")

        assertTrue(event is LlmStreamEvent.ToolFailed)
    }

    @Test
    fun sessionLevelToolFailureKeepsItsMessage() {
        val event = LlmStreamEventMapper.map(
            event = SessionEvent.ToolFailed(
                callId = "call-2",
                toolName = "node_action",
                kind = SessionToolCallKind.Local,
                message = "tool refused",
                resultJson = null,
            ),
            accumulator = accumulator,
            startedAtMs = System.currentTimeMillis(),
            defaultErrorMessage = "failed",
        )

        assertTrue(event is LlmStreamEvent.ToolFailed)
        assertEquals("tool refused", (event as LlmStreamEvent.ToolFailed).message)
        assertFalse(event.unconfirmed)
    }
}
