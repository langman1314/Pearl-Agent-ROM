package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandPolicyDecision
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where completion may and may not be concluded, checked against the real dispatch path.
 *
 * The defects this pins down are not about a wrong verdict on a *known* payload — they are about
 * what happens when the payload is something nobody enumerated: an error code the four-code table
 * does not list, a byte count that cannot describe a write, or a trace field that belongs to a
 * different tool. Guessing "refused" or "completed" in those cases is what lets a side effect that
 * may already have landed be repeated.
 *
 * Every case drives a real [ToolCallDispatcher] over a counting raw-json tool, so a passing
 * assertion also states the dispatch count. Only the bottom-most action is faked: the guard,
 * the contract and the classifier under test are the production ones.
 */
class Cano81CompletionBoundaryRegressionTest {
    private val arguments = """{"action":"exec","command":"echo harmless"}"""

    // --- 1. An unproven error code is not a pre-dispatch refusal -------------------------------

    @Test
    fun unrecognisedErrorCodeBlocksTheNextSideEffect() =
        assertBlocked("""{"error":{"code":"UNRECOGNIZED_FAILURE"},"session":"user"}""")

    @Test
    fun sshConnectionFailureBlocksTheNextSideEffect() =
        assertBlocked("""{"error":{"code":"SSH_CONNECTION_FAILED"},"session":"user"}""")

    @Test
    fun startupFailureBlocksTheNextSideEffect() =
        assertBlocked("""{"error":{"code":"STARTUP_FAILED"},"identity":"user"}""")

    @Test
    fun authorisationFailureBlocksTheNextSideEffect() =
        assertBlocked("""{"error":{"code":"AUTHORIZATION_FAILED"},"session":"user"}""")

    @Test
    fun malformedErrorCodeTypeBlocksTheNextSideEffect() =
        assertBlocked("""{"error":{"code":{}},"session":"user"}""")

    /** A failure that carries a session handle may well have reached the session it names. */
    @Test
    fun failureCarryingASessionHandleBlocksTheNextSideEffect() =
        assertBlocked(
            """{"session":"user","elapsed_seconds":2,"error":{"code":"BACKEND_UNAVAILABLE"}}"""
        )

    // --- 2. Completion credentials must fit the tool and the operation -------------------------

    @Test
    fun negativeByteCountDoesNotCompleteTheWrite() =
        assertBlocked("""{"accepted":true,"bytes_written":-1,"sequence":1,"replayed":false}""")

    @Test
    fun byteCountFromANonWritingToolDoesNotCompleteTheAction() =
        assertBlocked("""{"bytes_written":0}""")

    @Test
    fun numericSessionHandleIsNotASession() = assertBlocked("""{"session":true}""")

    @Test
    fun numericIdentityHandleIsNotAnIdentity() = assertBlocked("""{"identity":7}""")

