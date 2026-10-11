package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Cano81IndependentRegressionTest {
    @Test fun unusableOkCannotConfirmDeclaredSuccess() {
        for (payload in listOf("""{"ok":{},"outcome":"success"}""", """{"ok":null,"outcome":"success"}""")) {
            assertEquals(ToolOutcome.UNKNOWN, BuiltinToolResult.fromJsonString(payload)?.outcome)
        }
    }

    @Test fun rawDispatchedResultMustPreventDuplicateCallId() {
        val guard = RoundActionGuard()
        val arguments = """{"command":"echo harmless"}"""
        guard.afterCall("terminal", arguments, "terminal-1", """{"exit_code":0,"stdout":"done"}""")
        assertTrue(guard.beforeCall("terminal", arguments, "terminal-1") is RoundActionGuard.Decision.Replay)
        assertEquals(RoundActionGuard.Decision.Proceed, guard.beforeCall("terminal", arguments, "terminal-2"))
    }

    @Test fun observationBreaksIdlessReplayWindow() {
        val guard = RoundActionGuard()
        val arguments = """{"action":"click","index":1}"""
        guard.afterCall("node_action", arguments, null, BuiltinToolResult.success("clicked").toJsonString())
        assertEquals(RoundActionGuard.Decision.Proceed, guard.beforeCall("screen_content", "{}", null))
        guard.afterCall("screen_content", "{}", null, "screen: ready")
        assertEquals(RoundActionGuard.Decision.Proceed, guard.beforeCall("node_action", arguments, null))
    }

    @Test fun undecodableDispatchedResultMustLatchWithoutBlindingObservation() {
        val guard = RoundActionGuard()
        guard.afterCall("node_action", "{}", "action-1", "{")
        assertTrue(guard.isLatched)
        assertTrue(guard.beforeCall("node_action", "{}", "action-2") is RoundActionGuard.Decision.Refused)
        assertEquals(RoundActionGuard.Decision.Proceed, guard.beforeCall("screen_content", "{}", null))
    }
}
