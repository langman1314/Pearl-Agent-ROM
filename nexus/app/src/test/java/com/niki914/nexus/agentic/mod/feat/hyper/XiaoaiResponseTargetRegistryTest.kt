package com.niki914.nexus.agentic.mod.feat.hyper

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class XiaoaiResponseTargetRegistryTest {
    private var now = 1_000L
    private val registry = XiaoaiResponseTargetRegistry(targetTtlMs = 500L) { now }

    @Test
    fun wrongDialogCannotWakeWaiter() = runBlocking {
        val wanted = async { registry.await("wanted", 50L) }
        yield()
        registry.capture("other", Any())
        assertNull(wanted.await())
    }

    @Test
    fun matchingDialogWakesOnlyItsWaiter() = runBlocking {
        val target = Any()
        val wanted = async { registry.await("wanted", 1_000L) }
        yield()
        registry.capture("wanted", target)
        assertSame(target, wanted.await())
    }

    @Test
    fun expiredTargetIsNotReused() = runBlocking {
        registry.capture("room", Any())
        now += 501L
        assertNull(registry.await("room", 1L))
    }

    @Test
    fun resetCancelsPendingAndAllowsFreshCapture() = runBlocking {
        val oldWaiter = async { registry.await("room", 1_000L) }
        yield()
        registry.reset()
        assertNull(oldWaiter.await())

        val target = Any()
        registry.capture("room", target)
        assertSame(target, registry.await("room", 1L))
    }
}
