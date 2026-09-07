package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.mod.feat.AssistantCapturedInput
import com.niki914.nexus.agentic.mod.feat.AssistantInputSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoaiInputDeduplicatorTest {
    private var now = 10_000L
    private val deduplicator = XiaoaiInputDeduplicator(duplicateWindowMs = 1_500L) { now }

    @Test
    fun suppressesCompatibilityFallbackForSameUtterance() {
        assertTrue(deduplicator.shouldDeliver(input(AssistantInputSource.TEMPLATE_QUERY)))
        now += 200L
        assertFalse(deduplicator.shouldDeliver(input(AssistantInputSource.QUERY_INFO)))
    }

    @Test
    fun allowsIdenticalNewUtteranceAfterWindow() {
        assertTrue(deduplicator.shouldDeliver(input(AssistantInputSource.TEMPLATE_QUERY)))
        now += 1_501L
        assertTrue(deduplicator.shouldDeliver(input(AssistantInputSource.TEMPLATE_QUERY)))
    }

    @Test
    fun differentDialogOrTextIsNeverCollapsed() {
        assertTrue(deduplicator.shouldDeliver(input(AssistantInputSource.TEMPLATE_QUERY)))
        assertTrue(
            deduplicator.shouldDeliver(
                AssistantCapturedInput("dialog-2", "same text", AssistantInputSource.QUERY_INFO)
            )
        )
        assertTrue(
            deduplicator.shouldDeliver(
                AssistantCapturedInput("dialog-2", "new text", AssistantInputSource.QUERY_INFO)
            )
        )
    }

    @Test
    fun resetAllowsImmediateIdenticalInput() {
        assertTrue(deduplicator.shouldDeliver(input(AssistantInputSource.TEMPLATE_QUERY)))
        deduplicator.reset()
        assertTrue(deduplicator.shouldDeliver(input(AssistantInputSource.QUERY_INFO)))
    }

    private fun input(source: AssistantInputSource) =
        AssistantCapturedInput("dialog-1", "same text", source)
}
