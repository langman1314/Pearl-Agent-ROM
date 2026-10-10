package com.niki914.nexus.agentic.mod.feat.hyper

import java.util.Optional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏 shape 的覆盖。这里只验证“形状被正确读出且不含敏感内容”这一源码事实；
 * 真机上宿主是否真的传这些 payload 字段仍要看日志。
 */
class XiaoaiStreamContainerInstructionTest {

    private val marker = "stream.bundle"

    @Test
    fun reportsShapeForNormalStreamContainer() {
        val shape = XiaoaiStreamContainerInstruction.inspectShape(
            FakeInstruction(FakePayload(loadUrl = Optional.of("local://stream.bundle/index"))),
            marker,
        )!!
        assertEquals("URL", shape.loadType)
        assertTrue(shape.loadUrlPresent)
        assertTrue(shape.loadUrlMatchesMarker)
        assertFalse(shape.loadHtmlPresent)
        assertEquals(0, shape.innerInstructionCount)
        assertFalse(shape.innerInstructionsPossiblyPresent)
    }

    @Test
    fun countsSmuggledInnerInstructions() {
        val shape = XiaoaiStreamContainerInstruction.inspectShape(
            FakeInstruction(
                FakePayload(
                    loadUrl = Optional.of("local://stream.bundle/index"),
                    instructions = Optional.of(listOf(Any(), Any(), Any())),
                )
            ),
            marker,
        )!!
        assertEquals(3, shape.innerInstructionCount)
        assertTrue(shape.innerInstructionsPossiblyPresent)
    }

    @Test
    fun unknownInnerInstructionStructureIsTreatedAsPossiblyPresent() {
        // 提供了 instructions 但不是集合：数量未知，保守视为可能存在副作用。
        val shape = XiaoaiStreamContainerInstruction.inspectShape(
            FakeInstruction(
                FakePayload(
                    loadUrl = Optional.of("local://stream.bundle/index"),
                    instructions = Optional.of("unexpected-scalar"),
                )
            ),
            marker,
        )!!
        assertEquals(
            XiaoaiStreamContainerInstruction.INNER_INSTRUCTION_COUNT_UNKNOWN,
            shape.innerInstructionCount,
        )
        assertTrue(shape.innerInstructionsPossiblyPresent)
    }

    @Test
    fun marksDisguisedUrlContainerWithHtmlLoad() {
        // URL 含标记，但同一 payload 还带了 load_html：形状不一致，调用方可据此分辨伪装容器。
        val shape = XiaoaiStreamContainerInstruction.inspectShape(
            FakeInstruction(
                FakePayload(
                    loadUrl = Optional.of("https://example.com/stream.bundle"),
                    loadHtml = Optional.of("<html></html>"),
                )
            ),
            marker,
        )!!
        assertTrue(shape.loadUrlMatchesMarker)
        assertTrue(shape.loadHtmlPresent)
        assertFalse(shape.innerInstructionsPossiblyPresent)
    }

    @Test
    fun blankMarkerNeverMatches() {
        val shape = XiaoaiStreamContainerInstruction.inspectShape(
            FakeInstruction(FakePayload(loadUrl = Optional.of("local://stream.bundle/index"))),
            "",
        )!!
        assertFalse(shape.loadUrlMatchesMarker)
    }

    @Test
    fun missingPayloadReturnsNullInsteadOfThrowing() {
        assertNull(XiaoaiStreamContainerInstruction.inspectShape(null, marker))
        assertNull(XiaoaiStreamContainerInstruction.inspectShape(Any(), marker))
    }

    class FakeInstruction(private val payload: Any?) {
        fun getPayload(): Any? = payload
    }

    class FakePayload(
        private val loadUrl: Optional<String> = Optional.empty(),
        private val loadHtml: Optional<String> = Optional.empty(),
        private val cardType: Optional<String> = Optional.empty(),
        private val instructions: Optional<Any> = Optional.empty(),
    ) {
        fun getLoadUrl(): Optional<String> = loadUrl
        fun getLoadHtml(): Optional<String> = loadHtml
        fun getCardType(): Optional<String> = cardType
        fun getInstructions(): Optional<Any> = instructions
        fun getLoadType(): String = "URL"
        fun getParamType(): String = "json"
    }
}
