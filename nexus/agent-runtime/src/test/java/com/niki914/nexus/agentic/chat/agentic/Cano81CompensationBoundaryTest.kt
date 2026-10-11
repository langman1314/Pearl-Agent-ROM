package com.niki914.nexus.agentic.chat.agentic

import com.niki914.libterm.OpenResult
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
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
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CANO-81 directed correction (2/2): the *compensating close* boundary of `TerminalSessionPool
 * .openSession`.
 *
 * The pool registers a handle only if nobody took it while the runtime was opening a session. When
 * someone did take it, the session this call just opened is not the one being reported, so the pool
 * closes it again — the compensating close. That close has no result channel: `TerminalRuntimePort
 * .close` returns `Unit`, so "the compensating close succeeded" only ever means "it did not throw".
 * The defect this class defends against is that the old exit folded *any* failure there into
 * `runCatching { … }`, so a call whose own session was never proven dead went on to retry on a fresh
 * handle, or — with `reuseExisting = true`, the `executeCustomCommand` path — reused the *other*
 * call's session and ran its command on it.
 *
 * Every case enters through the real production chain:
 *
 *  - the generated-handle branch through the real [ToolCallDispatcher] entry point
 *    (`executeLocalTool` -> real `TerminalBuiltin` -> real pool), and
 *  - the fixed-reuse branch through the real [CustomToolExecutor] whose default command executor is
 *    the real `TerminalSessionPool.executeCustomCommand`.
 *
 * The race is made deterministic with the pool's existing install points plus a runtime that parks
 * its first open on a `CompletableDeferred` — no sleeps and no randomness: the loser's open parks
 * *inside* the runtime after the handle was already chosen, the racer then registers that same
 * handle, and the loser resumes into the compensating close.
 *
 * Scope and honesty: the runtime, the session and the command result below are **fakes**. What these
 * cases assert (opens, runtime closes, and which session id executed) proves *interface*
 * reachability — that a production seam folded a cleanup failure into a continued action. They are
 * not real-device evidence: nothing here touches a phone, ADB, a real shell or a real file, and the
 * fake session's `exec` returns a canned `CommandResult` instead of running anything. The fake's
 * ability to throw from `close` is a stand-in for "the close did not complete", not proof about any
 * real device cleanup.
 *
 * These cases fail on the unmodified revision (`56acc34`) and pass after the minimal fix: on the old
 * exit the loser's first return is a *decided success* envelope while the counts show the extra open
 * or the reused session; on the fixed exit the loser's first return is a canonical unknown, no
 * second handle is taken, and no command runs on the other call's session.
 */
class Cano81CompensationBoundaryTest {
    @After
    fun tearDown() {
        runTest { TerminalSessionPool.closeAll() }
        RuntimeEnvironment.clearForTest()
    }

    // -------------------------------------------------- generated-handle branch, ordinary exception

