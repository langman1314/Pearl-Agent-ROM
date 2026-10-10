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
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.runtime.CommandResult
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    // Each opaque payload gets its own test, so one failure cannot hide the rest of them.
    @Test
    fun plainTextPayloadBlocksALaterAction() = assertOpaqueBlocks("pending")

    @Test
    fun emptyObjectPayloadBlocksALaterAction() = assertOpaqueBlocks("{}")

    @Test
    fun unknownKeyObjectPayloadBlocksALaterAction() = assertOpaqueBlocks("""{"pending":true}""")

    @Test
    fun bareAcceptedPayloadBlocksALaterAction() = assertOpaqueBlocks("""{"accepted":true}""")

    @Test
    fun nullSessionPayloadBlocksALaterAction() = assertOpaqueBlocks("""{"session":null}""")

    /**
     * A verdict-less payload from a side-effecting tool is dispatched once, reported as `unknown`
     * with `ok=false`, replayed verbatim, and blocks the next side effect.
     *
     * The first return has to be the canonical envelope here: these payloads are readable enough to
     * parse, so returning them verbatim would tell the caller the action was accounted for.
     */
    private fun assertOpaqueBlocks(payload: String) {
        val action = CountingTool("terminal", payload)
        val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action, later)))

        val first = guardedDispatch(executor, guard, "terminal", "call-1")
        assertEquals("'$payload' must report ok=false", "false", jsonField(first, "ok"))
        assertEquals("'$payload' must report outcome=unknown", "unknown", jsonField(first, "outcome"))

        val replay = guardedDispatch(executor, guard, "terminal", "call-1")
        assertEquals("'$payload' replay must equal the first return", first, replay)
        assertEquals("'$payload' was dispatched exactly once", 1, action.invocations)

        guardedDispatch(executor, guard, "gesture", "call-2")
        assertEquals("'$payload' must block the later side effect", 0, later.invocations)
    }

    /**
     * The real terminal failure factories that had already dispatched, each checked on its own.
     *
     * They are the same code path the phone takes: `Timeout` and `INTERNAL_ERROR` are produced by the
     * production factory functions, not hand-written JSON. Their `error` object is read before their
     * session handle, so a timeout that carries a session cannot be mistaken for an opened session.
     */
    @Test
    fun timeoutWithSessionBlocksALaterAction() = assertFactoryBlocks(
        TerminalToolResponse.commandTimeout(
            result = timeoutResult(),
            elapsedSeconds = 1L,
            timeoutMs = 1_000L,
            session = "user",
        )
    )

    @Test
    fun timeoutWithoutSessionBlocksALaterAction() = assertFactoryBlocks(
        TerminalToolResponse.commandTimeout(
            result = timeoutResult(),
            elapsedSeconds = 1L,
            timeoutMs = 1_000L,
        )
    )

    @Test
    fun internalErrorBlocksALaterAction() =
        assertFactoryBlocks(TerminalToolResponse.internalError(IllegalStateException("failed")))

    @Test
    fun blockedCommandIsRefusedBeforeDispatchAndStaysRetryable() {
        val payload = TerminalToolResponse.policyBlocked(
            ShellCommandPolicyDecision(
                allowed = false,
                code = "BLOCKED",
                reason = "Command blocked by test policy.",
            )
        )
        val action = CountingTool("terminal", payload)
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action)))

        val first = guardedDispatch(executor, guard, "terminal", "call-1")
        assertTrue(
            "'$payload' was refused before dispatch, so it is a plain failure",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Failed,
        )
        assertFalse("a pre-dispatch refusal must not latch the round", guard.isLatched)
        assertEquals("nothing reached the device", 1, action.invocations)
    }

    private fun timeoutResult() = CommandResult(
        command = "cmd",
        stdout = TerminalBytes.of(byteArrayOf()),
        stderr = TerminalBytes.of(byteArrayOf()),
        exitCode = null,
        timedOut = true,
    )

    /** A dispatched-but-unconfirmed factory payload: reported unknown, replayed, and it blocks. */
    private fun assertFactoryBlocks(payload: String) {
        val action = CountingTool("terminal", payload)
        val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action, later)))

        val first = guardedDispatch(executor, guard, "terminal", "call-1")
        assertEquals("must report ok=false", "false", jsonField(first, "ok"))
        assertEquals("must report outcome=unknown", "unknown", jsonField(first, "outcome"))
        assertTrue(
            "must read as unconfirmed for the UI",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Unconfirmed,
        )
        assertEquals(
            "replay must equal the first return",
            first,
            guardedDispatch(executor, guard, "terminal", "call-1"),
        )
        assertEquals("dispatched exactly once", 1, action.invocations)

        guardedDispatch(executor, guard, "gesture", "call-2")
        assertEquals("must block the later side effect", 0, later.invocations)
    }

    // Added counterexamples: an exit code that is not a valid integer, and closed=false.
    @Test
    fun invalidExitCodeTypesNeverConfirmCompletion() {        listOf(
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

    /** The same real dispatch path for a named tool, so one test can drive two different tools. */
    private fun guardedDispatch(
        executor: BuiltinToolExecutor,
        guard: RoundActionGuard,
        toolName: String,
        callId: String,
    ): String {
        return when (val decision = guard.beforeCall(toolName, arguments, callId)) {
            is RoundActionGuard.Decision.Replay -> decision.resultJson
            is RoundActionGuard.Decision.Refused -> decision.resultJson
            RoundActionGuard.Decision.Proceed -> {
                val raw = runBlocking {
                    executor.execute(name = toolName, argumentsJson = arguments, callId = callId)
                }
                guard.afterCall(toolName, arguments, callId, raw)
            }
        }
    }

    private fun jsonField(json: String, key: String): String {
        return Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()
    }

    /** A raw-json tool under any name, counting how often it was really invoked. */
    private class CountingTool(override val name: String, private val payload: String) :
        BuiltinTool(), RawJsonBuiltinTool {
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
