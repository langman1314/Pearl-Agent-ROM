package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolResultClassifierTest {
    @Test
    fun failureMessage_returnsNullForZeroExitCode() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"exit_code":0,"stdout":"ok"}"""
        )

        assertNull(message)
    }

    @Test
    fun failureMessage_usesStderrForNonZeroExitCode() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"exit_code":1,"stderr":"not found"}"""
        )

        assertEquals("not found", message)
    }

    @Test
    fun failureMessage_usesNestedErrorMessageForStructuredToolError() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"error":{"code":"SESSION_NOT_FOUND","message":"Call open first"}}"""
        )

        assertEquals("Call open first", message)
    }

    @Test
    fun failureMessage_usesNestedErrorCodeWhenErrorMessageMissing() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"error":{"code":"TIMEOUT"}}"""
        )

        assertEquals("TIMEOUT", message)
    }

    @Test
    fun failureMessage_ignoresPlainErrorString() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"error":"not structured"}"""
        )

        assertNull(message)
    }

    @Test
    fun failureMessage_describesNonZeroExitWithoutStatusText() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"exit_code":2,"stdout":""}"""
        )

        assertEquals("Command completed with non-zero exit code 2.", message)
    }

    @Test
    fun failureMessage_usesMessageForExplicitOkFalse() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"ok":false,"message":"blocked"}"""
        )

        assertEquals("blocked", message)
    }

    @Test
    fun failureMessage_ignoresUnstructuredToolOutput() {
        // screen_content and search_nodes return YAML, which carries no success verdict either
        // way. Treating it as a failure would blind the agent after every observation.
        val message = LocalToolResultClassifier.failureMessage("plain text")

        assertNull(message)
    }

    @Test
    fun status_reportsUnknownOutcomeAsUnconfirmedNotSuccess() {
        val json = BuiltinToolResult.unknown(message = "tap was sent but never confirmed").toJsonString()

        val status = LocalToolResultClassifier.status(json)

        assertTrue(status is LocalToolResultStatus.Unconfirmed)
        val message = (status as LocalToolResultStatus.Unconfirmed).message
        assertTrue(message, message.contains("tap was sent but never confirmed"))
        assertTrue(message, message.contains(BuiltinToolResult.OUTCOME_UNKNOWN_HINT))
    }

    @Test
    fun status_neverReportsUnknownAsSuccess() {
        val json = BuiltinToolResult.unknown(message = "unconfirmed").toJsonString()

        assertTrue(LocalToolResultClassifier.failureMessage(json) != null)
    }

    @Test
    fun status_reportsEmptyResultAsFailureNotSuccess() {
        listOf(null, "", "   ").forEach { payload ->
            val status = LocalToolResultClassifier.status(payload)
            assertTrue(
                "payload=$payload",
                status is LocalToolResultStatus.Failed,
            )
        }
    }

    @Test
    fun status_reportsMalformedJsonAsFailureNotSuccess() {
        listOf("""{"ok":true""", """{"ok":""", """["unterminated"""").forEach { payload ->
            val status = LocalToolResultClassifier.status(payload)
            assertTrue("payload=$payload", status is LocalToolResultStatus.Failed)
        }
    }

    @Test
    fun status_treatsContradictoryPayloadAsUnconfirmedNotSuccess() {
        // ok=false next to outcome=success must never be read as a success.
        val json = """{"ok":false,"outcome":"success","code":"OK","message":"tap sent"}"""

        val status = LocalToolResultClassifier.status(json)

        assertTrue(status is LocalToolResultStatus.Unconfirmed)
        assertTrue(LocalToolResultClassifier.failureMessage(json) != null)
    }

    @Test
    fun status_decodesLegacyEnvelopeWithoutOutcomeField() {
        assertEquals(
            LocalToolResultStatus.Success,
            LocalToolResultClassifier.status("""{"ok":true,"code":"OK","message":"done"}"""),
        )
        assertTrue(
            LocalToolResultClassifier.status("""{"ok":false,"code":"DENIED","message":"no"}""")
                is LocalToolResultStatus.Failed
        )
        // Envelopes written before `outcome` existed but shaped by the same factory.
        assertEquals(
            LocalToolResultStatus.Success,
            LocalToolResultClassifier.status(BuiltinToolResult.success("ok").toJsonString()),
        )
        assertTrue(
            LocalToolResultClassifier.status(BuiltinToolResult.failure("X", "no").toJsonString())
                is LocalToolResultStatus.Failed
        )
    }

    @Test
    fun status_keepsPlainTextObservationOutputUsable() {
        assertEquals(
            LocalToolResultStatus.Success,
            LocalToolResultClassifier.status("snapshot_id: 42\nroot:\n  - {i: 0, t: button}"),
        )
        assertEquals(
            LocalToolResultStatus.Success,
            LocalToolResultClassifier.status("matched: 0\nnodes:\n"),
        )
    }

    @Test
    fun status_readsRawSearchErrorPayloadAsFailure() {
        val status = LocalToolResultClassifier.status("error: keywords must be a non-empty array of strings")

        assertTrue(status is LocalToolResultStatus.Failed)
        assertEquals(
            "keywords must be a non-empty array of strings",
            (status as LocalToolResultStatus.Failed).message,
        )
    }
}
