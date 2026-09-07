package com.niki914.nexus.agentic.chat

import java.util.concurrent.atomic.AtomicLong

data class ConversationTurnState(
    val turnId: Long = 0L,
    val roomId: String = "",
    val lastQuery: String = "",
    val mode: TurnMode = TurnMode.InjectedLLM
) {
    fun nextTurn(roomId: String, query: String, mode: TurnMode) = ConversationTurnState(
        turnId = TurnIdGenerator.next(),
        roomId = roomId,
        lastQuery = query,
        mode = mode
    )
}

private object TurnIdGenerator {
    private val nextId = AtomicLong(System.currentTimeMillis())

    fun next(): Long = nextId.updateAndGet { previous ->
        maxOf(previous + 1L, System.currentTimeMillis())
    }
}
