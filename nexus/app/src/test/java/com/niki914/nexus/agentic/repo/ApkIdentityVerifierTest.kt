package com.niki914.nexus.agentic.repo

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkIdentityVerifierTest {
    @After
    fun clearCache() {
        ApkIdentityVerifier.clearCacheForTest()
    }

    @Test
    fun matchesExactReadableApkBytes() {
        val file = tempFile("abc")
        try {
            assertTrue(
                ApkIdentityVerifier.matches(
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    file.absolutePath,
                )
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsDifferentBytesForSameConfiguredVersion() {
        val file = tempFile("ported-xiaoai")
        try {
            assertFalse(
                ApkIdentityVerifier.matches(
                    "dd75a0d9b1c0803906eafe72126dc44a150f16337e26c8fcf53304f13d74140b",
                    file.absolutePath,
                )
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsMissingMalformedOrUnreadableIdentity() {
        val file = tempFile("abc")
        try {
            assertFalse(ApkIdentityVerifier.matches("", file.absolutePath))
            assertFalse(ApkIdentityVerifier.matches("not-a-sha256", file.absolutePath))
            assertFalse(ApkIdentityVerifier.matches("a".repeat(64), null))
            assertFalse(ApkIdentityVerifier.matches("a".repeat(64), file.parent))
            assertFalse(ApkIdentityVerifier.matches("a".repeat(64), file.absolutePath + ".missing"))
        } finally {
            file.delete()
        }
    }

    private fun tempFile(content: String): File =
        File.createTempFile("nexus-apk-identity-", ".apk").apply { writeText(content) }
}