    /**
     * The loser's own runtime session cannot be closed again, so the compensating close throws. The
     * old exit swallowed that and retried on a fresh handle, reporting a success for a session whose
     * predecessor may still be alive; the fixed exit must report the call as unconfirmed, take no
     * second handle, and never touch the racer's session.
     */
    @Test
    fun anUnprovenCompensatingCloseDoesNotRetryOnAFreshGeneratedHandle() = runTest {
        val runtime = CompensationRuntime(
            compensatingCloseThrowable = { IllegalStateException(CLOSE_FAILED) },
        )
        install(runtime).use {
            installHandles("a3f9", "a3f9", "b7c2").use {
                val screen = CountingBuiltin("screen_content", "nodes: []")
                val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
                val dispatcher = dispatcher(permissiveTerminal(), screen, later)
                val openArguments = """{"action":"open","identity":"user"}"""

                // The loser, at the real dispatcher entry point, parked inside the runtime's open.
                val loser = async(Dispatchers.Default) {
                    dispatcher.executeLocalTool("terminal", openArguments, callId = "t-open")
                }
                runtime.awaitFirstOpenEntered()
                // The racer: an independent call context sharing the same pool, registering the very
                // handle the parked call had already chosen.
                val racer = try {
                    TerminalSessionPool.open("root")
                } finally {
                    runtime.releaseFirstOpen()
                }

                val loserJson = loser.await()
                assertUnknown(
                    "the loser continued after its compensating close failed " +
                        "(opens=${runtime.openIdsSnapshot()}, closes=${runtime.closeIdsSnapshot()}, " +
                        "execs=${runtime.execIdsSnapshot()}): $loserJson",
                    loserJson,
                )
                assertTrue(
                    "the compensation stage must survive as a readable, redacted diagnostic: $loserJson",
                    loserJson.contains(COMPENSATION_MARKER),
                )
                assertFalse(
                    "an unproven compensating close must not be reported as a request-validation " +
                        "refusal: $loserJson",
                    loserJson.contains("INVALID_REQUEST"),
                )
                assertEquals("no second handle may be taken", 2, runtime.openCount)
                assertEquals(
                    "only the loser's own runtime session may be closed",
                    listOf("runtime-1"),
                    runtime.closeIdsSnapshot(),
                )
                assertEquals(
                    "no command may be composed after an unproven close",
                    emptyList<String>(),
                    runtime.execIdsSnapshot(),
                )
                assertEquals(
                    "the racer's session must stay registered and untouched",
                    "runtime-2",
                    TerminalSessionPool.get("a3f9")?.libTermSessionId,
                )
                assertTrue("the racer's own open must still succeed", racer is TerminalOpenOutcome.Success)

                assertEquals(
                    "the first return must replay byte-identically",
                    loserJson,
                    dispatcher.executeLocalTool("terminal", openArguments, callId = "t-open"),
                )
                assertEquals("a replay must not reach the runtime again", 2, runtime.openCount)

                dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
                assertEquals("an unproven close must block the next side effect", 0, later.invocations)

                val observed = dispatcher.executeLocalTool("screen_content", "{}", callId = "screen-1")
                assertEquals("observation must still run", 1, screen.invocations)
                assertEquals("observation must stay readable: $observed", "true", field(observed, "ok"))
            }
        }
    }

    // ------------------------------------------------------- generated-handle branch, cancellation

    /**
     * The same seam, cancelled: the loser gets no normal return at all, so there is no first string
     * to compare. What must hold is the device-side fact — the cancellation reaches the caller, no
     * second handle is taken — plus the guard's barrier and the still-readable observation.
     */
    @Test
    fun aCancelledCompensatingClosePropagatesAndKeepsTheBarrier() = runTest {
        val runtime = CompensationRuntime(
            compensatingCloseThrowable = { CancellationException(CLOSE_CANCELLED) },
        )
        install(runtime).use {
            installHandles("a3f9", "a3f9", "b7c2").use {
                val screen = CountingBuiltin("screen_content", "nodes: []")
                val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
                val dispatcher = dispatcher(permissiveTerminal(), screen, later)
                val openArguments = """{"action":"open","identity":"user"}"""

                val loser = async(Dispatchers.Default) {
                    dispatcher.executeLocalTool("terminal", openArguments, callId = "t-open")
                }
                runtime.awaitFirstOpenEntered()
                try {
                    TerminalSessionPool.open("root")
                } finally {
                    runtime.releaseFirstOpen()
                }

                val cancelled = try {
                    loser.await()
                    null
                } catch (expected: CancellationException) {
                    expected
                }
                assertTrue(
                    "cancellation must reach the caller as-is " +
                        "(opens=${runtime.openIdsSnapshot()}, closes=${runtime.closeIdsSnapshot()}, " +
                        "execs=${runtime.execIdsSnapshot()})",
                    cancelled is CancellationException,
                )
                assertEquals("no second handle may be taken", 2, runtime.openCount)
                assertEquals(
                    "only the loser's own runtime session may be closed, exactly once",
                    listOf("runtime-1"),
                    runtime.closeIdsSnapshot(),
                )
                assertEquals(
                    "the racer's session must stay registered and untouched",
                    "runtime-2",
                    TerminalSessionPool.get("a3f9")?.libTermSessionId,
                )

                // A cancelled call left no verdict: the same call id must not be dispatched again,
                // and the round's barrier must refuse it.
                val repeat = dispatcher.executeLocalTool("terminal", openArguments, callId = "t-open")
                assertTrue(
                    "a cancelled call must not be dispatched again: $repeat",
                    repeat.contains("ACTION_STATE_INDETERMINATE"),
                )
                assertEquals("the barrier must hold", 2, runtime.openCount)

                dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
                assertEquals("a cancelled open must block the next side effect", 0, later.invocations)

                val observed = dispatcher.executeLocalTool("screen_content", "{}", callId = "screen-1")
                assertEquals("observation must still run", 1, screen.invocations)
                assertEquals("observation must stay readable: $observed", "true", field(observed, "ok"))
            }
        }
    }

