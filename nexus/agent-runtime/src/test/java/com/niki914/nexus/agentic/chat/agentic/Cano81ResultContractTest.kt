package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandPolicyDecision
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result contract the phone closed loop depends on.
 *
 * Completion is decided *at the tool exit* by the tool that really ran, and one canonical result is
 * shared by the first return, the guard's bookkeeping and the replay of the same call id. Nothing
 * here infers completion from key names, and nothing treats "accepted", "running", a broken payload,
 * or an exit code that is not a valid integer as a finished action.
 *
 * Rows follow the verifier's eight-row matrix. The decisive assertion for each row is the dispatched
 * count, because an equivalent string with a second dispatch is still a duplicate.
 */
class Cano81ResultContractTest {
    private val arguments = """{"command":"echo harmless"}"""

    // Rows 1-2: an async receipt and a running poll prove acceptance, never completion.
    @Test
    fun asyncAcceptanceAndRunningAreUnconfirmed() {
        val accepted = TerminalToolResponse.asyncAccepted(asyncId = "a1", elapsedSeconds = 1L)
        val running = TerminalToolResponse.asyncRunning("partial out", "partial err", 5L)

        for (payload in listOf(accepted, running)) {
            assertTrue(
                "an async receipt or running poll never proves the work finished: $payload",
                LocalToolResultClassifier.status(payload) is LocalToolResultStatus.Unconfirmed,
            )
        }
    }

    @Test
    fun asyncAcceptedAndRunningBothLatchTheRound() {
        for (payload in listOf(
            TerminalToolResponse.asyncAccepted(asyncId = "a1", elapsedSeconds = 1L),
            TerminalToolResponse.asyncRunning("out", "err", 5L),
        )) {
            val guard = RoundActionGuard()
            guard.afterCall("terminal", arguments, "terminal-1", payload)

            assertTrue("in-flight work must latch: $payload", guard.isLatched)
            assertTrue(
                guard.beforeCall("terminal", arguments, "terminal-2")
                    is RoundActionGuard.Decision.Refused,
            )
        }
    }

    // Row 3: the replay of a broken payload must be the canonical result the first call returned,
    // not the broken original.
    @Test
    fun brokenPayloadReplayEqualsTheCanonicalFirstResult() {
        val guard = RoundActionGuard()

        val firstReturn = guard.afterCall("node_action", "{}", "action-1", "{")

        assertEquals(ToolOutcome.UNKNOWN, BuiltinToolResult.fromJsonString(firstReturn)?.outcome)
        assertNotEquals("the raw broken payload must not be what the caller sees", "{", firstReturn)

        val replay = guard.beforeCall("node_action", "{}", "action-1")
        assertTrue(replay is RoundActionGuard.Decision.Replay)
        assertEquals(
            "a replay must hand back exactly what the first call returned",
            firstReturn,
            (replay as RoundActionGuard.Decision.Replay).resultJson,
        )
    }

    // Row 4.
    @Test
    fun explicitOkTrueWithUnknownOutcomeIsNormalized() {
        val payload = """{"ok":true,"outcome":"unknown","message":"tap not confirmed"}"""

        assertEquals(ToolOutcome.UNKNOWN, BuiltinToolResult.fromJsonString(payload)?.outcome)
        assertFalse(BuiltinToolResult.fromJsonString(payload)!!.ok)
        assertTrue(LocalToolResultClassifier.status(payload) is LocalToolResultStatus.Unconfirmed)
    }

