package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUriSafetyPolicyTest {
    @Test
    fun allowsOrdinaryWebNavigation() {
        assertTrue(VoiceUriSafetyPolicy.isAllowed("https://example.com/path"))
        assertTrue(VoiceUriSafetyPolicy.isAllowed("HTTP://example.com"))
    }

    @Test
    fun rejectsEffectfulAndCustomSchemes() {
        listOf(
            "tel:10086",
            "sms:10086?body=hello",
            "mailto:name@example.com",
            "geo:0,0",
            "intent://pay#Intent;scheme=app;end",
            "weixin://dl/business",
            "example.com/no-scheme",
        ).forEach { uri ->
            assertFalse(uri, VoiceUriSafetyPolicy.isAllowed(uri))
        }
    }
}