    @Test
    fun handleFromTheWrongToolDoesNotCompleteTheAction() {
        // A valid-looking terminal handle reported by a tool that cannot open a terminal.
        val action = CountingTool("node_action", """{"session":"user","identity":"user"}""")
        val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action, later)))

        val first = dispatch(executor, guard, "node_action", "call-1")
        assertEquals("a terminal handle from a node action proves nothing", "unknown", outcomeOf(first))
        assertEquals("dispatched exactly once", 1, action.invocations)

        dispatch(executor, guard, "gesture", "call-2")
        assertEquals("must still block the next side effect", 0, later.invocations)
    }

    // --- 3. A real SSH write receipt proves the write, not the goal ----------------------------

    @Test
    fun sshWriteAcceptanceDoesNotClaimTheCommandFinished() {
        val accepted = """{"accepted":true,"bytes_written":3,"sequence":4,"replayed":false}"""

        assertTrue(
            "accepting input is not the command finishing",
            LocalToolResultClassifier.status(accepted) is LocalToolResultStatus.Unconfirmed,
        )
    }

    @Test
    fun sshWriteAcceptanceStillBlocksAndKeepsItsReceipt() {
        val accepted = """{"accepted":true,"bytes_written":3,"sequence":4,"replayed":false}"""
        val action = CountingTool("ssh_terminal", accepted)
        val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action, later)))

        val first = dispatch(executor, guard, "ssh_terminal", "call-1")
        assertEquals("a write receipt is not a confirmed command", "unknown", outcomeOf(first))
        assertEquals("dispatched exactly once", 1, action.invocations)
        assertEquals("replay must equal the first return", first, dispatch(executor, guard, "ssh_terminal", "call-1"))

        dispatch(executor, guard, "gesture", "call-2")
        assertEquals("must block the next side effect", 0, later.invocations)
    }

    // --- 4. Genuinely undispached refusals stay retryable --------------------------------------

    @Test
    fun blockedCommandStaysRetryable() {
        val refused = TerminalToolResponse.policyBlocked(
            ShellCommandPolicyDecision(
                allowed = false,
                code = "BLOCKED",
                reason = "Command blocked by test policy.",
            )
        )
        val action = CountingTool("terminal", refused)
        val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action, later)))

        val first = dispatch(executor, guard, "terminal", "call-1")
        assertTrue(
            "a policy refusal never ran, so it is an ordinary failure",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Failed,
        )
        assertFalse("a pre-dispatch refusal must not latch the round", guard.isLatched)

        dispatch(executor, guard, "gesture", "call-2")
        assertEquals("a genuinely refused call does not block the next action", 1, later.invocations)
    }

    @Test
    fun missingSessionStaysRetryable() {
        val refused = TerminalToolResponse.sessionNotFound("user")
        val guard = RoundActionGuard()

        val first = dispatch(
            BuiltinToolExecutor(BuiltinToolRegistry(listOf(CountingTool("terminal", refused)))),
            guard,
            "terminal",
            "call-1",
        )
        assertTrue(
            "a missing session never dispatched anything",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Failed,
        )
        assertFalse(guard.isLatched)
    }

    @Test
    fun invalidRequestStaysRetryable() {
        val refused = TerminalToolResponse.invalidRequest("text must not be blank")
        val guard = RoundActionGuard()

        val first = dispatch(
            BuiltinToolExecutor(BuiltinToolRegistry(listOf(CountingTool("ssh_terminal", refused)))),
            guard,
            "ssh_terminal",
            "call-1",
        )
        assertTrue(
            "an invalid request never dispatched anything",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Failed,
        )
        assertFalse(guard.isLatched)
    }

    // --- 5. Real completions keep their exact meaning -----------------------------------------

    @Test
    fun synchronousCompletionStaysASuccess() {
        val payload = """{"exit_code":0,"stdout":"ok","stderr":""}"""
        val action = CountingTool("terminal", payload)
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action)))

        val first = dispatch(executor, guard, "terminal", "call-1")
        assertTrue(
            "a valid integer exit code is a real completion",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Success,
        )
        assertFalse("a completion must not latch the round", guard.isLatched)
        assertEquals("dispatched exactly once", 1, action.invocations)
        assertEquals("replay must equal the first return", first, dispatch(executor, guard, "terminal", "call-1"))
        assertEquals("the same call id must not dispatch again", 1, action.invocations)
    }

    @Test
    fun realSessionOpenStaysASuccess() {
        val opened = TerminalToolResponse.openSuccess(session = "user", identity = "user")
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(
            BuiltinToolRegistry(listOf(CountingTool("terminal", opened)))
        )

        val first = dispatch(executor, guard, "terminal", "call-1")
        assertTrue(
            "opening a session is a completion in its own right",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Success,
        )
        assertFalse(guard.isLatched)
    }

    @Test
    fun realSessionCloseStaysASuccess() {
        val closed = TerminalToolResponse.closeSuccess()
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(
            BuiltinToolRegistry(listOf(CountingTool("terminal", closed)))
        )

        val first = dispatch(executor, guard, "terminal", "call-1")
        assertTrue(
            "closing a session is a completion in its own right",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Success,
        )
        assertFalse(guard.isLatched)
    }

    @Test
    fun nonZeroExitStaysAFailure() {
        val payload = """{"exit_code":1,"stdout":"","stderr":"boom"}"""
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(
            BuiltinToolRegistry(listOf(CountingTool("terminal", payload)))
        )

        val first = dispatch(executor, guard, "terminal", "call-1")
        assertTrue(
            "a non-zero exit code is a decided failure",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Failed,
        )
        assertFalse(guard.isLatched)
    }

    // --- 6. Observation stays readable without unlocking --------------------------------------

    @Test
    fun observationStaysReadableAfterAnUnknownAndDoesNotUnlock() {
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(
            BuiltinToolRegistry(
                listOf(
                    CountingTool("terminal", """{"error":{"code":"UNRECOGNIZED_FAILURE"}}"""),
                    CountingTool("screen_content", "snapshot_id: 42\nroot:\n  - {i: 0, t: button}"),
                )
            )
        )

        dispatch(executor, guard, "terminal", "call-1")
        assertTrue("an unproven failure must latch", guard.isLatched)

        val observation = dispatch(executor, guard, "screen_content", "call-2")
        assertTrue(
            "observation output stays readable",
            LocalToolResultClassifier.status(observation) is LocalToolResultStatus.Success,
        )
        assertTrue("reading the screen must not clear the latch", guard.isLatched)
        assertNotNull(observation)
    }

    /** A dispatched-but-unproven payload: reported unknown once, and the next side effect is refused. */
    private fun assertBlocked(payload: String) {
        val action = CountingTool("terminal", payload)
        val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val guard = RoundActionGuard()
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(action, later)))

        val first = dispatch(executor, guard, "terminal", "call-1")
        assertEquals("'$payload' must report ok=false", "false", jsonField(first, "ok"))
        assertEquals("'$payload' must report outcome=unknown", "unknown", outcomeOf(first))
        assertEquals("dispatched exactly once", 1, action.invocations)
        assertEquals("replay must equal the first return", first, dispatch(executor, guard, "terminal", "call-1"))
        assertEquals("the same call id must not dispatch again", 1, action.invocations)

        dispatch(executor, guard, "gesture", "call-2")
        assertEquals("'$payload' must block the next side effect", 0, later.invocations)
    }

    private fun dispatch(
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

    private fun jsonField(json: String, key: String): String =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()

    private fun outcomeOf(json: String): String = jsonField(json, "outcome")

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
}
