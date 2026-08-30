package com.niki914.nexus.agentic.repo

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Fail-closed byte identity gate for host APK-specific hook configurations. */
object ApkIdentityVerifier {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    @Volatile
    private var cached: CachedDigest? = null

    fun matches(expectedSha256: String, sourcePath: String?): Boolean {
        val expected = expectedSha256.trim().lowercase()
        if (!sha256Pattern.matches(expected) || sourcePath.isNullOrBlank()) return false

        val file = File(sourcePath)
        if (!file.isFile || !file.canRead()) return false

        val identity = FileIdentity(
            canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath },
            length = file.length(),
            lastModified = file.lastModified(),
        )
        val digest = cached
            ?.takeIf { it.identity == identity }
            ?.sha256
            ?: runCatching { sha256(file) }
                .getOrNull()
                ?.also { cached = CachedDigest(identity, it) }
            ?: return false
        return digest == expected
    }

    internal fun clearCacheForTest() {
        cached = null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private data class FileIdentity(
        val canonicalPath: String,
        val length: Long,
        val lastModified: Long,
    )

    private data class CachedDigest(
        val identity: FileIdentity,
        val sha256: String,
    )
}
