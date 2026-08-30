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

class BundledXiaoai507012002ConfigTest {
    @Test
    fun exactStableConfig_matchesAuditedDexTargets() {
        val file = sequenceOf(
            File("src/main/assets/hooks/com.miui.voiceassist/507012002/config.json"),
            File("app/src/main/assets/hooks/com.miui.voiceassist/507012002/config.json"),
        ).firstOrNull(File::isFile) ?: error("bundled XiaoAi 507012002 config missing")

        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("com.miui.voiceassist", root.getValue("package_name").jsonPrimitive.content)
        assertEquals(507012002L, root.getValue("version_code").jsonPrimitive.content.toLong())
        assertFalse(root.getValue("is_beta").jsonPrimitive.boolean)

        val actions = root.getValue("config").jsonObject.getValue("actions").jsonObject
        val dispatcherActions = listOf(
            "capture_response_target",
            "block_native_text_stream",
            "block_native_tts_stream",
            "block_native_instruction_whitelist",
            "render_text_stream_card",
        )
        dispatcherActions.forEach { actionName ->
            val target = actions.getValue(actionName).jsonObject.getValue("target").jsonObject
            assertEquals("cb0.db", target.getValue("owner_class").jsonPrimitive.content)
            assertEquals("A0", target.getValue("method_name").jsonPrimitive.content)
            assertEquals(
                listOf("com.xiaomi.ai.api.common.Instruction"),
                target.getValue("param_types").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("void", target.getValue("return_type").jsonPrimitive.content)
        }

        assertTarget(
            actions = actions,
            actionName = "capture_input",
            ownerClass = "com.xiaomi.voiceassistant.instruction.base.OperationManager",
            methodName = "setQueryInfo",
            paramTypes = listOf("java.lang.String", "java.lang.String", "org.json.JSONObject"),
            returnType = "void",
        )
        assertTarget(
            actions = actions,
            actionName = "block_native_tts_playback",
            ownerClass = "cb0.n9",
            methodName = "onProcessOp",
            paramTypes = listOf("g90.l"),
            returnType = "boolean",
        )
        assertTarget(
            actions = actions,
            actionName = "render_tts",
            ownerClass = "com.xiaomi.voiceassistant.instruction.card.TemplateReactNativeCard",
            methodName = "p1",
            paramTypes = listOf("org.json.JSONObject"),
            returnType = "void",
        )

        assertFalse(file.readText().contains("cb0.eb"))
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
