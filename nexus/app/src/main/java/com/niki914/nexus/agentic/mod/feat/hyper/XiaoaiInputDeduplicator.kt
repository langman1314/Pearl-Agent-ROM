package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.mod.feat.AssistantCapturedInput

class XiaoaiInputDeduplicator(
    private val duplicateWindowMs: Long = 1_500L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var lastInput: TimedInput? = null

    fun shouldDeliver(input: AssistantCapturedInput): Boolean = synchronized(lock) {
        val now = clockMs()
        val previous = lastInput
        val duplicate = previous != null &&
                now - previous.capturedAtMs in 0..duplicateWindowMs &&
                previous.input.roomId == input.roomId &&
                previous.input.query == input.query
        if (!duplicate) {
            lastInput = TimedInput(input, now)
        }
        !duplicate
    }

    fun reset() = synchronized(lock) {
        lastInput = null
    }

    private data class TimedInput(
        val input: AssistantCapturedInput,
        val capturedAtMs: Long,
    )
}