    // --------------------------------------------------------- fixed-reuse branch (custom tool)

    /**
     * The `reuseExisting = true` path — the one `executeCustomCommand` really uses — is the sharper
     * half of the defect: once the compensating close failure was swallowed, the loser did not stop
     * at reporting the *other* call's session, it went on to execute its command **on that session**.
     * The fixed exit must not run the command at all.
     */
    @Test
    fun anUnprovenCompensatingCloseDoesNotReuseTheRacersCustomToolSession() = runTest {
        val runtime = CompensationRuntime(
            compensatingCloseThrowable = { IllegalStateException(CLOSE_FAILED) },
        )
        install(runtime).use {
            val screen = CountingBuiltin("screen_content", "nodes: []")
            val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(screen, later, customToolExecutor = permissiveCustomExecutor())
            val tool = customTool()

            val loser = async(Dispatchers.Default) { dispatcher.executeCustomTool(tool, callId = "custom-1") }
            runtime.awaitFirstOpenEntered()
            // The racer: the same composite call the custom executor makes, in its own call context.
            val racer = try {
                TerminalSessionPool.executeCustomCommand(command = HARMLESS_COMMAND, timeoutMs = 1_000L)
            } finally {
                runtime.releaseFirstOpen()
            }

            val loserJson = loser.await()
            assertUnknown(
                "the custom tool continued on a session whose own cleanup failed " +
                    "(opens=${runtime.openIdsSnapshot()}, closes=${runtime.closeIdsSnapshot()}, " +
                    "execs=${runtime.execIdsSnapshot()}): $loserJson",
                loserJson,
            )
            assertTrue(
                "the compensation stage must survive as a readable, redacted diagnostic: $loserJson",
                loserJson.contains(COMPENSATION_MARKER),
            )
            assertTrue(
                "the racer's own command must still succeed: $racer",
                racer is TerminalCommandOutcome.Success,
            )
            assertEquals("no second session may be opened", 2, runtime.openCount)
            assertEquals(
                "only the loser's own runtime session may be closed",
                listOf("runtime-1"),
                runtime.closeIdsSnapshot(),
            )
            assertEquals(
                "no command may run on the racer's session beyond the racer's own",
                listOf("runtime-2"),
                runtime.execIdsSnapshot(),
            )
            assertEquals(
                "the racer's session must stay the one registered for this handle",
                "runtime-2",
                TerminalSessionPool.get("__custom_user")?.libTermSessionId,
            )

            assertEquals(
                "the first return must replay byte-identically",
                loserJson,
                dispatcher.executeCustomTool(tool, callId = "custom-1"),
            )
            assertEquals("a replay must not reach the runtime again", 2, runtime.openCount)

            dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
            assertEquals("an unproven close must block the next side effect", 0, later.invocations)

            val observed = dispatcher.executeLocalTool("screen_content", "{}", callId = "screen-1")
            assertEquals("observation must still run", 1, screen.invocations)
            assertEquals("observation must stay readable: $observed", "true", field(observed, "ok"))
        }
    }

