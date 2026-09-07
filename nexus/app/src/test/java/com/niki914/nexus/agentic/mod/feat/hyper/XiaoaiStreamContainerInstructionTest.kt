package com.niki914.nexus.agentic.mod.feat.hyper

import java.util.Optional
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoaiStreamContainerInstructionTest {
    @Test
    fun allowsOnlyFrontendPageWithInternalStreamBundleUrl() {
        assertTrue(
            XiaoaiStreamContainerInstruction.isSafeContainer(
                instruction = FakeInstruction(FakePayload(Optional.of("local://stream.bundle/index"))),
                fullName = "Template.FrontendPage",
                expectedFullName = "Template.FrontendPage",
                loadUrlMarker = "stream.bundle",
            )
        )
        assertFalse(
            XiaoaiStreamContainerInstruction.isSafeContainer(
                instruction = FakeInstruction(FakePayload(Optional.of("https://example.com"))),
                fullName = "Template.FrontendPage",
                expectedFullName = "Template.FrontendPage",
                loadUrlMarker = "stream.bundle",
            )
        )
        assertFalse(
            XiaoaiStreamContainerInstruction.isSafeContainer(
                instruction = FakeInstruction(FakePayload(Optional.of("local://stream.bundle/index"))),
                fullName = "Application.Operate",
                expectedFullName = "Template.FrontendPage",
                loadUrlMarker = "stream.bundle",
            )
        )
        assertFalse(
            XiaoaiStreamContainerInstruction.isSafeContainer(
                instruction = FakeInstruction(FakePayload(Optional.empty())),
                fullName = "Template.FrontendPage",
                expectedFullName = "Template.FrontendPage",
                loadUrlMarker = "stream.bundle",
            )
        )
    }

    class FakeInstruction(private val payload: FakePayload) {
        fun getPayload(): FakePayload = payload
    }

    class FakePayload(private val loadUrl: Optional<String>) {
        fun getLoadUrl(): Optional<String> = loadUrl
    }
}