    // Row 5: a valid integer exit_code is a real completion, and dedupe is by call identity.
    @Test
    fun synchronousCompletionIsDecidedAndDedupedByCallIdentity() {
        val tool = CountingTerminalTool("""{"exit_code":0,"stdout":"done","stderr":""}""")
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool)))
        val guard = RoundActionGuard()

        val first = dispatch(executor, guard, "terminal-1")
        assertTrue(
            "exit_code=0 on the completion path is an explicit success",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Success,
        )
        assertEquals("the tool really ran once", 1, tool.invocations)

        val sameId = dispatch(executor, guard, "terminal-1")
        assertEquals("the same call id returns the same canonical result", first, sameId)
        assertEquals("the same call id must not dispatch again", 1, tool.invocations)

        val newId = dispatch(executor, guard, "terminal-2")
        assertTrue(LocalToolResultClassifier.status(newId) is LocalToolResultStatus.Success)
        assertEquals("a different call id is a different call", 2, tool.invocations)
    }

    @Test
    fun nonZeroExitCodeIsAFailure() {
        val payload = """{"exit_code":1,"stdout":"","stderr":"boom"}"""

        assertTrue(
            LocalToolResultClassifier.status(payload) is LocalToolResultStatus.Failed,
        )
    }

    // Row 6: a refusal that happened before dispatch stays retryable and never poisons the round.
    @Test
    fun blockedCommandNeverDispatchedStaysRetryable() {
        val payload = TerminalToolResponse.policyBlocked(
            ShellCommandPolicyDecision(
                allowed = false,
                code = "BLOCKED",
                reason = "Command blocked by test policy.",
            )
        )

        assertTrue(LocalToolResultClassifier.status(payload) is LocalToolResultStatus.Failed)

        val guard = RoundActionGuard()
        guard.afterCall("terminal", arguments, "terminal-1", payload)
        assertFalse("a pre-dispatch refusal must not latch the round", guard.isLatched)
        assertEquals(
            RoundActionGuard.Decision.Proceed,
            guard.beforeCall("terminal", arguments, "terminal-2"),
        )
    }

    // Row 7: observation stays readable after an unconfirmed action, without unlocking anything.
    @Test
    fun observationStaysReadableAndDoesNotProveTheOldActionFinished() {
        val guard = RoundActionGuard()
        guard.afterCall("node_action", "{}", "action-1", "{")
        assertTrue(guard.isLatched)

        assertEquals(RoundActionGuard.Decision.Proceed, guard.beforeCall("screen_content", "{}", null))
        val observation = "snapshot_id: 42\nroot:\n  - {i: 0, t: button}"
        guard.afterCall("screen_content", "{}", null, observation)
        assertTrue(
            "observation output stays readable",
            LocalToolResultClassifier.status(observation) is LocalToolResultStatus.Success,
        )

        assertTrue("reading the screen must not clear the latch", guard.isLatched)
        assertTrue(
            guard.beforeCall("node_action", "{}", "action-2") is RoundActionGuard.Decision.Refused,
        )
    }

    // Added counterexamples: an exit code that is not a valid integer, and closed=false.
    @Test
    fun invalidExitCodeTypesNeverConfirmCompletion() {
        listOf(
            """{"exit_code":null,"stdout":"out","stderr":""}""",
            """{"exit_code":"0","stdout":"out","stderr":""}""",
            """{"exit_code":{},"stdout":"out","stderr":""}""",
            """{"exit_code":0.5,"stdout":"out","stderr":""}""",
        ).forEach { payload ->
            assertTrue(
                "'$payload' carries no valid integer exit code, so it cannot confirm completion",
                LocalToolResultClassifier.status(payload) !is LocalToolResultStatus.Success,
            )
        }
    }

    @Test
    fun closedFalseIsNotACompletion() {
        assertTrue(
            "a session reporting it is not closed says nothing about a completed action",
            LocalToolResultClassifier.status("""{"closed":false}""")
                !is LocalToolResultStatus.Success,
        )
    }

    private fun dispatch(
        executor: BuiltinToolExecutor,
        guard: RoundActionGuard,
        callId: String,
    ): String {
        return when (val decision = guard.beforeCall("terminal", arguments, callId)) {
            is RoundActionGuard.Decision.Replay -> decision.resultJson
            is RoundActionGuard.Decision.Refused -> decision.resultJson
            RoundActionGuard.Decision.Proceed -> {
                val raw = runBlocking {
                    executor.execute(name = "terminal", argumentsJson = arguments, callId = callId)
                }
                guard.afterCall("terminal", arguments, callId, raw)
            }
        }
    }

    /**
     * The tool exit under test: a real [BuiltinToolExecutor] dispatch of the production `terminal`
     * name, whose raw payload is the terminal completion shape.
     */
    private class CountingTerminalTool(private val payload: String) :
        BuiltinTool(), RawJsonBuiltinTool {
        override val name: String = "terminal"

        var invocations: Int = 0
            private set

        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            BuiltinToolResult.success("unused")

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
            invocations++
            return payload
        }
    }
}
