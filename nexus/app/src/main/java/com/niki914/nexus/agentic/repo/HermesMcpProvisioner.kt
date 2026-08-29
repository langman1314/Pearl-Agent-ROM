package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object HermesMcpProvisioner {
    internal const val HERMES_NAME = "Hermes"
    internal const val HERMES_URL = "http://127.0.0.1:51338/mcp"
    internal const val TOKEN_FILE = "/data/adb/pearl-agent/data/config/mcp-token"
    private const val AUTHORIZATION = "Authorization"
    private const val READ_TIMEOUT_SECONDS = 5L
    private val tokenPattern = Regex("^[0-9a-f]{64}$")

    suspend fun provision(): Boolean {
        val token = withContext(Dispatchers.IO) { readRootToken() } ?: return false
        val servers = XRepo.mcp.list()
        val existing = servers.firstOrNull { it.url == HERMES_URL }
        if (existing == null && servers.any { it.name == HERMES_NAME }) {
            // Respect a user-owned server that already uses the reserved display
            // name with another URL. Never silently repoint custom configuration.
            return false
        }
        val updated = withToken(existing, token) ?: return false
        XRepo.mcp.save(updated)
        return true
    }

    internal fun normalizeToken(raw: String?): String? {
        val token = raw?.trim()?.lowercase() ?: return null
        return token.takeIf(tokenPattern::matches)
    }

    internal fun withToken(existing: RuntimeMcpServer?, rawToken: String?): RuntimeMcpServer? {
        val token = normalizeToken(rawToken) ?: return null
        val current = existing ?: RuntimeMcpServer(
            name = HERMES_NAME,
            url = HERMES_URL,
            enabled = true,
        )
        val headers = current.headers
            .filterKeys { !it.equals(AUTHORIZATION, ignoreCase = true) }
            .toMutableMap()
            .apply { put(AUTHORIZATION, "Bearer $token") }
        return current.copy(headers = headers.toSortedMap())
    }

    private fun readRootToken(): String? {
        val process = runCatching {
            ProcessBuilder("su", "-c", "cat $TOKEN_FILE")
                .redirectErrorStream(false)
                .start()
        }.getOrNull() ?: return null

        return try {
            if (!process.waitFor(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (output.length > 128) null else normalizeToken(output)
            }
        } catch (_: Exception) {
            process.destroyForcibly()
            null
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
