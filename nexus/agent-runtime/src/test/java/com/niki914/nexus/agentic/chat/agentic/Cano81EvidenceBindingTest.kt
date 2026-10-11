package com.niki914.nexus.agentic.chat.agentic

import com.niki914.libterm.OpenResult
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.TermResult
import com.niki914.libterm.runtime.TerminalTextChunk
import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.installRuntimeSettingsGatewayForTest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.RawOutcomeProducing
import com.niki914.nexus.agentic.chat.agentic.buildin.RawToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.MemorizeBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.rawOutcome
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CANO-81 定向修正 (1/1): the *source* and *operation* binding of a completion credential.
 *
 * Every case here goes through the real [ToolCallDispatcher] entry point (`executeLocalTool`) so the
 * whole production chain runs: dispatcher -> [BuiltinToolExecutor] -> the tool's real exit ->
 * [RoundActionGuard] -> the string the caller receives.
 *
 * The rule these cases defend: a *completion credential* is only a credential for the tool and the
 * operation that emit it, and a raw result that nothing vouched for can never be released, whatever
 * its payload happens to look like. A payload is not evidence — the branch that produced it is.
 *
 * The synthetic raw tools below stand in for the interface-level counter-examples the review asked
 * to see listed separately: they count *tool entry-point invocations* and *runtime calls*. Those
 * numbers prove interface reachability, never that a phone was untouched.
 *
 * This class compiles against the pre-fix revision on purpose: it uses only interfaces that already
 * exist (`RawJsonBuiltinTool`, `RawOutcomeProducing`, `rawOutcome`, the pool's test install points).
 * Cases that need the newly added binding type live in `Cano81OperationBindingTest` and are marked
 * 修后新增 there — they do not claim to have run against the old revision.
 */
class Cano81EvidenceBindingTest {
    @After
    fun tearDown() {
        runTest { TerminalSessionPool.closeAll() }
        RuntimeEnvironment.clearForTest()
    }

    // ------------------------------------------------------- no-evidence raw side effects (§ source)

    /**
     * A raw side effect whose payload is a whitelisted *refusal* shape. Nothing vouched for this
     * exit, so the refusal proves nothing: releasing it would let a caller retry an action that may
     * already have landed.
     */
    @Test
    fun rawSideEffectWithWhitelistedRefusalShapeIsNeverReleased() = runTest {
        val payloads = listOf(
            """{"error":{"code":"INVALID_REQUEST","message":"Field 'text' must not be blank."}}""",
            """{"error":{"code":"COMMAND_BLOCKED","message":"blocked by policy"}}""",
            """{"error":{"code":"SESSION_NOT_FOUND","message":"no such session"}}""",
            """{"error":{"code":"ASYNC_NOT_FOUND","message":"no such async id"}}""",
            """{"error":{"code":"SESSION_BUSY","message":"busy"}}""",
        )
        for (payload in payloads) {
            val action = CountingRaw("node_action", payload)
            val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(action, later)

            val first = dispatcher.executeLocalTool("node_action", "{}", callId = "action-1")
            assertUnknown("'$payload' must be unconfirmed: $first", first)
            assertEquals(
                "'$payload' replay must equal the first return",
                first,
                dispatcher.executeLocalTool("node_action", "{}", callId = "action-1"),
            )
            assertEquals("'$payload' must be invoked exactly once", 1, action.invocations)

            dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
            assertEquals("'$payload' must block the next side effect", 0, later.invocations)
        }
    }

    /** A raw side effect printing a *fake* synchronous completion: an exit code nobody proved. */
    @Test
    fun rawSideEffectPrintingAnExitCodeIsNeverReleased() = runTest {
        val payloads = listOf(
            """{"exit_code":0,"stdout":"done"}""",
            """{"exit_code":0,"stdout":"done","session":"a3f9","identity":"user"}""",
        )
        for (payload in payloads) {
            val action = CountingRaw("terminal", payload)
            val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(action, later)

            val first = dispatcher.executeLocalTool("terminal", "{}", callId = "terminal-1")
            assertUnknown("'$payload' must be unconfirmed: $first", first)
            assertEquals(first, dispatcher.executeLocalTool("terminal", "{}", callId = "terminal-1"))
            assertEquals(1, action.invocations)

            dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
            assertEquals("'$payload' must block the next side effect", 0, later.invocations)
        }
    }

    /** A raw side effect printing a *fake* close receipt. */
    @Test
    fun rawSideEffectPrintingACloseReceiptIsNeverReleased() = runTest {
        val payloads = listOf(
            """{"closed":true}""",
            """{"closed":true,"session":"a3f9"}""",
        )
        for (payload in payloads) {
            val action = CountingRaw("terminal", payload)
            val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(action, later)

            val first = dispatcher.executeLocalTool("terminal", "{}", callId = "close-1")
            assertUnknown("'$payload' must be unconfirmed: $first", first)
            assertEquals(first, dispatcher.executeLocalTool("terminal", "{}", callId = "close-1"))
            assertEquals(1, action.invocations)

            dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
            assertEquals("'$payload' must block the next side effect", 0, later.invocations)
        }
    }

    /**
     * The sharpest case: a raw tool that prints a *canonical success envelope*. Byte for byte it is
     * indistinguishable from a non-raw tool's verdict, which is exactly why the payload can never be
     * the thing that decides — the branch that produced it must say so.
     */
    @Test
    fun rawSideEffectPrintingASuccessEnvelopeIsNeverReleased() = runTest {
        val payloads = listOf(
            BuiltinToolResult.success("done").toJsonString(),
            """{"ok":true,"outcome":"success","code":"OK","message":"done"}""",
        )
        for (payload in payloads) {
            val action = CountingRaw("terminal", payload)
            val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(action, later)

            val first = dispatcher.executeLocalTool("terminal", "{}", callId = "terminal-1")
            assertUnknown("'$payload' must be unconfirmed: $first", first)
            assertEquals(first, dispatcher.executeLocalTool("terminal", "{}", callId = "terminal-1"))
            assertEquals(1, action.invocations)

            dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
            assertEquals("'$payload' must block the next side effect", 0, later.invocations)
        }
    }

    /**
     * A foreign session handle next to a whitelisted refusal code, printed by a tool that cannot
     * emit either. Both the credential and the code are self-report.
     */
    @Test
    fun rawSideEffectWithForeignCredentialAndRefusalCodeIsNeverReleased() = runTest {
        val payloads = listOf(
            """{"session":"a3f9","identity":"user","error":{"code":"SESSION_NOT_FOUND","message":"gone"}}""",
            """{"session":"a3f9","identity":"user"}""",
        )
        for (payload in payloads) {
            val action = CountingRaw("node_action", payload)
            val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(action, later)

            val first = dispatcher.executeLocalTool("node_action", "{}", callId = "action-1")
            assertUnknown("'$payload' must be unconfirmed: $first", first)
            assertEquals(first, dispatcher.executeLocalTool("node_action", "{}", callId = "action-1"))
            assertEquals(1, action.invocations)

            dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
            assertEquals("'$payload' must block the next side effect", 0, later.invocations)
        }
    }

    // ------------------------------------------- credential bound to the operation that emits it

    /**
     * An exit that claims the `exec` operation may not be released by an *open* handle: a session
     * handle proves a terminal was opened, never that a command finished.
     */
    @Test
    fun sessionHandleCannotCompleteAnExecOperation() = runTest {
        val action = CountingEvidenceRaw(
            name = "terminal",
            payload = """{"session":"a3f9","identity":"user"}""",
            operation = "exec",
        )
        val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)

        val first = dispatcher.executeLocalTool("terminal", execArguments(), callId = "terminal-1")
        assertUnknown("an open handle must not complete 'exec': $first", first)
        assertEquals(first, dispatcher.executeLocalTool("terminal", execArguments(), callId = "terminal-1"))
        assertEquals(1, action.invocations)

        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("an unproven 'exec' must block the next side effect", 0, later.invocations)
    }

    /** An exit that claims the `close` operation may not be released by an *exec* exit code. */
    @Test
    fun exitCodeCannotCompleteACloseOperation() = runTest {
        val action = CountingEvidenceRaw(
            name = "terminal",
            payload = """{"exit_code":0,"stdout":"done"}""",
            operation = "close",
        )
        val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)

        val first = dispatcher.executeLocalTool("terminal", """{"action":"close","session":"a3f9"}""", callId = "close-1")
        assertUnknown("an exit code must not complete 'close': $first", first)
        assertEquals(first, dispatcher.executeLocalTool("terminal", """{"action":"close","session":"a3f9"}""", callId = "close-1"))
        assertEquals(1, action.invocations)

        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("an unproven 'close' must block the next side effect", 0, later.invocations)
    }

    /**
     * The positive control for the two cases above: the same operation, with the credential that
     * operation really emits, is still a decided result and must not latch the round.
     */
    @Test
    fun theCredentialOfTheSameOperationStillCompletes() = runTest {
        val opened = CountingEvidenceRaw(
            name = "terminal",
            payload = """{"session":"a3f9","identity":"user"}""",
            operation = "open",
        )
        val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(opened, later)

        val result = dispatcher.executeLocalTool("terminal", """{"action":"open","identity":"user"}""", callId = "open-1")

        // A released raw result is handed back as the tool wrote it, so the handle — not an
        // envelope field — is what proves this operation's credential survived.
        assertTrue("an open handle still completes 'open': $result", result.contains("a3f9"))
        // A released result is handed back exactly as the tool wrote it, so the credential the
        // operation really emits survives the guard untouched instead of being rewritten.
        assertEquals(
            "a released raw result must not be rewritten by the guard",
            """{"session":"a3f9","identity":"user"}""",
            result,
        )
        assertFalse(
            "a confirmed open must not latch the round: $result",
            LocalToolResultClassifier.status(result) is LocalToolResultStatus.Unconfirmed,
        )
        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("a confirmed open must not block the next side effect", 1, later.invocations)
    }

    // ------------------------------------------------------------------- semantics that must not move

    /** A real `memorize` success is a raw-with-evidence result and keeps its verdict and its data. */
    @Test
    fun realMemorizeSuccessKeepsItsVerdictThroughTheRealDispatcher() = runTest {
        installRuntimeSettingsGatewayForTest()
        val action = MemorizeBuiltin()
        val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)

        val result = dispatcher.executeLocalTool(
            "memorize",
            """{"content":"The user prefers concise answers."}""",
            callId = "m-1",
        )

        assertEquals("memorize must stay a success: $result", "true", field(result, "ok"))
        assertFalse(
            "a real memorize success must not read as unconfirmed: $result",
            LocalToolResultClassifier.status(result) is LocalToolResultStatus.Unconfirmed,
        )
        assertEquals("the confirmed write must not be replayed", result, dispatcher.executeLocalTool(
            "memorize",
            """{"content":"The user prefers concise answers."}""",
            callId = "m-1",
        ))
        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("a confirmed memorize must not block the next side effect", 1, later.invocations)
    }

    /** A real `memorize` validation refusal proves nothing was written, so it stays retryable. */
    @Test
    fun realMemorizeValidationRefusalStaysRetryable() = runTest {
        installRuntimeSettingsGatewayForTest()
        val action = MemorizeBuiltin()
        val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)

        val result = dispatcher.executeLocalTool(
            "memorize",
            """{"content":"   "}""",
            callId = "m-blank",
        )

        assertFalse(
            "a blank-content refusal never wrote anything, so it stays an ordinary failure: $result",
            LocalToolResultClassifier.status(result) is LocalToolResultStatus.Unconfirmed,
        )
        assertEquals("false", field(result, "ok"))
        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("a proven refusal must not block the next side effect", 1, later.invocations)
    }

    /**
     * Reading the screen is how the agent recovers: it must stay readable and available even after
     * an unconfirmed side effect, while still not unlocking the barrier.
     */
    @Test
    fun observationStaysReadableAfterARawSideEffectButDoesNotUnlockIt() = runTest {
        val action = CountingRaw("terminal", """{"exit_code":0,"stdout":"done"}""")
        val screen = CountingBuiltin("screen_content", "nodes: []")
        val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, screen, later)

        val first = dispatcher.executeLocalTool("terminal", "{}", callId = "terminal-1")
        assertUnknown("a raw exit code must be unconfirmed: $first", first)

        val observed = dispatcher.executeLocalTool("screen_content", "{}", callId = "screen-1")
        assertEquals("observation must still really run", 1, screen.invocations)
        assertEquals("observation must stay readable: $observed", "true", field(observed, "ok"))
        assertTrue("the observation payload must survive: $observed", observed.contains("nodes: []"))
        assertFalse(
            "observation must not be reported as unconfirmed: $observed",
            LocalToolResultClassifier.status(observed) is LocalToolResultStatus.Unconfirmed,
        )

        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("observation must not unlock later side effects", 0, later.invocations)
    }

    // --------------------------------------------------------------------- real-path gap filling

    /**
     * Cancellation after the request was already dispatched. The caller gets no normal return — a
     * cancelled first call has no string to compare — so what is asserted is the device-side fact:
     * no later call reaches the runtime, and observation is still allowed.
     */
    @Test
    fun cancellationAfterDispatchStopsLaterRuntimeCallsWithoutBlindingObservation() = runTest {
        val runtime = FakeRuntime(execThrowable = CancellationException("cancelled in flight"))
        val screen = CountingBuiltin("screen_content", "nodes: []")
        install(runtime).use {
            installHandles("a3f9").use {
                val dispatcher = dispatcher(permissiveTerminal(), screen)
                val opened = dispatcher.executeLocalTool(
                    "terminal",
                    """{"action":"open","identity":"user"}""",
                    callId = "t-open",
                )
                assertEquals("a3f9", field(opened, "session"))
                assertEquals(1, runtime.openCount)

                val cancelled = try {
                    dispatcher.executeLocalTool("terminal", execArguments(), callId = "t-exec-1")
                    null
                } catch (expected: CancellationException) {
                    expected
                }
                assertTrue("cancellation must reach the caller", cancelled is CancellationException)
                assertEquals("the exec was dispatched exactly once", 1, runtime.execCount)

                // A cancelled call left no verdict: a fresh call id must not reach the runtime again.
                dispatcher.executeLocalTool("terminal", execArguments(), callId = "t-exec-2")
                assertEquals("no later exec may reach the runtime", 1, runtime.execCount)

                val observed = dispatcher.executeLocalTool("screen_content", "{}", callId = "screen-1")
                assertEquals("observation must still run", 1, screen.invocations)
                assertEquals("observation must stay readable: $observed", "true", field(observed, "ok"))
            }
        }
    }

    // Precise uncovered point, stated rather than papered over: the pool's *compensating close*
    // branch (`TerminalSessionPool.openSession`, the path where a handle was taken by someone else
    // between the availability check and the registration, so the session just opened is closed
    // again) is only reachable through a check-then-register race. The pool's deterministic
    // injection point, `generateAvailablePublicHandle`, refuses an occupied handle *before*
    // `openSession` is entered, and `ToolCallDispatcher` serialises calls under its execution
    // mutex, so a dispatcher-level case cannot isolate that branch without changing the pool
    // algorithm — which is out of scope for this correction. The branch is therefore asserted only
    // at its observable consequence below (a pool-side open rejection before any session exists),
    // and the compensating-close branch itself is listed as uncovered in the handed-over report.

    /**
     * A pool-side open rejection seen through the whole dispatcher: when the handle generator can no
     * longer produce a handle, the pool rejects the open before any session exists, and the tool
     * still reports unconfirmed rather than a retryable failure.
     */
    @Test
    fun poolSideOpenRejectionIsUnconfirmedAndLatchesTheRound() = runTest {
        val runtime = FakeRuntime()
        install(runtime).use {
            installHandles().use {
                val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
                val dispatcher = dispatcher(permissiveTerminal(), later)

                val first = dispatcher.executeLocalTool(
                    "terminal",
                    """{"action":"open","identity":"user"}""",
                    callId = "open-1",
                )
                assertUnknown("a pool-side rejection must stay unconfirmed: $first", first)
                assertTrue(
                    "the pool's own reason must survive as a diagnostic: $first",
                    first.contains("handle"),
                )
                assertEquals("no session may be opened without a handle", 0, runtime.openCount)
                assertTrue(
                    "a rejected open must not compensating-close anything",
                    runtime.runtimeCloseIds.isEmpty(),
                )
                assertEquals(first, dispatcher.executeLocalTool("terminal", """{"action":"open","identity":"user"}""", callId = "open-1"))

                dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
                assertEquals("an unconfirmed open must block the next side effect", 0, later.invocations)
            }
        }
    }

    // ---------------------------------------------------------------------------------- plumbing

    private fun dispatcher(vararg tools: BuiltinTool): ToolCallDispatcher {
        return ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(tools.toList())),
            customToolExecutor = CustomToolExecutor(),
            actionGuard = RoundActionGuard(),
            currentTools = {
                ResolvedTools(builtinTools = tools.map { LocalTool.Builtin(it.name, it.name, it) })
            },
        )
    }

    private fun execArguments(): String =
        """{"action":"exec","session":"a3f9","command":"echo harmless"}"""

    /** A terminal whose policy really runs but blocks nothing, so no settings gateway is needed. */
    private fun permissiveTerminal(): TerminalBuiltin = TerminalBuiltin(
        safetyPolicy = ShellCommandSafetyPolicy(
            listExecutionRules = { emptyList() },
            isUnlocked = { true },
        )
    )

    private fun install(runtime: FakeRuntime): AutoCloseable {
        return TerminalSessionPool.installRuntimePortFactoryForTest { runtime }
    }

    private fun installHandles(vararg handles: String): AutoCloseable {
        val iterator = handles.iterator()
        return TerminalSessionPool.installHandleGeneratorForTest {
            check(iterator.hasNext()) { "No fake terminal handles left." }
            iterator.next()
        }
    }

    private fun assertUnknown(message: String, json: String) {
        assertEquals("$message (ok)", "false", field(json, "ok"))
        assertEquals(
            "$message (outcome)",
            ToolOutcome.UNKNOWN.wireName,
            field(json, "outcome"),
        )
        assertTrue(
            "$message (classifier)",
            LocalToolResultClassifier.status(json) is LocalToolResultStatus.Unconfirmed,
        )
    }

    private fun field(json: String, key: String): String =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()

    /** A raw side-effect tool deliberately *not* wired to the trusted evidence channel. */
    private class CountingRaw(override val name: String, private val payload: String) :
        BuiltinTool(), RawJsonBuiltinTool {
        var invocations: Int = 0
            private set

        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            error("raw only")

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
            invocations++
            return payload
        }
    }

    /**
     * A raw tool that *does* report its stage and operation, so the operation binding can be
     * exercised without the new declared-operation type. It counts entry-point invocations only.
     */
    private class CountingEvidenceRaw(
        override val name: String,
        private val payload: String,
        private val operation: String,
    ) : BuiltinTool(), RawJsonBuiltinTool, RawOutcomeProducing {
        var invocations: Int = 0
            private set

        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            error("raw only")

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
            invocations++
            return payload
        }

        override suspend fun invokeRawJsonWithEvidence(request: BuiltinToolRequest): RawToolOutcome {
            invocations++
            return rawOutcome(
                operation = operation,
                stage = DispatchStage.DISPATCHED_CONFIRMED,
                json = payload,
            )
        }
    }

    /** An ordinary non-raw tool, counting how often it really ran. */
    private class CountingBuiltin(override val name: String, private val output: String) :
        BuiltinTool() {
        var invocations: Int = 0
            private set

        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            invocations++
            return BuiltinToolResult.success(output)
        }
    }

    private class FakeRuntime(
        private val execThrowable: Throwable? = null,
        private val execResult: CommandResult = commandResult(),
    ) : TerminalRuntimePort {
        var openCount = 0
            private set
        var execCount = 0
            private set
        val runtimeCloseIds: MutableList<String> = mutableListOf()

        override suspend fun open(
            identity: TerminalIdentity,
            cwd: String?,
            sshOptions: SshOpenOptions?,
        ): OpenResult<TerminalSessionPort> {
            openCount++
            return OpenResult.Success(
                FakeSession(
                    id = "runtime-$openCount",
                    runtime = this,
                    execThrowable = execThrowable,
                    execResult = execResult,
                )
            )
        }

        override suspend fun close(sessionId: String) {
            runtimeCloseIds.add(sessionId)
        }

        override suspend fun closeAll(): Int = openCount

        suspend fun recordExec() {
            execCount++
        }
    }

    private class FakeSession(
        override val id: String,
        private val runtime: FakeRuntime,
        private val execThrowable: Throwable?,
        private val execResult: CommandResult,
    ) : TerminalSessionPort {
        override val stream =
            MutableSharedFlow<TerminalTextChunk>(replay = 16, extraBufferCapacity = 16)

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            runtime.recordExec()
            execThrowable?.let { throw it }
            return TermResult.Success(execResult)
        }

        override suspend fun write(text: String) = Unit

        override suspend fun close() = Unit
    }

    private companion object {
        fun commandResult(
            stdout: String = "ok\n",
            stderr: String = "",
            exitCode: Int? = 0,
            timedOut: Boolean = false,
        ): CommandResult {
            return CommandResult(
                command = "cmd",
                stdout = TerminalBytes.of(stdout.encodeToByteArray()),
                stderr = TerminalBytes.of(stderr.encodeToByteArray()),
                exitCode = exitCode,
                timedOut = timedOut,
            )
        }
    }
}
