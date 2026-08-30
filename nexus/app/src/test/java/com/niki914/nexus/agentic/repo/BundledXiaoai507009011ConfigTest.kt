package com.niki914.nexus.agentic.repo

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BundledXiaoai507009011ConfigTest {
    @Test
    fun officialBaselineConfig_matchesAuditedDexTargets() {
        val file = sequenceOf(
            File("src/main/assets/hooks/com.miui.voiceassist/507009011/config.json"),
            File("app/src/main/assets/hooks/com.miui.voiceassist/507009011/config.json"),
        ).firstOrNull(File::isFile) ?: error("bundled XiaoAi 507009011 config missing")

        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("com.miui.voiceassist", root.getValue("package_name").jsonPrimitive.content)
        assertEquals(507009011L, root.getValue("version_code").jsonPrimitive.content.toLong())
        assertEquals(
            "dd75a0d9b1c0803906eafe72126dc44a150f16337e26c8fcf53304f13d74140b",
            root.getValue("apk_sha256").jsonPrimitive.content,
        )
        assertFalse(root.getValue("is_beta").jsonPrimitive.boolean)

        val actions = root.getValue("config").jsonObject.getValue("actions").jsonObject
        listOf(
            "capture_response_target",
            "block_native_text_stream",
            "block_native_tts_stream",
            "block_native_instruction_whitelist",
            "render_text_stream_card",
        ).forEach { actionName ->
            assertTarget(
                actions,
                actionName,
                "qb0.ua",
                "z0",
                listOf("com.xiaomi.ai.api.common.Instruction"),
                "void",
            )
        }

        assertTarget(
            actions,
            "capture_input",
            "com.xiaomi.voiceassistant.instruction.base.OperationManager",
            "setQueryInfo",
            listOf("java.lang.String", "java.lang.String", "org.json.JSONObject"),
            "void",
        )
        assertTarget(
            actions,
            "block_native_tts_playback",
            "qb0.f9",
            "onProcessOp",
            listOf("v90.l"),
            "boolean",
        )
        assertTarget(
            actions,
            "render_tts",
            "fa0.ac",
            "y1",
            listOf("org.json.JSONObject"),
            "void",
        )

        val detach = actions.getValue("float_screen_detach").jsonObject
        val detachTarget = detach.getValue("target").jsonObject
        assertEquals(
            "com.xiaomi.voiceassistant.mainui.board.FloatViewRootLayout",
            detachTarget.getValue("owner_class").jsonPrimitive.content,
        )
        assertEquals("onDetachedFromWindow", detachTarget.getValue("method_name").jsonPrimitive.content)
        val resume = detach.getValue("business").jsonObject.getValue("resume_target").jsonObject
        assertEquals(
            "com.xiaomi.voiceassistant.TabHostMainActivity",
            resume.getValue("owner_class").jsonPrimitive.content,
        )
        assertEquals("onResume", resume.getValue("method_name").jsonPrimitive.content)
    }

    private fun assertTarget(
        actions: kotlinx.serialization.json.JsonObject,
        actionName: String,
        ownerClass: String,
        methodName: String,
        paramTypes: List<String>,
        returnType: String,
    ) {
        val target = actions.getValue(actionName).jsonObject.getValue("target").jsonObject
        assertEquals(ownerClass, target.getValue("owner_class").jsonPrimitive.content)
        assertEquals(methodName, target.getValue("method_name").jsonPrimitive.content)
        assertEquals(paramTypes, target.getValue("param_types").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(returnType, target.getValue("return_type").jsonPrimitive.content)
    }
}