    /** The fixed-reuse seam cancelled: no normal return, and no command on the racer's session. */
    @Test
    fun aCancelledCompensatingCloseDoesNotExecuteOnTheRacersCustomToolSession() = runTest {
        val runtime = CompensationRuntime(
            compensatingCloseThrowable = { CancellationException(CLOSE_CANCELLED) },
        )
        install(runtime).use {
            val screen = CountingBuiltin("screen_content", "nodes: []")
            val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(screen, later, customToolExecutor = permissiveCustomExecutor())
            val tool = customTool()

            val loser = async(Dispatchers.Default) { dispatcher.executeCustomTool(tool, callId = "custom-1") }
            runtime.awaitFirstOpenEntered()
            try {
                TerminalSessionPool.executeCustomCommand(command = HARMLESS_COMMAND, timeoutMs = 1_000L)
            } finally {
                runtime.releaseFirstOpen()
            }

            val cancelled = try {
                loser.await()
                null
            } catch (expected: CancellationException) {
                expected
            }
            assertTrue(
                "cancellation must reach the caller as-is " +
                    "(opens=${runtime.openIdsSnapshot()}, closes=${runtime.closeIdsSnapshot()}, " +
                    "execs=${runtime.execIdsSnapshot()})",
                cancelled is CancellationException,
            )
            assertEquals("no second session may be opened", 2, runtime.openCount)
            assertEquals(
                "only the loser's own runtime session may be closed, exactly once",
                listOf("runtime-1"),
                runtime.closeIdsSnapshot(),
            )
            assertEquals(
                "no command may run on the racer's session beyond the racer's own",
                listOf("runtime-2"),
                runtime.execIdsSnapshot(),
            )
            assertEquals(
                "the racer's session must stay registered and untouched",
                "runtime-2",
                TerminalSessionPool.get("__custom_user")?.libTermSessionId,
            )

            val repeat = dispatcher.executeCustomTool(tool, callId = "custom-1")
            assertTrue(
                "a cancelled call must not be dispatched again: $repeat",
                repeat.contains("ACTION_STATE_INDETERMINATE"),
            )
            assertEquals("the barrier must hold", 2, runtime.openCount)

            dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
            assertEquals("a cancelled custom tool must block the next side effect", 0, later.invocations)

            val observed = dispatcher.executeLocalTool("screen_content", "{}", callId = "screen-1")
            assertEquals("observation must still run", 1, screen.invocations)
            assertEquals("observation must stay readable: $observed", "true", field(observed, "ok"))
        }
    }

    // ------------------------------------------------------------------------- positive controls

    /**
     * The other side of the same seam: when the compensating close really finishes, the bounded retry
     * is still the right answer and must not regress. The loser takes a fresh handle, the racer's
     * session is untouched, and the round is not latched.
     */
    @Test
    fun aConfirmedCompensatingCloseStillRetriesOnAFreshHandle() = runTest {
        val runtime = CompensationRuntime()
        install(runtime).use {
            installHandles("a3f9", "a3f9", "b7c2").use {
                val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
                val dispatcher = dispatcher(permissiveTerminal(), later)
                val openArguments = """{"action":"open","identity":"user"}"""

                val loser = async(Dispatchers.Default) {
                    dispatcher.executeLocalTool("terminal", openArguments, callId = "t-open")
                }
                runtime.awaitFirstOpenEntered()
                try {
                    TerminalSessionPool.open("root")
                } finally {
                    runtime.releaseFirstOpen()
                }

                val loserJson = loser.await()
                assertEquals(
                    "a compensating close that finished must still report the fresh handle: $loserJson",
                    "b7c2",
                    field(loserJson, "session"),
                )
                assertEquals(
                    "a decided success must not carry an unknown outcome: $loserJson",
                    "",
                    field(loserJson, "outcome"),
                )
                assertEquals("exactly one retry, no more", 3, runtime.openCount)
                assertEquals(
                    "the loser's own first session is the only one closed",
                    listOf("runtime-1"),
                    runtime.closeIdsSnapshot(),
                )
                assertEquals(
                    "the fresh handle must really be registered",
                    "runtime-3",
                    TerminalSessionPool.get("b7c2")?.libTermSessionId,
                )
                assertEquals(
                    "the racer's session must stay registered and untouched",
                    "runtime-2",
                    TerminalSessionPool.get("a3f9")?.libTermSessionId,
                )

                dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
                assertEquals("a finished compensation must not latch the round", 1, later.invocations)
            }
        }
    }

