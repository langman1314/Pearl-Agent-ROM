package com.niki914.nexus.agentic.mod.feat

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class AssistantInputSource(val wireName: String) {
    FINAL_ASR("final_asr"),
    TEMPLATE_QUERY("template_query"),
    QUERY_INFO("query_info"),
    HOST_INPUT("host_input"),
}

data class AssistantCapturedInput(
    val roomId: String,
    val query: String,
    val source: AssistantInputSource,
)

class AssistantInputIdentity(
    private val hostProcessEpoch: String = UUID.randomUUID().toString(),
) {
    private val inputSequence = AtomicLong()

    fun next(input: AssistantCapturedInput, turnId: Long): String {
        val sequence = inputSequence.incrementAndGet()
        return buildString {
            append(hostProcessEpoch)
            append(':')
            append(sequence)
            append(':')
            append(input.source.wireName)
            append(':')
            append(turnId)
            append(':')
            append(input.roomId.length)
            append(':')
            append(input.roomId)
        }
    }
}
