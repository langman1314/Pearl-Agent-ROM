package com.niki914.nexus.agentic.chat

import java.util.concurrent.atomic.AtomicReference

object ActiveTurnStore {
    private val current = AtomicReference<ConversationTurnState?>(null)

    fun getCurrent(): ConversationTurnState? = current.get()

    fun setCurrent(state: ConversationTurnState) {
        current.set(state)
    }

    fun clear() {
        current.set(null)
    }

    fun isCurrentInjected(): Boolean = getCurrent()?.mode == TurnMode.InjectedLLM

    fun isActiveInjection(turnId: Long, roomId: String? = null): Boolean {
        val state = getCurrent() ?: return false
        return state.turnId == turnId &&
                state.mode == TurnMode.InjectedLLM &&
                (roomId == null || state.roomId == roomId)
    }

    fun ownsInjectedRoom(roomId: String): Boolean {
        val state = getCurrent() ?: return false
        return state.mode == TurnMode.InjectedLLM && state.roomId == roomId
    }

    fun hasActiveTurn(): Boolean = getCurrent() != null
}