    /**
     * No race at all: the ordinary open/exec path and the ordinary reuse of the custom tool's fixed
     * handle must keep working and must not latch the round — the boundary fix may only change what
     * happens after a cleanup *fails*.
     */
    @Test
    fun theUnracedPathAndConfirmedReuseStillWork() = runTest {
        val runtime = CompensationRuntime(parkFirstOpen = false)
        install(runtime).use {
            installHandles("a3f9").use {
                val later = CountingRaw("gesture", BuiltinToolResult.success("done").toJsonString())
                val dispatcher = dispatcher(
                    permissiveTerminal(),
                    later,
                    customToolExecutor = permissiveCustomExecutor(),
                )

                val opened = dispatcher.executeLocalTool(
                    "terminal",
                    """{"action":"open","identity":"user"}""",
                    callId = "open-1",
                )
                val handle = field(opened, "session")
                assertEquals("a plain open must still succeed: $opened", "a3f9", handle)

                val executed = dispatcher.executeLocalTool(
                    "terminal",
                    """{"action":"exec","session":"$handle","command":"$HARMLESS_COMMAND"}""",
                    callId = "exec-1",
                )
                assertEquals(
                    "a plain exec must still come back as a decided completion: $executed",
                    "0",
                    field(executed, "exit_code"),
                )
                assertEquals(
                    "the exec really ran on the opened session",
                    listOf("runtime-1"),
                    runtime.execIdsSnapshot(),
                )

                // The custom tool's fixed handle: the first call opens it, the second reuses it.
                val first = dispatcher.executeCustomTool(customTool(), callId = "custom-1")
                assertEquals("the first custom call must succeed: $first", "true", field(first, "ok"))
                val openedForCustom = runtime.openCount
                val second = dispatcher.executeCustomTool(customTool(), callId = "custom-2")
                assertEquals("a confirmed reuse must still succeed: $second", "true", field(second, "ok"))
                assertEquals(
                    "a confirmed reuse must not open another session",
                    openedForCustom,
                    runtime.openCount,
                )

                dispatcher.executeLocalTool("gesture", "{}", callId = "next-1")
                assertEquals("decided results must not latch the round", 1, later.invocations)
            }
        }
    }

    // ---------------------------------------------------------------------------------- plumbing

