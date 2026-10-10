package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the decoding gap: a payload with a missing, wrongly typed, unknown or
 * contradictory verdict must neither crash nor read as a success, while the tool payload shapes that
 * legitimately carry no verdict keep working.
 */
class ToolResultDecodingRegressionTest {
    @Test
    fun typeErrorInOkNeverThrowsAndNeverSucceeds() {
        val decoded = BuiltinToolResult.fromJsonString("""{"ok":{}}""")

        assertNotNull(decoded)
        assertEquals(ToolOutcome.UNKNOWN, decoded!!.outcome)
        assertFalse(decoded.ok)
        assertTrue(
            LocalToolResultClassifier.status("""{"ok":{}}""") is LocalToolResultStatus.Unconfirmed
        )
    }

    @Test
    fun unknownOutcomeNameDoesNotFallBackToSuccess() {
        val decoded = BuiltinToolResult.fromJsonString("""{"ok":true,"outcome":"bogus"}""")

        assertNotNull(decoded)
        assertEquals(ToolOutcome.UNKNOWN, decoded!!.outcome)
        assertFalse(decoded.ok)
        assertFalse(
            LocalToolResultClassifier.status("""{"ok":true,"outcome":"bogus"}""")
                is LocalToolResultStatus.Success
        )
    }

    @Test
    fun unknownOutcomeIsReserializedAsOkFalseAndDecodesBackToUnknown() {
        val decoded = BuiltinToolResult.fromJsonString("""{"ok":true,"outcome":"unknown"}""")

        assertNotNull(decoded)
        assertEquals(ToolOutcome.UNKNOWN, decoded!!.outcome)
        val reserialized = Json.parseToJsonElement(decoded.toJsonString()).jsonObject
        assertEquals("false", reserialized["ok"]!!.jsonPrimitive.content)
        assertEquals("unknown", reserialized["outcome"]!!.jsonPrimitive.content)
        assertEquals(
            ToolOutcome.UNKNOWN,
            BuiltinToolResult.fromJsonString(decoded.toJsonString())?.outcome,
        )
    }

    @Test
    fun verdictLessEmptyObjectCannotConfirmASuccess() {
        assertTrue(LocalToolResultClassifier.status("{}") is LocalToolResultStatus.Failed)
        assertNotNull(LocalToolResultClassifier.failureMessage("{}"))
    }

    @Test
    fun emptyAndTruncatedJsonStayFailures() {
        listOf("", "   ", "{", """{"ok":true""", "{]").forEach { payload ->
            assertTrue(
                "'$payload' must not be a success",
                LocalToolResultClassifier.status(payload) is LocalToolResultStatus.Failed,
            )
        }
    }

    @Test
    fun legacyEnvelopesKeepTheirMeaning() {
        assertEquals(
            LocalToolResultStatus.Success,
            LocalToolResultClassifier.status("""{"ok":true,"code":"OK","message":"done"}"""),
        )
        assertTrue(
            LocalToolResultClassifier.status("""{"ok":false,"code":"DENIED","message":"no"}""")
                is LocalToolResultStatus.Failed
        )
    }

    @Test
    fun contradictoryEnvelopeIsUnconfirmed() {
        assertTrue(
            LocalToolResultClassifier.status("""{"ok":false,"outcome":"success"}""")
                is LocalToolResultStatus.Unconfirmed
        )
    }

    @Test
    fun verdictLessToolPayloadsKeepTheirReading() {
        listOf(
            // Custom tool success and the memorize/read_custom_tool envelopes.
            """{"ok":true,"command":"printf sample_model","stdout":"sample_model"}""",
            """{"ok":true}""",
            // Terminal: open, exec, interactive write/read, async accept and close.
            """{"session":"s1","identity":"local"}""",
            """{"exit_code":0,"stdout":"/","stderr":""}""",
            """{"accepted":true,"bytes_written":3,"sequence":4,"replayed":false}""",
            """{"stdout":"","stderr":"","mode":"delta","sequence":3,"truncated":false}""",
            """{"async_id":"a1","accepted":true,"elapsed_seconds":1}""",
            """{"closed":true}""",
            // Raw observation payloads and unstructured error strings keep their old handling.
            "snapshot_id: 42\nroot:\n  - {i: 0, t: button}",
            """{"error":"not structured"}""",
        ).forEach { payload ->
            assertEquals(
                "'$payload' must stay readable",
                LocalToolResultStatus.Success,
                LocalToolResultClassifier.status(payload),
            )
        }
    }
}
