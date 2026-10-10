package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.ToolCallDispatcher
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.runtime.CommandResult
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultStatus
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Cano81CandidateIndependentRegressionTest {
    private class CountingTool(override val name: String, private val payload: String) : BuiltinTool(), RawJsonBuiltinTool {
        var calls = 0
        override fun configure(config: LocalToolConfig) = Unit
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult = error("raw only")
        override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
            calls++
            return payload
        }
    }

    private fun dispatcher(vararg tools: CountingTool) = ToolCallDispatcher(
        builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(tools.toList())),
        customToolExecutor = CustomToolExecutor(),
        currentTools = { ResolvedTools(builtinTools = tools.map { LocalTool.Builtin(it.name, it.name, it) }) },
    )

    private fun assertUnknown(json: String) {
        val fields = Json.parseToJsonElement(json).jsonObject
        assertEquals("false", fields["ok"]?.jsonPrimitive?.content)
        assertEquals("unknown", fields["outcome"]?.jsonPrimitive?.content)
        assertTrue(LocalToolResultClassifier.status(json) is LocalToolResultStatus.Unconfirmed)
    }

    @Test fun opaqueDispatchedResultsCannotPermitAnotherAction() = runTest {
        for (payload in listOf("pending", "{}", """{"pending":true}""", """{"accepted":true}""", """{"session":null}""")) {
            val action = CountingTool("node_action", payload)
            val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(action, later)
            val first = dispatcher.executeLocalTool("node_action", "{}", "action-1")
            val replay = dispatcher.executeLocalTool("node_action", "{}", "action-1")
            assertUnknown(first)
            assertEquals(first, replay)
            dispatcher.executeLocalTool("gesture", "{}", "next-1")
            assertEquals("$payload dispatched once", 1, action.calls)
            assertEquals("$payload must block later dispatch", 0, later.calls)
        }
    }

    @Test fun realTerminalFailureFactoriesBlockFurtherActions() = runTest {
        val failures = listOf(
            TerminalToolResponse.commandTimeout(CommandResult(command = "cmd", stdout = TerminalBytes.of(byteArrayOf()), stderr = TerminalBytes.of(byteArrayOf()), exitCode = null, timedOut = true), elapsedSeconds = 1L, timeoutMs = 1000L, session = "user"),
            TerminalToolResponse.commandTimeout(CommandResult(command = "cmd", stdout = TerminalBytes.of(byteArrayOf()), stderr = TerminalBytes.of(byteArrayOf()), exitCode = null, timedOut = true), elapsedSeconds = 1L, timeoutMs = 1000L),
            TerminalToolResponse.internalError(IllegalStateException("failed")),
        )
        for (payload in failures) {
            val action = CountingTool("terminal", payload)
            val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
            val dispatcher = dispatcher(action, later)
            val first = dispatcher.executeLocalTool("terminal", "{}", "terminal-1")
            assertUnknown(first)
            assertEquals(first, dispatcher.executeLocalTool("terminal", "{}", "terminal-1"))
            dispatcher.executeLocalTool("gesture", "{}", "next-1")
            assertEquals(1, action.calls)
            assertEquals(0, later.calls)
        }
    }

    @Test fun contradictoryUnknownIsNormalizedOnFirstReturnAndReplay() = runTest {
        val action = CountingTool("node_action", """{"ok":true,"outcome":"unknown","message":"tap not confirmed"}""")
        val later = CountingTool("gesture", BuiltinToolResult.success("done").toJsonString())
        val dispatcher = dispatcher(action, later)
        val first = dispatcher.executeLocalTool("node_action", "{}", "action-1")
        val replay = dispatcher.executeLocalTool("node_action", "{}", "action-1")
        assertUnknown(first)
        assertEquals(first, replay)
        dispatcher.executeLocalTool("gesture", "{}", "next-1")
        assertEquals(1, action.calls)
        assertEquals(0, later.calls)
    }
}