    private fun dispatcher(
        vararg tools: BuiltinTool,
        customToolExecutor: CustomToolExecutor = CustomToolExecutor(),
    ): ToolCallDispatcher {
        return ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(tools.toList())),
            customToolExecutor = customToolExecutor,
            actionGuard = RoundActionGuard(),
            currentTools = {
                ResolvedTools(builtinTools = tools.map { LocalTool.Builtin(it.name, it.name, it) })
            },
        )
    }

    /** The real custom executor: its default command executor is the real pool path. */
    private fun permissiveCustomExecutor(): CustomToolExecutor = CustomToolExecutor(
        safetyPolicy = ShellCommandSafetyPolicy(
            listExecutionRules = { emptyList() },
            isUnlocked = { true },
        )
    )

    private fun customTool(): LocalTool.Custom = LocalTool.Custom(
        name = "custom_tool",
        description = "test tool",
        enabled = true,
        command = HARMLESS_COMMAND,
    )

    /** A terminal whose policy really runs but blocks nothing, so no settings gateway is needed. */
    private fun permissiveTerminal(): TerminalBuiltin = TerminalBuiltin(
        safetyPolicy = ShellCommandSafetyPolicy(
            listExecutionRules = { emptyList() },
            isUnlocked = { true },
        )
    )

    private fun install(runtime: CompensationRuntime): AutoCloseable {
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
        assertEquals("$message (outcome)", ToolOutcome.UNKNOWN.wireName, field(json, "outcome"))
        assertTrue(
            "$message (classifier)",
            LocalToolResultClassifier.status(json) is LocalToolResultStatus.Unconfirmed,
        )
    }

    private fun field(json: String, key: String): String =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()

    /**
     * A runtime whose first open parks until released, so a second call context can take the handle
     * that the parked call already chose. Its compensating close can be made to fail — with an
     * ordinary exception or with cancellation — which is exactly the failure the pool used to fold
     * away. The session id that executed is recorded, so a command run on the *other* call's session
     * is visible rather than inferred.
     */
    private class CompensationRuntime(
        private val compensatingCloseThrowable: (() -> Throwable)? = null,
        private val parkFirstOpen: Boolean = true,
    ) : TerminalRuntimePort {
        private val openIds = mutableListOf<String>()
        private val closeIds = mutableListOf<String>()
        private val execIds = mutableListOf<String>()
        private val firstOpenEntered = CompletableDeferred<Unit>()
        private val firstOpenRelease = CompletableDeferred<Unit>()

        val openCount: Int get() = openIdsSnapshot().size

        fun openIdsSnapshot(): List<String> = synchronized(this) { openIds.toList() }

        fun closeIdsSnapshot(): List<String> = synchronized(this) { closeIds.toList() }

        fun execIdsSnapshot(): List<String> = synchronized(this) { execIds.toList() }

        suspend fun awaitFirstOpenEntered() = firstOpenEntered.await()

        fun releaseFirstOpen() {
            firstOpenRelease.complete(Unit)
        }

        fun recordExec(sessionId: String) {
            synchronized(this) { execIds.add(sessionId) }
        }

        override suspend fun open(
            identity: TerminalIdentity,
            cwd: String?,
            sshOptions: SshOpenOptions?,
        ): OpenResult<TerminalSessionPort> {
            val id = synchronized(this) {
                "runtime-${openIds.size + 1}".also { openIds.add(it) }
            }
            if (parkFirstOpen && id == "runtime-1") {
                firstOpenEntered.complete(Unit)
                firstOpenRelease.await()
            }
            return OpenResult.Success(CompensationSession(id = id, runtime = this))
        }

        override suspend fun close(sessionId: String) {
            synchronized(this) { closeIds.add(sessionId) }
            compensatingCloseThrowable?.invoke()?.let { throw it }
        }

        override suspend fun closeAll(): Int = openCount
    }

    private class CompensationSession(
        override val id: String,
        private val runtime: CompensationRuntime,
    ) : TerminalSessionPort {
        override val stream =
            MutableSharedFlow<TerminalTextChunk>(replay = 16, extraBufferCapacity = 16)

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            runtime.recordExec(id)
            // A canned result: nothing here reaches a shell, a file or a phone.
            return TermResult.Success(
                CommandResult(
                    command = "cmd",
                    stdout = TerminalBytes.of("ok\n".encodeToByteArray()),
                    stderr = TerminalBytes.of(ByteArray(0)),
                    exitCode = 0,
                    timedOut = false,
                )
            )
        }

        override suspend fun write(text: String) = Unit

        override suspend fun close() = Unit
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
        /** The compensating close's own message, which the fixed exit must not discard. */
        const val CLOSE_FAILED = "compensating close failed"

        /** The cancelled compensating close's own message. */
        const val CLOSE_CANCELLED = "compensating close cancelled"

        /** A marker any fix that keeps the compensation-stage diagnostic will carry through. */
        const val COMPENSATION_MARKER = "ompensat"

        /** Never executed: the fake session answers with a canned result. */
        const val HARMLESS_COMMAND = "echo harmless"
    }
}
