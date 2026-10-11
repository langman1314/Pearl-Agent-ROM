package com.niki914.nexus.agentic.chat.agentic.accessibility

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellActionStateTest {
    @Test
    fun failureBeforeDispatchIsAnOrdinaryFailureAndDoesNotLatch() {
        val result = ShellActionClassifier.classifyFailure(
            code = "SHELL_FAILED",
            action = "Shell tap",
            result = ShellResult(-1, "", "No shell available (root, shizuku, and user all failed)"),
        )

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertFalse(result.ok)
        assertTrue(result.message, result.message.contains("No shell available"))
    }

    @Test
    fun sessionBusyIsAFailureBecauseTheCommandNeverRan() {
        val result = ShellActionClassifier.classifyFailure(
            code = "SHELL_FAILED",
            action = "Shell tap",
            result = ShellResult(-1, "", "Shell session busy"),
        )

        assertEquals(ToolOutcome.FAILURE, result.outcome)
    }

    @Test
    fun postDispatchTimeoutIsUnknownNotFailure() {
        val result = ShellActionClassifier.classifyFailure(
            code = "SHELL_FAILED",
            action = "Shell tap",
            result = ShellResult(-1, "", "Command timed out", mayHaveTakenEffect = true),
        )

        assertEquals(ToolOutcome.UNKNOWN, result.outcome)
        // Legacy callers read `ok`, so an unconfirmed action must still look unsuccessful.
        assertFalse(result.ok)
        assertEquals(BuiltinToolResult.OUTCOME_UNKNOWN_CODE, result.code)
        assertTrue(result.hint, result.hint.contains("未重复操作"))
        assertEquals(BuiltinToolResult.OUTCOME_UNKNOWN_HINT, result.hint)
        assertTrue(result.message, result.message.contains("will not send it again"))
    }

    @Test
    fun lostSessionAfterDispatchIsUnknown() {
        val result = ShellActionClassifier.classifyFailure(
            code = "SHELL_FAILED",
            action = "Shell gesture",
            result = ShellResult(-1, "", "Shell session lost", mayHaveTakenEffect = true),
        )

        assertEquals(ToolOutcome.UNKNOWN, result.outcome)
    }

    @Test
    fun unknownResultSerializesWithAStableCodeAndHint() {
        val json = ShellActionClassifier.classifyFailure(
            code = "SHELL_FAILED",
            action = "Shell tap",
            result = ShellResult(-1, "", "Command timed out", mayHaveTakenEffect = true),
        ).toJsonString()

        assertTrue(json, json.contains("\"outcome\":\"unknown\""))
        assertTrue(json, json.contains("\"ok\":false"))
        assertTrue(json, json.contains(BuiltinToolResult.OUTCOME_UNKNOWN_CODE))
    }

    @Test
    fun trackerStartsCleanAndReportsNoRefusal() {
        val tracker = IndeterminateActionTracker()

        assertFalse(tracker.isLatched)
        assertNull(tracker.recordedDescription)
        assertNull(tracker.refusal("node_action"))
    }

    @Test
    fun trackerRefusesFurtherSideEffectsOnceLatched() {
        val tracker = IndeterminateActionTracker()
        tracker.record("Shell tap: Command timed out")

        val refusal = tracker.refusal("node_action")

        assertNotNull(refusal)
        assertEquals("ACTION_STATE_INDETERMINATE", refusal!!.code)
        assertEquals(ToolOutcome.FAILURE, refusal.outcome)
        assertTrue(refusal.message, refusal.message.contains("Shell tap: Command timed out"))
        assertTrue(refusal.hint, refusal.hint.contains("screen_content"))
        assertTrue(refusal.hint, refusal.hint.contains(BuiltinToolResult.OUTCOME_UNKNOWN_HINT))
    }

    @Test
    fun trackerKeepsTheFirstIndeterminateAction() {
        val tracker = IndeterminateActionTracker()
        tracker.record("first action")
        tracker.record("second action")

        assertEquals("first action", tracker.recordedDescription)
    }

    @Test
    fun trackerResetClearsTheLatchForTheNextRound() {
        val tracker = IndeterminateActionTracker()
        tracker.record("Shell tap: Command timed out")

        tracker.reset()

        assertFalse(tracker.isLatched)
        assertNull(tracker.refusal("node_action"))
    }
}
