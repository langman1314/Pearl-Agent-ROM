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
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SshTerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end dispatch evidence, driven through the **real** production entry point.
 *
 * Everything here is the real code path: [ToolCallDispatcher.executeLocalTool] -> the real
 * [RoundActionGuard] -> the real [BuiltinToolExecutor] -> the real `terminal` / `ssh_terminal`
 * builtin -> the real response factories and the real [TerminalSessionPool]. The only thing replaced
 * is the lowest-level `TerminalRuntimePort` / `TerminalSessionPort`, installed through the pool's
 * existing test install points, so the counts asserted below are counts of *underlying operations*
 * (`open` / `exec` / `write` / `close` on the fake runtime and session) rather than counts of fake
 * tool entry points.
 *
 * Real-device and framework-node behaviour is **not** covered by this class: no phone is involved, no
 * ADB is used, and nothing here says anything about a real XiaoAi/Breeno host.
 */
class Cano81DispatchEvidenceTest {
    @After
    fun tearDown() {
        runTest {
            TerminalSessionPool.closeAll()
        }
    }

    // --------------------------------------------------- refusals prove nothing was dispatched

    /**
     * A policy-blocked command must not reach the pool at all, and because nothing was dispatched the
     * barrier stays open: the next legal action really runs.
     */
    @Test
    fun policyRefusalNeverReachesThePoolAndTheRoundContinues() = runTest {
        val runtime = FakeRuntime()
        val later = CountingBuiltin("gesture", "tapped")
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(
                terminal = TerminalBuiltin(
                    safetyPolicy = ShellCommandSafetyPolicy(
                        listExecutionRules = { dangerousRules() },
                        isUnlocked = { true },
                    )
                ),
                later = later,
            )

            val blocked = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"open_and_exec","identity":"user","command":"rm -rf /sdcard/x"}""",
                "t-1",
            )

            assertEquals("COMMAND_BLOCKED", errorCode(blocked))
            assertEquals("a blocked command must not open a session", 0, runtime.openCount)
            assertEquals("a blocked command must not execute", 0, runtime.execCount)
            assertEquals(0, later.invocations)

            dispatcher.executeLocalTool("gesture", "{}", "t-2")
            assertEquals("a proven refusal must not block the next action", 1, later.invocations)
        } }
    }

    /** A parse failure is validated before the first pool call, so it provably changed nothing. */
    @Test
    fun argumentValidationNeverReachesThePoolAndTheRoundContinues() = runTest {
        val runtime = FakeRuntime()
        val later = CountingBuiltin("gesture", "tapped")
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal(), later = later)

            val invalid = dispatcher.executeLocalTool("terminal", """{"action":""", "t-1")
            assertEquals("INVALID_REQUEST", errorCode(invalid))
            assertEquals(0, runtime.openCount)

            val missingCommand = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"exec","session":"a3f9"}""",
                "t-2",
            )
            assertEquals("INVALID_REQUEST", errorCode(missingCommand))
            assertEquals(0, runtime.execCount)

