package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesMcpProvisionerTest {
    @Test
    fun normalizeToken_acceptsExactly64HexAndNormalizesCase() {
        val upper = "AB".repeat(32)
        assertEquals(upper.lowercase(), HermesMcpProvisioner.normalizeToken("\n$upper\n"))
    }

    @Test
    fun normalizeToken_rejectsMalformedOrExtraContent() {
        assertNull(HermesMcpProvisioner.normalizeToken(null))
        assertNull(HermesMcpProvisioner.normalizeToken("a".repeat(63)))
        assertNull(HermesMcpProvisioner.normalizeToken("g".repeat(64)))
        assertNull(HermesMcpProvisioner.normalizeToken("a".repeat(64) + "\nextra"))
    }

    @Test
    fun withToken_createsEnabledLoopbackHermesServer() {
        val token = "a".repeat(64)
        val server = HermesMcpProvisioner.withToken(null, token)
        requireNotNull(server)
        assertEquals(HermesMcpProvisioner.HERMES_NAME, server.name)
        assertEquals(HermesMcpProvisioner.HERMES_URL, server.url)
        assertTrue(server.enabled)
        assertEquals("Bearer $token", server.headers["Authorization"])
    }

    @Test
    fun withToken_replacesAuthorizationCaseInsensitivelyAndPreservesOtherHeaders() {
        val existing = RuntimeMcpServer(
            name = "Hermes",
            url = HermesMcpProvisioner.HERMES_URL,
            enabled = false,
            headers = mapOf(
                "authorization" to "Bearer stale",
                "X-Trace" to "keep",
            ),
        )
        val token = "b".repeat(64)
        val updated = HermesMcpProvisioner.withToken(existing, token)
        requireNotNull(updated)
        assertEquals(false, updated.enabled)
        assertEquals("keep", updated.headers["X-Trace"])
        assertEquals("Bearer $token", updated.headers["Authorization"])
        assertEquals(2, updated.headers.size)
    }
}
