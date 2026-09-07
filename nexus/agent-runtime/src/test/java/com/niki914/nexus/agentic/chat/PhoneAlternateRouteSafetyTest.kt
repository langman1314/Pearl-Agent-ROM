package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.GestureBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.KeyEventBuiltin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneAlternateRouteSafetyTest {
    @Test
    fun gestureRejectsCoordinateTap() = runTest {
        val result = GestureBuiltin().invoke(
            BuiltinToolRequest(
                name = "gesture",
                argumentsJson = """{"start_x":100,"start_y":200,"end_x":101,"end_y":201}""",
            )
        )
        assertFalse(result.ok)
        assertEquals("GESTURE_TOO_SHORT", result.code)
    }

    @Test
    fun keyEventRejectsCommittingKeysBeforeExecution() = runTest {
        listOf(5, 23, 66).forEach { key ->
            val result = KeyEventBuiltin().invoke(
                BuiltinToolRequest("key_event", """{"key":$key}""")
            )
            assertFalse(result.ok)
            assertEquals("KEY_NOT_ALLOWED", result.code)
        }
    }
}
