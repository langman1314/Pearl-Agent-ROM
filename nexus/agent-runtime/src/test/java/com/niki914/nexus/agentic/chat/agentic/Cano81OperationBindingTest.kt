package com.niki914.nexus.agentic.chat.agentic

import com.niki914.libterm.OpenResult
import com.niki914.libterm.SshOpenOptions
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
import com.niki914.nexus.agentic.chat.agentic.buildin.DeclaredOperationProducing
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.RawOutcomeProducing
import com.niki914.nexus.agentic.chat.agentic.buildin.RawToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SshTerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.rawOutcome
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * CANO-81 定向修正 —— 修后新增的 operation binding 覆盖（本轮新增，未在旧版本上运行过）。
 *
 * The *declared* operation is the one the request asked for, established by the tool's own argument
 * rules before the call runs. These cases prove that the production wiring really is connected —
 * that [com.niki914.nexus.agentic.chat.agentic.ToolCallDispatcher] asks the real tool, and that the
 * real [TerminalBuiltin] / [SshTerminalBuiltin] answer with the operation the schema allows rather
 * than with something the exit reports about itself.
 *
 * Everything asserted here is an interface-level fact: which operation a tool declares for a given
 * request, and how a completion credential is judged against it. No claim is made about a real
 * device.
 */
class Cano81OperationBindingTest {

    @After
    fun tearDown() = runTest {
        // The pool is a process-wide singleton, so a session left behind here would leak into the
        // other classes in this same JVM.
        TerminalSessionPool.closeAll()
    }

    // ----------------------------------------------------- the real tools declare their operation

    @Test
    fun terminalDeclaresTheOperationItsOwnSchemaAccepts() {
        val terminal = TerminalBuiltin()

        assertEquals("open", terminal.declaredOperation("""{"action":"open","identity":"user"}"""))
        assertEquals(
            "open_and_exec",
            terminal.declaredOperation("""{"action":"open_and_exec","identity":"user","command":"pwd"}"""),
        )
        assertEquals(
            "exec",
            terminal.declaredOperation("""{"action":"exec","session":"a3f9","command":"pwd"}"""),
        )
        assertEquals(
            "read_async_result",
            terminal.declaredOperation("""{"action":"read_async_result","async_id":"7"}"""),
        )
        assertEquals("close", terminal.declaredOperation("""{"action":"close","session":"a3f9"}"""))

        // The handler's own tolerance is the tolerance here: same casing, same surrounding space.
        assertEquals("exec", terminal.declaredOperation("""{"action":"  EXEC  ","session":"a3f9","command":"pwd"}"""))
    }

    @Test
    fun sshTerminalDeclaresTheOperationItsOwnSchemaAccepts() {
        val ssh = SshTerminalBuiltin()

        assertEquals(
            "open",
            ssh.declaredOperation("""{"action":"open","host":"10.0.0.1","username":"root","password":"x"}"""),
        )
        assertEquals("send_line", ssh.declaredOperation("""{"action":"send_line","session":"a3f9","text":"pwd"}"""))
        assertEquals("write", ssh.declaredOperation("""{"action":"write","session":"a3f9","text":"pwd"}"""))
        assertEquals("interrupt", ssh.declaredOperation("""{"action":"interrupt","session":"a3f9"}"""))
        assertEquals("read", ssh.declaredOperation("""{"action":"read","session":"a3f9"}"""))
        assertEquals("close", ssh.declaredOperation("""{"action":"close","session":"a3f9"}"""))
    }

    /**
     * A request this version cannot read establishes *no* operation. That is the release valve: an
     * unparseable request must not become a false `unknown` for a call that really completed, and it
     * must not silently inherit some other operation either.
     */
    @Test
    fun anUnreadableOrOperationlessRequestDeclaresNothing() {
        val terminal = TerminalBuiltin()
        val ssh = SshTerminalBuiltin()

        assertNull("no action at all", terminal.declaredOperation("""{"identity":"user"}"""))
        assertNull("unknown action value", terminal.declaredOperation("""{"action":"teleport"}"""))
        assertNull("blank action", terminal.declaredOperation("""{"action":"   "}"""))
        assertNull("action of the wrong type", terminal.declaredOperation("""{"action":7}"""))
        assertNull("not JSON at all", terminal.declaredOperation("not json"))
        assertNull("JSON that is not an object", terminal.declaredOperation("[]"))
        assertNull("a key the schema does not allow", terminal.declaredOperation("""{"action":"exec","nope":1}"""))
        assertNull("ssh has no exec", ssh.declaredOperation("""{"action":"exec","session":"a3f9"}"""))
        assertNull("ssh without an action", ssh.declaredOperation("""{"session":"a3f9"}"""))
    }

    /** The declared operation must not depend on side effects: asking twice answers the same. */
    @Test
    fun declaringAnOperationNeverTouchesTheTool() {
        val terminal = TerminalBuiltin()
        repeat(3) {
            assertEquals("exec", terminal.declaredOperation("""{"action":"exec","session":"a3f9","command":"pwd"}"""))
        }
    }

