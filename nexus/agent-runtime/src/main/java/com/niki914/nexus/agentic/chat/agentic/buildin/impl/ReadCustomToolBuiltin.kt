package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.DispatchStage
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.RawOutcomeProducing
import com.niki914.nexus.agentic.chat.agentic.buildin.RawToolOutcome
import com.niki914.nexus.agentic.chat.agentic.buildin.rawOutcome
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool

class ReadCustomToolBuiltin : BuiltinTool(), RawJsonBuiltinTool, RawOutcomeProducing {
    override val name: String = "read_custom_tool"

    override val description: String =
        "Read custom tool implementations, including command strings. Use only when tool implementation details are needed."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("name") {
            description = "Optional custom tool name. Omit to read all custom tool implementations."
            required = false
        }
        config.rawJsonSchema(READ_CUSTOM_TOOL_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "read_custom_tool must be executed through invokeRawJson().",
            hint = "Use BuiltinToolExecutor to execute this builtin.",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        return invokeRawJsonWithEvidence(request).resultJson
    }

    /**
     * The real exit path.
     *
     * Reading custom tool definitions changes nothing, so a failure here is a retryable failure
     * rather than an indeterminate side effect: it just never proved anything about the read. The
     * argument check happens before the settings gateway is touched, so it is the one exit that
     * provably never reached the runtime at all.
     */
    override suspend fun invokeRawJsonWithEvidence(
        request: BuiltinToolRequest,
    ): RawToolOutcome {
        val requestedName = try {
            parseName(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return refusal(
                BuiltinToolResult.failure(
                    code = "INVALID_ARGUMENTS_JSON",
                    message = "read_custom_tool arguments must be a JSON object with an optional name field.",
                    hint = """Example: {"name":"battery_status"} or {}""",
                    fieldErrors = mapOf(
                        "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                    ),
                ).toJsonString()
            )
        }

        return try {
            val tools = RuntimeEnvironment.awaitSettingsGateway().listCustomTools()
            // The read returned, so its result is a definite one — including "no such tool".
            decided(
                requestedName
                    ?.let { name -> singleToolResult(name, tools) }
                    ?: allToolsResult(tools)
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            // The read failed, but a failed read has not changed anything, so it stays an ordinary
            // retryable failure: refusing every later side effect would buy no safety here.
            decided(
                BuiltinToolResult.failure(
                    code = "SETTINGS_READ_FAILED",
                    message = "Failed to read custom tools: ${throwable.message ?: throwable::class.java.simpleName}.",
                    hint = "Retry after confirming the settings provider is available.",
                ).toJsonString()
            )
        }
    }

    /** A rejection proven to have happened before the settings gateway was touched. */
    private fun refusal(json: String) =
        rawOutcome(operation = READ_CUSTOM_TOOL, stage = DispatchStage.NOT_DISPATCHED, json = json)

    /** A read that really ran and really produced a result. */
    private fun decided(json: String) =
        rawOutcome(
            operation = READ_CUSTOM_TOOL,
            stage = DispatchStage.DISPATCHED_CONFIRMED,
            json = json,
        )

    private fun parseName(argumentsJson: String): String? {
        val element = try {
            Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun singleToolResult(name: String, tools: List<CustomTool>): String {
        val tool = tools.firstOrNull { it.name == name }
            ?: return BuiltinToolResult.failure(
                code = "CUSTOM_TOOL_NOT_FOUND",
                message = "Custom tool '$name' was not found.",
                hint = "Call read_custom_tool with no name to list available custom tools.",
                fieldErrors = mapOf("name" to "Unknown custom tool name."),
            ).toJsonString()
        return JsonObject(
            linkedMapOf<String, JsonElement>(
                "ok" to JsonPrimitive(true),
                "tool" to tool.toJsonObject(),
            )
        ).toString()
    }

    private fun allToolsResult(tools: List<CustomTool>): String {
        return JsonObject(
            linkedMapOf<String, JsonElement>(
                "ok" to JsonPrimitive(true),
                "tools" to JsonArray(tools.map { it.toJsonObject() }),
            )
        ).toString()
    }

    private fun CustomTool.toJsonObject(): JsonObject {
        return JsonObject(
            linkedMapOf(
                "name" to JsonPrimitive(name),
                "description" to JsonPrimitive(description),
                "command" to JsonPrimitive(command),
                "enabled" to JsonPrimitive(enabled),
            )
        )
    }

    companion object {
        private const val READ_CUSTOM_TOOL = "read_custom_tool"
        private const val READ_CUSTOM_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Optional custom tool name. Omit to read all custom tool implementations."
                }
              }
            }
        """
    }
}
