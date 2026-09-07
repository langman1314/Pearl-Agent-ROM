package com.niki914.nexus.agentic.mod.feat.hyper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinalAsrPayloadDecoderTest {
    @Test
    fun partialResultDoesNotCreateInput() {
        assertNull(FinalAsrPayloadDecoder.decode(FakePayload(false, listOf(FakeItem("partial")))))
    }

    @Test
    fun finalResultUsesFirstNonBlankCandidate() {
        assertEquals(
            "打开计算器",
            FinalAsrPayloadDecoder.decode(
                FakePayload(true, listOf(FakeItem("  "), FakeItem(" 打开计算器 "), FakeItem("other")))
            )
        )
    }

    @Test
    fun emptyFinalResultFailsOpen() {
        assertNull(FinalAsrPayloadDecoder.decode(FakePayload(true, emptyList())))
        assertNull(FinalAsrPayloadDecoder.decode(FakePayload(true, listOf(FakeItem(" ")))))
    }

    @Test
    fun incompatiblePayloadFailsOpenWithoutGuessingFields() {
        assertNull(FinalAsrPayloadDecoder.decode(Any()))
        assertNull(FinalAsrPayloadDecoder.decode(ThrowingPayload()))
    }

    class FakePayload(private val final: Boolean, private val results: List<FakeItem>) {
        fun isFinal(): Boolean = final
        fun getResults(): List<FakeItem> = results
    }

    class FakeItem(private val text: String) {
        fun getText(): String = text
    }

    class ThrowingPayload {
        fun isFinal(): Boolean = throw IllegalStateException("broken host payload")
        fun getResults(): List<FakeItem> = emptyList()
    }
}
