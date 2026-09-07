package com.niki914.nexus.agentic.mod.feat

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantInputIdentityTest {
    @Test
    fun requestIdsIncludeEpochSequenceSourceAndUnambiguousRoom() {
        val identity = AssistantInputIdentity(hostProcessEpoch = "epoch-1")
        val first = identity.next(
            AssistantCapturedInput("room:one", "hello", AssistantInputSource.TEMPLATE_QUERY),
            turnId = 7L,
        )
        val second = identity.next(
            AssistantCapturedInput("room:one", "hello", AssistantInputSource.QUERY_INFO),
            turnId = 7L,
        )

        assertTrue(first.startsWith("epoch-1:1:template_query:7:8:room:one"))
        assertTrue(second.startsWith("epoch-1:2:query_info:7:8:room:one"))
        assertNotEquals(first, second)
    }

    @Test
    fun anotherHostProcessEpochCannotReuseRequestIdentity() {
        val input = AssistantCapturedInput("room", "hello", AssistantInputSource.FINAL_ASR)
        val first = AssistantInputIdentity("epoch-a").next(input, 1L)
        val second = AssistantInputIdentity("epoch-b").next(input, 1L)

        assertNotEquals(first, second)
    }
}
