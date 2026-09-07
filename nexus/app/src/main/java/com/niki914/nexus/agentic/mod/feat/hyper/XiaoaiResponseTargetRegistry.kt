package com.niki914.nexus.agentic.mod.feat.hyper

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class XiaoaiResponseTargetRegistry(
    private val targetTtlMs: Long = 15_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val targets = mutableMapOf<String, TargetEntry>()
    private val waiters = mutableMapOf<String, MutableList<CompletableDeferred<Any?>>>()

    fun capture(dialogId: String, target: Any) {
        val pending = synchronized(lock) {
            targets[dialogId] = TargetEntry(target, clockMs())
            waiters.remove(dialogId).orEmpty()
        }
        pending.forEach { it.complete(target) }
    }

    suspend fun await(dialogId: String, timeoutMs: Long): Any? {
        val waiter = synchronized(lock) {
            currentTargetLocked(dialogId)?.let { return it }
            CompletableDeferred<Any?>().also {
                waiters.getOrPut(dialogId) { mutableListOf() }.add(it)
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            synchronized(lock) {
                waiters[dialogId]?.remove(waiter)
                if (waiters[dialogId].isNullOrEmpty()) waiters.remove(dialogId)
            }
        }
    }

    fun clear(dialogId: String) {
        val pending = synchronized(lock) {
            targets.remove(dialogId)
            waiters.remove(dialogId).orEmpty()
        }
        pending.forEach { it.complete(null) }
    }

    fun reset() {
        val pending = synchronized(lock) {
            targets.clear()
            waiters.values.flatten().also { waiters.clear() }
        }
        pending.forEach { it.complete(null) }
    }

    private fun currentTargetLocked(dialogId: String): Any? {
        val entry = targets[dialogId] ?: return null
        if (clockMs() - entry.capturedAtMs > targetTtlMs) {
            targets.remove(dialogId)
            return null
        }
        return entry.target
    }

    private data class TargetEntry(val target: Any, val capturedAtMs: Long)
}