    // ----------------------------------------- interface-level reachability of the release valves

    /**
     * The dispatcher asks the tool that is *really* about to run, so a tool which declares no
     * operation is judged with no constraint: its raw completion still releases the barrier. This is
     * the interface-level counterpart of the real-terminal positives.
     */
    @Test
    fun aToolThatDeclaresNothingIsNotConstrained() = runTest {
        val action = DeclaringRaw(
            name = "terminal",
            payload = """{"exit_code":0,"stdout":"done"}""",
            declares = null,
        )
        val later = RawTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)

        val first = dispatcher.executeLocalTool("terminal", """{"action":"exec","session":"a3f9","command":"pwd"}""", callId = "t-1")

        assertEquals(
            "a result whose operation was never established keeps its own text",
            """{"exit_code":0,"stdout":"done"}""",
            first,
        )
        assertFalse(
            "a released completion must not be reported as unconfirmed: $first",
            LocalToolResultClassifier.status(first) is LocalToolResultStatus.Unconfirmed,
        )
        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("a released completion must not block the next side effect", 1, later.invocations)
    }

    /**
     * The other half of the same wiring: when the tool *does* declare an operation, the dispatcher
     * passes it through and the credential is judged against it. `open` cannot be completed by an
     * exit code, so this raw completion is reported as unconfirmed and the round stops.
     */
    @Test
    fun theDeclaredOperationIsWhatJudgesTheCredential() = runTest {
        val action = DeclaringRaw(
            name = "terminal",
            payload = """{"exit_code":0,"stdout":"done"}""",
            declares = "open",
        )
        val later = RawTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)

        val first = dispatcher.executeLocalTool("terminal", """{"action":"open","identity":"user"}""", callId = "t-1")

        assertUnconfirmed("an exit code cannot complete 'open': $first", first)
        assertEquals("the same call id replays identically", first, dispatcher.executeLocalTool("terminal", """{"action":"open","identity":"user"}""", callId = "t-1"))
        assertEquals("the exit ran exactly once", 1, action.invocations)

        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("an unproven 'open' must block the next side effect", 0, later.invocations)
    }

    /**
     * A declared operation is a *request*-side fact, so it can never be supplied by the result: a
     * tool whose exit claims `open` while the request declared `exec` is judged as `exec`. Here the
     * request declares nothing recognisable while the exit claims `exec` — the payload's own claim
     * must not be what releases it.
     */
    @Test
    fun theExitCannotSupplyTheOperationThatReleasesIt() = runTest {
        val action = DeclaringRaw(
            name = "terminal",
            payload = """{"exit_code":0,"stdout":"done"}""",
            declares = null,
            reportsOperation = "exec",
        )
        val later = RawTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)

        // The request's own `action` is unreadable, so nothing was declared: the release valve for
        // "no operation established" applies, and the raw completion keeps its text.
        val first = dispatcher.executeLocalTool("terminal", """{"identity":"user"}""", callId = "t-1")
        assertEquals("""{"exit_code":0,"stdout":"done"}""", first)

        dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
        assertEquals("a released completion must not block the next side effect", 1, later.invocations)
    }

    /**
     * The pool's *compensating close*: the branch where a handle that looked free is taken by
     * someone else between the availability check and the registration, so the session this call
     * just opened is closed again rather than leaked.
     *
     * This is reachable without changing the pool algorithm, because the pool's own injection seams
     * (`installHandleGeneratorForTest`, `installRuntimePortFactoryForTest`) are enough to place the
     * two calls in the exact interleaving: the fake runtime holds the first `open` inside the await
     * point while the second call runs to completion, registering the same handle first.
     *
     * What is asserted is the device-side fact, not an internal counter: the loser's runtime session
     * is really closed, the winner's is *not*, and the loser retries onto a genuinely fresh handle
     * instead of reporting success for a session it no longer owns.
     */
    @Test
    fun aHandleTakenDuringOpenIsCompensatingClosedAndRetriedOnAFreshHandle() = runTest {
        val runtime = RacingRuntime()
        TerminalSessionPool.installRuntimePortFactoryForTest { runtime }.use {
            // Two calls race for "a3f9"; only after both are spent does a fresh handle appear.
            val handles = arrayOf("a3f9", "a3f9", "b7c2")
            val nextHandle = AtomicInteger(0)
            TerminalSessionPool.installHandleGeneratorForTest {
                handles[nextHandle.getAndIncrement().coerceAtMost(handles.lastIndex)]
            }.use {
                val first = async(Dispatchers.Default) { TerminalSessionPool.open(identity = "user") }
                runtime.awaitFirstOpenEntered()

                // The second call now runs to completion and claims the handle first.
                val second = async(Dispatchers.Default) { TerminalSessionPool.open(identity = "root") }
                val secondOutcome = second.await()
                assertTrue(
                    "the second call must win the handle: $secondOutcome",
                    secondOutcome is TerminalOpenOutcome.Success,
                )
                assertEquals("a3f9", (secondOutcome as TerminalOpenOutcome.Success).session)
                assertEquals(
                    "'root' maps to the superuser terminal identity",
                    TerminalIdentity.Su,
                    runtime.identities[1],
                )

                // Release the first call: it must notice the handle was taken under it.
                runtime.releaseFirstOpen()
                val firstOutcome = first.await()

                assertTrue(
                    "the first call must not report a session it does not own: $firstOutcome",
                    firstOutcome is TerminalOpenOutcome.Success,
                )
                assertEquals(
                    "the loser must land on a different handle",
                    "b7c2",
                    (firstOutcome as TerminalOpenOutcome.Success).session,
                )

                assertEquals(
                    "the orphaned session must be closed exactly once, and only that one",
                    listOf("runtime-1"),
                    runtime.runtimeCloseIds,
                )
                assertEquals(
                    "the winner's live session must survive the loser's compensating close",
                    "runtime-2",
                    TerminalSessionPool.get("a3f9")?.libTermSessionId,
                )
                assertEquals(
                    "the loser's retry must be registered under its own new handle",
                    "runtime-3",
                    TerminalSessionPool.get("b7c2")?.libTermSessionId,
                )
            }
        }
    }

    // ------------------------------------------------------------------------------- plumbing

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

    private fun assertUnconfirmed(message: String, json: String) {
        assertEquals("$message (ok)", "false", field(json, "ok"))
        assertEquals("$message (outcome)", ToolOutcome.UNKNOWN.wireName, field(json, "outcome"))
        assertTrue(
            "$message (classifier)",
            LocalToolResultClassifier.status(json) is LocalToolResultStatus.Unconfirmed,
        )
        assertFalse(
            "$message must not be reported as a success",
            field(json, "ok") == "true",
        )
    }

    private fun field(json: String, key: String): String =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()

    /**
     * A raw tool that goes through the evidence channel and declares an operation, so the
     * dispatcher's `DeclaredOperationProducing` path can be exercised without a real device. It
     * counts entry-point invocations only — that is an interface fact, not proof about a phone.
     */
    private class DeclaringRaw(
        override val name: String,
        private val payload: String,
        private val declares: String?,
        private val reportsOperation: String = declares ?: "unknown",
    ) : BuiltinTool(), RawJsonBuiltinTool, RawOutcomeProducing, DeclaredOperationProducing {
        var invocations: Int = 0
            private set

        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult = error("raw only")

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String = payload

        override fun declaredOperation(argumentsJson: String): String? = declares

        override suspend fun invokeRawJsonWithEvidence(request: BuiltinToolRequest): RawToolOutcome {
            invocations++
            return rawOutcome(
                operation = reportsOperation,
                stage = DispatchStage.DISPATCHED_CONFIRMED,
                json = payload,
            )
        }
    }

    /** A raw side-effect tool deliberately *not* wired to the trusted evidence channel. */
    private class RawTool(override val name: String, private val payload: String) :
        BuiltinTool(), RawJsonBuiltinTool {
        var invocations: Int = 0
            private set

        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult = error("raw only")

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
            invocations++
            return payload
        }
    }

    /**
     * A runtime whose *first* `open` parks inside the await point until the test releases it, so the
     * check-then-register window in `TerminalSessionPool.openSession` can be hit deterministically
     * rather than by luck. Later opens complete immediately.
     */
    private class RacingRuntime : TerminalRuntimePort {
        val identities: MutableList<TerminalIdentity> = mutableListOf()
        val runtimeCloseIds: MutableList<String> = mutableListOf()
        private var openCount = 0
        private val firstOpenEntered = CompletableDeferred<Unit>()
        private val firstOpenRelease = CompletableDeferred<Unit>()

        suspend fun awaitFirstOpenEntered() {
            firstOpenEntered.await()
        }

        fun releaseFirstOpen() {
            firstOpenRelease.complete(Unit)
        }

        override suspend fun open(
            identity: TerminalIdentity,
            cwd: String?,
            sshOptions: SshOpenOptions?,
        ): OpenResult<TerminalSessionPort> {
            val index = synchronized(this) {
                openCount++
                identities.add(identity)
                openCount
            }
            if (index == 1) {
                firstOpenEntered.complete(Unit)
                firstOpenRelease.await()
            }
            return OpenResult.Success(FakeSession(id = "runtime-$index"))
        }

        override suspend fun close(sessionId: String) {
            synchronized(this) { runtimeCloseIds.add(sessionId) }
        }

        override suspend fun closeAll(): Int = synchronized(this) { openCount }
    }

    private class FakeSession(override val id: String) : TerminalSessionPort {
        override val stream = MutableSharedFlow<TerminalTextChunk>(replay = 16, extraBufferCapacity = 16)

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> =
            error("not used")

        override suspend fun write(text: String) = Unit

        override suspend fun close() = Unit
    }
}
