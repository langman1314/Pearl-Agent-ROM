package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.mod.WebSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSettingsHookPolicyTest {
    @Test
    fun exactConfig_canInstallHooks() {
        assertTrue(success(isFallback = false).canInstallHooks())
    }

    @Test
    fun fallbackConfig_cannotInstallHooks() {
        assertFalse(success(isFallback = true).canInstallHooks())
    }

    @Test
    fun requestFailure_cannotInstallHooks() {
        assertFalse(
            WebSettingsResult.RequestFailed(WebSettingsFailureReason.UnsupportedVersion)
                .canInstallHooks()
        )
    }

    private fun success(isFallback: Boolean): WebSettingsResult.Success {
        val settings = WebSettings(
            Json.parseToJsonElement(
                """{"package_name":"com.miui.voiceassist","version_code":507013003,"config":{"actions":{}}}"""
            ).jsonObject
        )
        return WebSettingsResult.Success(
            settings = settings,
            requestedVersionCode = 507013003,
            resolvedVersionCode = if (isFallback) 507013002 else 507013003,
            source = WebSettingsSource.Bundled,
            isFallbackVersion = isFallback,
        )
    }
}