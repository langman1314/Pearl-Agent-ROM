package com.niki914.nexus.agentic.chat.agentic.custom

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The custom-tool return seam: a command that was already handed to the terminal and then stopped
 * answering must not come back as a retryable failure.
 *
 * Everything here drives a fake [CustomToolExecutor] command body and an explicit safety policy; no
 * real terminal, no runtime settings gateway, and no phone action is involved.
 */
class CustomToolExecutorTest {
    private fun tool(command: String = "echo harmless") = LocalTool.Custom(
        name = "custom_tool",
        description = "test tool",
        enabled = true,
        command = command,
    )

    /** A policy with no rules, so `evaluate` never awaits the runtime settings gateway. */
    private fun permissivePolicy() = ShellCommandSafetyPolicy(
        listExecutionRules = { emptyList() },
        isUnlocked = { true },
    )

    private fun blockingPolicy() = ShellCommandSafetyPolicy(
        listExecutionRules = {
            listOf(
                RuntimeExecutionRule(
                    id = "block-rm",
                    name = "dangerous",
                    enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
                    patterns = listOf("\\brm\\s+-rf\\b"),
                )
            )
        },
        isUnlocked = { true },
    )

    private fun executorReturning(
        result: CustomCommandExecutionResult,
        policy: ShellCommandSafetyPolicy = permissivePolicy(),
    ) = CustomToolExecutor(
        safetyPolicy = policy,
        commandExecutor = { _, _ -> result },
    )

    private fun field(json: String, key: String): String? =
        runCatching { Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content }
            .getOrNull()

    private fun decodedOutcome(json: String): ToolOutcome? =
        BuiltinToolResult.fromJsonString(json)?.outcome

    @Test
    fun timeoutAfterDispatchIsUnknownAndNotARetryableFailure() = runTest {
        val json = executorReturning(
            CustomCommandExecutionResult(
                ok = false,
                message = "Command timed out after 10000ms.",
                unconfirmed = true,
            )
        ).execute(tool())

        assertEquals("false", field(json, "ok"))
        assertEquals("unknown", field(json, "outcome"))
        assertEquals(BuiltinToolResult.OUTCOME_UNKNOWN_CODE, field(json, "code"))
        assertEquals(ToolOutcome.UNKNOWN, decodedOutcome(json))
        // The hint must steer the caller to re-observe and must carry the do-not-repeat contract.
        val hint = field(json, "hint").orEmpty()
        org.junit.Assert.assertTrue(hint, hint.contains(BuiltinToolResult.OUTCOME_UNKNOWN_HINT))
    }

    @Test
    fun confirmedFailureStaysARetryableFailure() = runTest {
        val json = executorReturning(
            CustomCommandExecutionResult(ok = false, message = "Command exited with code 2.")
        ).execute(tool())

        // The legacy failure shape carries no `outcome`: decoding it must still read as a failure
        // the caller may retry, not as an unconfirmed action.
        assertEquals("false", field(json, "ok"))
        assertEquals(ToolOutcome.FAILURE, decodedOutcome(json))
        assertFalse(
            "a confirmed failure must not carry the do-not-repeat hint",
            field(json, "hint")?.contains("未重复操作") == true,
        )
    }

    @Test
    fun confirmedSuccessKeepsItsCommandAndStdout() = runTest {
        val json = executorReturning(
            CustomCommandExecutionResult(ok = true, stdout = "done")
        ).execute(tool())

        assertEquals("true", field(json, "ok"))
        assertEquals("done", field(json, "stdout"))
        assertEquals("echo harmless", field(json, "command"))
        assertEquals(ToolOutcome.SUCCESS, decodedOutcome(json))
    }

    @Test
    fun blockedCommandNeverRunsAndStaysRetryable() = runTest {
        var invoked = false
        val executor = CustomToolExecutor(
            safetyPolicy = blockingPolicy(),
            commandExecutor = { _, _ ->
                invoked = true
                CustomCommandExecutionResult(ok = true, stdout = "should not run")
            },
        )

        val json = executor.execute(tool(command = "rm -rf /"))

        assertFalse("a blocked command must never be dispatched", invoked)
        assertEquals("false", field(json, "ok"))
        assertEquals(ToolOutcome.FAILURE, decodedOutcome(json))
        assertNull(field(json, "stdout"))
    }

    @Test
    fun emptyCommandIsRefusedBeforeDispatch() = runTest {
        var invoked = false
        val executor = CustomToolExecutor(
            safetyPolicy = permissivePolicy(),
            commandExecutor = { _, _ ->
                invoked = true
                CustomCommandExecutionResult(ok = true)
            },
        )

        val json = executor.execute(tool(command = "   "))

        assertFalse(invoked)
        assertEquals("false", field(json, "ok"))
        assertEquals(ToolOutcome.FAILURE, decodedOutcome(json))
    }
}