            dispatcher.executeLocalTool("gesture", "{}", "t-3")
            assertEquals(1, later.invocations)
        } }
    }

    /**
     * The standalone `exec` lookup failure really is provable: the pool returns before touching any
     * session, so nothing was dispatched and the round continues.
     */
    @Test
    fun standaloneExecSessionNotFoundProvesNothingWasDispatched() = runTest {
        val runtime = FakeRuntime()
        val later = CountingBuiltin("gesture", "tapped")
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal(), later = later)

            val notFound = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"exec","session":"zzzz","command":"pwd"}""",
                "t-1",
            )

            assertEquals("SESSION_NOT_FOUND", errorCode(notFound))
            assertEquals("exec must not open a session", 0, runtime.openCount)
            assertEquals("no command may reach a session", 0, runtime.execCount)

            dispatcher.executeLocalTool("gesture", "{}", "t-2")
            assertEquals(1, later.invocations)
        } }
    }

    // ----------------------------------------- post-dispatch failures are never "retryable"

    /**
     * A throw from the runtime happens *after* the call was handed over. It must be reported unknown
     * and must latch the round no matter which exception class it is — including a plain
     * `IllegalArgumentException`, which a payload-only reading could take for a pre-dispatch
     * rejection.
     */
    @Test
    fun exceptionAfterTheRuntimeWasCalledIsUnknownAndLatchesTheRound() = runTest {
        val runtime = FakeRuntime(openThrowable = IllegalArgumentException("thrown after handover"))
        val later = CountingBuiltin("gesture", "tapped")
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal(), later = later)
            val arguments = """{"action":"open_and_exec","identity":"user","command":"pwd"}"""

            val first = dispatcher.executeLocalTool("terminal", arguments, "t-1")

            assertUnknown(first)
            assertEquals("the composite call reached the runtime once", 1, runtime.openCount)

            val replay = dispatcher.executeLocalTool("terminal", arguments, "t-1")
            assertEquals("replay must be the very same text", first, replay)
            assertEquals("a replayed call id must not dispatch again", 1, runtime.openCount)

            dispatcher.executeLocalTool("gesture", "{}", "t-2")
            assertEquals("an unconfirmed call must block later side effects", 0, later.invocations)
        } }
    }

    /** A command that timed out really did run: exactly one exec, and the result is unknown. */
    @Test
    fun timeoutCountsOneRealExecAndReportsUnknownWhileKeepingDiagnostics() = runTest {
        val runtime = FakeRuntime(execResult = commandResult(stdout = "half", timedOut = true, exitCode = null))
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal())

            val opened = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"open","identity":"user"}""",
                "t-1",
            )
            assertEquals("a3f9", field(opened, "session"))

            val timedOut = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"exec","session":"a3f9","command":"sleep 99"}""",
                "t-2",
            )

            assertUnknown(timedOut)
            assertEquals(1, runtime.execCount)
            assertEquals(
                "a timeout must not discard the partial output",
                "half",
                diagnostics(timedOut)["stdout"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "a timeout must keep its error code readable",
                "TIMEOUT",
                diagnostics(timedOut)["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content,
            )
        } }
    }

    // --------------------------------------------------- real positives stay positive

    @Test
    fun realOpenExecAndClosePositivesKeepTheirVerdictsAndDoNotLatch() = runTest {
        val runtime = FakeRuntime(execResult = commandResult(stdout = "ok\n", exitCode = 0))
        val later = CountingBuiltin("gesture", "tapped")
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal(), later = later)

            val opened = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"open","identity":"user"}""",
                "t-1",
            )
            assertEquals("a3f9", field(opened, "session"))
            assertEquals("user", field(opened, "identity"))
            assertEquals(1, runtime.openCount)

            val executed = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"exec","session":"a3f9","command":"pwd"}""",
                "t-2",
            )
            assertEquals("0", field(executed, "exit_code"))
            assertEquals("ok\n", field(executed, "stdout"))
            assertEquals(1, runtime.execCount)

            val closed = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"close","session":"a3f9"}""",
                "t-3",
            )
            assertEquals("true", field(closed, "closed"))
            assertEquals("the real session must be closed once", 1, runtime.sessionCloseCount)

            dispatcher.executeLocalTool("gesture", "{}", "t-4")
            assertEquals("confirmed calls must not latch the round", 1, later.invocations)
        } }
    }

    /** A non-zero exit is a *definite* failure, not an unconfirmed one. */
    @Test
    fun nonZeroExitIsAConfirmedFailureRatherThanUnknown() = runTest {
        val runtime = FakeRuntime(execResult = commandResult(stdout = "boom", exitCode = 2))
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal())
            dispatcher.executeLocalTool("terminal", """{"action":"open","identity":"user"}""", "t-1")

            val failed = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"exec","session":"a3f9","command":"false"}""",
                "t-2",
            )

            assertEquals("2", field(failed, "exit_code"))
            assertFalse(
                "a definite failure must not be turned into an unknown envelope: $failed",
                field(failed, "outcome") == ToolOutcome.UNKNOWN.wireName,
            )
            assertEquals(1, runtime.execCount)
        } }
    }

    /**
     * `open_and_exec` is one call: the open already happened by the time a later step fails, so that
     * failure must not read as if the whole call had never run.
     */
    @Test
    fun openAndExecKeepsTheEarlierOpenFact() = runTest {
        val runtime = FakeRuntime(
            execFailure = TerminalFailure.RuntimeTerminated(TerminalIdentity.User)
        )
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal())

            val outcome = dispatcher.executeLocalTool(
                "terminal",
                """{"action":"open_and_exec","identity":"user","command":"pwd"}""",
                "t-1",
            )

            assertUnknown(outcome)
            assertEquals("the open really happened", 1, runtime.openCount)
            assertEquals("the exec really happened", 1, runtime.execCount)
            assertEquals(
                "the earlier open fact must stay readable",
                "a3f9",
                diagnostics(outcome)["session"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "the failure code must stay readable",
                "RUNTIME_TERMINATED",
                diagnostics(outcome)["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content,
            )
        } }
    }

    // --------------------------------------------------------------------- ssh: never "done"

    /**
     * An SSH write receipt proves the *write* and nothing about the command it started. Exactly one
     * write reaches the session, the receipt stays readable, and later side effects are refused.
     */
    @Test
    fun sshWriteAcceptanceIsOneWriteUnknownWithReadableReceipt() = runTest {
        val runtime = FakeRuntime()
        val later = CountingBuiltin("gesture", "tapped")
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(ssh = SshTerminalBuiltin(), later = later)

            val opened = dispatcher.executeLocalTool(
                "ssh_terminal",
                """{"action":"open","host":"example.com","username":"alice","password":"secret"}""",
                "s-1",
            )
            assertEquals("a3f9", field(opened, "session"))

            val sent = dispatcher.executeLocalTool(
                "ssh_terminal",
                """{"action":"send_line","session":"a3f9","text":"pwd"}""",
                "s-2",
            )

            assertUnknown(sent)
            assertEquals("exactly one write may reach the session", listOf("pwd\n"), runtime.writes)
            assertEquals(
                "the write receipt must stay readable: $sent",
                "4",
                diagnostics(sent)["bytes_written"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "the write receipt must stay readable: $sent",
                "true",
                diagnostics(sent)["accepted"]?.jsonPrimitive?.content,
            )

            dispatcher.executeLocalTool("gesture", "{}", "s-3")
            assertEquals("an accepted SSH write must block later side effects", 0, later.invocations)
        } }
    }

    @Test
    fun sshInterruptWritesCtrlCOnceAndStaysUnknown() = runTest {
        val runtime = FakeRuntime()
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(ssh = SshTerminalBuiltin())
            dispatcher.executeLocalTool(
                "ssh_terminal",
                """{"action":"open","host":"example.com","username":"alice","password":"secret"}""",
                "s-1",
            )

            val interrupted = dispatcher.executeLocalTool(
                "ssh_terminal",
                """{"action":"interrupt","session":"a3f9"}""",
                "s-2",
            )

            assertUnknown(interrupted)
            assertEquals(listOf("\u0003"), runtime.writes)
        } }
    }

    // ------------------------------------------------- observation after unknown, and replays

    /**
     * After an unknown, observation must still work and must not unlock the barrier; the same call id
     * must replay the exact same text without dispatching anything.
     */
    @Test
    fun observationAfterUnknownStaysAvailableAndDoesNotUnlock() = runTest {
        val runtime = FakeRuntime(execResult = commandResult(timedOut = true, exitCode = null))
        val screen = CountingBuiltin("screen_content", "nodes: []")
        val later = CountingBuiltin("gesture", "tapped")
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(
                terminal = permissiveTerminal(),
                screen = screen,
                later = later,
            )
            val poll = """{"action":"exec","session":"a3f9","command":"sleep 1"}"""

            dispatcher.executeLocalTool("terminal", """{"action":"open","identity":"user"}""", "t-1")
            assertUnknown(dispatcher.executeLocalTool("terminal", poll, "t-2"))

            val observed = dispatcher.executeLocalTool("screen_content", "{}", "t-3")
            assertEquals("observation must still really run", 1, screen.invocations)
            assertEquals(
                "an observation keeps its own ordinary success, not a forced unknown",
                "true",
                field(observed, "ok"),
            )
            assertTrue("the observation payload must be readable: $observed", observed.contains("nodes: []"))

            dispatcher.executeLocalTool("gesture", "{}", "t-4")
            assertEquals("observation must not unlock later side effects", 0, later.invocations)

            assertUnknown(dispatcher.executeLocalTool("terminal", poll, "t-2"))
            assertEquals("a replay must not dispatch", 1, runtime.execCount)
        } }
    }

    /** First return and same-id replay are byte-identical, and the classifier agrees with both. */
    @Test
    fun firstReturnReplayAndClassifierAgree() = runTest {
        val runtime = FakeRuntime(
            openFailure = TerminalFailure.BackendUnavailable(TerminalIdentity.User, "no backend")
        )
        install(runtime).use { installHandles("a3f9").use {
            val dispatcher = dispatcher(terminal = permissiveTerminal())
            val arguments = """{"action":"open_and_exec","identity":"user","command":"pwd"}"""

            val first = dispatcher.executeLocalTool("terminal", arguments, "t-1")
            val replay = dispatcher.executeLocalTool("terminal", arguments, "t-1")

            assertUnknown(first)
            assertEquals(first, replay)
            assertTrue(
                "the classifier must read the canonical envelope as unconfirmed",
                LocalToolResultClassifier.status(first) is LocalToolResultStatus.Unconfirmed,
            )
            assertEquals(1, runtime.openCount)
        } }
    }

    // --------------------------- contract negatives: not currently reachable in production

    /**
     * **Contract negative — not reachable through production tool wiring.**
     *
     * A raw side-effect tool that is *not* wired to the trusted channel and returns a bare session
     * credential under a name that cannot open terminals proves nothing, so it must not be released.
     * No production raw side-effect tool emits this shape; the case is recorded as a contract
     * negative rather than an observed production exit.
     */
    @Test
    fun unwiredRawToolCannotBeReleasedByAForeignSessionCredential() = runTest {
        val unwired = CountingRaw("node_action", """{"session":"a3f9","identity":"user"}""")
        val later = CountingBuiltin("gesture", "tapped")
        val dispatcher = dispatcherWith(unwired, later)

        val first = dispatcher.executeLocalTool("node_action", "{}", "n-1")

        assertUnknown(first)
        assertEquals(1, unwired.invocations)
        dispatcher.executeLocalTool("gesture", "{}", "n-2")
        assertEquals("a foreign credential must not release the barrier", 0, later.invocations)
    }

    /**
     * **Contract negative — not reachable through production tool wiring.**
     *
     * A payload that declares its own `stage`/`operation` in JSON must not be believed: that is
     * self-report, not evidence, and no production tool emits such fields. The claim neither releases
     * the barrier nor counts as a completion.
     */
    @Test
    fun payloadSelfReportedStageIsNotEvidence() = runTest {
        val lying = CountingRaw("node_action", """{"stage":"NOT_DISPATCHED","operation":"tap"}""")
        val later = CountingBuiltin("gesture", "tapped")
        val dispatcher = dispatcherWith(lying, later)

        val first = dispatcher.executeLocalTool("node_action", "{}", "n-1")

        assertUnknown(first)
        assertEquals(1, lying.invocations)
        dispatcher.executeLocalTool("gesture", "{}", "n-2")
        assertEquals("a self-declared stage must not release the barrier", 0, later.invocations)
        assertEquals("the claim must not have been dispatched twice", 1, lying.invocations)
    }

    // ------------------------------------------------------- non-raw tools keep their semantics

    /** Non-raw tools already carry a verdict and must not be pushed into the unknown path. */
    @Test
    fun nonRawToolsKeepTheirOrdinarySemantics() = runTest {
        val ok = CountingBuiltin("notify", "posting")
        val failing = object : BuiltinTool() {
            override val name = "create_custom_tool"

            override fun configure(config: LocalToolConfig) = Unit

            override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
                BuiltinToolResult.failure(
                    code = "MISSING_REQUIRED_FIELD",
                    message = "name is required",
                )
        }
        val dispatcher = dispatcherWith(ok, failing)

        val success = dispatcher.executeLocalTool("notify", "{}", "c-1")
        assertEquals("true", field(success, "ok"))
        assertEquals("success", field(success, "outcome"))
        assertEquals(1, ok.invocations)

        val failure = dispatcher.executeLocalTool("create_custom_tool", "{}", "c-2")
        assertEquals("false", field(failure, "ok"))
        assertEquals("failure", field(failure, "outcome"))
        assertEquals("MISSING_REQUIRED_FIELD", field(failure, "code"))
    }

    // ---------------------------------------------------------------------------------- plumbing

    private fun dispatcher(
        terminal: TerminalBuiltin? = null,
        ssh: SshTerminalBuiltin? = null,
        screen: CountingBuiltin? = null,
        later: CountingBuiltin? = null,
    ): ToolCallDispatcher = dispatcherWith(*listOfNotNull(terminal, ssh, screen, later).toTypedArray())

    private fun dispatcherWith(vararg tools: BuiltinTool): ToolCallDispatcher {
        return ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(tools.toList())),
            customToolExecutor = CustomToolExecutor(),
            actionGuard = RoundActionGuard(),
            currentTools = {
                ResolvedTools(
                    builtinTools = tools.map { LocalTool.Builtin(it.name, it.name, it) },
                )
            },
        )
    }

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

    private fun assertUnknown(json: String) {
        assertEquals("must report ok=false: $json", "false", field(json, "ok"))
        assertEquals(
            "must report outcome=unknown: $json",
            ToolOutcome.UNKNOWN.wireName,
            field(json, "outcome"),
        )
    }

    private fun field(json: String, key: String): String =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()

    private fun errorCode(json: String): String =
        Json.parseToJsonElement(json).jsonObject["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    /** The diagnostics a canonical `unknown` envelope preserves from the raw payload it replaced. */
    private fun diagnostics(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject["data"]?.jsonObject ?: JsonObject(emptyMap())

    private fun dangerousRules() = listOf(
        RuntimeExecutionRule(
            id = "dangerous-command",
            name = "dangerous",
            enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
            patterns = listOf("\\brm\\s+-rf\\b"),
        )
    )

    private class FakeRuntime(
        private val openFailure: TerminalFailure? = null,
        private val openThrowable: Throwable? = null,
        private val execResult: CommandResult = commandResult(),
        private val execFailure: TerminalFailure? = null,
    ) : TerminalRuntimePort {
        var openCount = 0
            private set
        var execCount = 0
            private set
        var sessionCloseCount = 0
            private set
        val writes: MutableList<String> = mutableListOf()

        override suspend fun open(
            identity: TerminalIdentity,
            cwd: String?,
            sshOptions: SshOpenOptions?,
        ): OpenResult<TerminalSessionPort> {
            openCount++
            openThrowable?.let { throw it }
            openFailure?.let { return OpenResult.Failure(it) }
            return OpenResult.Success(
                FakeSession(
                    id = "runtime-$openCount",
                    runtime = this,
                    execResult = execResult,
                    execFailure = execFailure,
                )
            )
        }

        override suspend fun close(sessionId: String) = Unit

        override suspend fun closeAll(): Int = openCount

        fun recordExec() {
            execCount++
        }

        fun recordWrite(text: String) {
            writes.add(text)
        }

        fun recordSessionClose() {
            sessionCloseCount++
        }
    }

    private class FakeSession(
        override val id: String,
        private val runtime: FakeRuntime,
        private val execResult: CommandResult,
        private val execFailure: TerminalFailure?,
    ) : TerminalSessionPort {
        override val stream =
            MutableSharedFlow<TerminalTextChunk>(replay = 16, extraBufferCapacity = 16)

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            runtime.recordExec()
            execFailure?.let { return TermResult.Failure(it) }
            return TermResult.Success(execResult)
        }

        override suspend fun write(text: String) {
            runtime.recordWrite(text)
        }

        override suspend fun close() {
            runtime.recordSessionClose()
        }
    }

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
