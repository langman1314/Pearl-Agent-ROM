package com.niki914.nexus.agentic.chat.agentic.buildin.impl

object VoiceUriSafetyPolicy {
    fun isAllowed(uri: String): Boolean {
        val separator = uri.indexOf(':')
        if (separator <= 0) return false
        val scheme = uri.substring(0, separator).lowercase()
        return scheme == "http" || scheme == "https"
    }
}
