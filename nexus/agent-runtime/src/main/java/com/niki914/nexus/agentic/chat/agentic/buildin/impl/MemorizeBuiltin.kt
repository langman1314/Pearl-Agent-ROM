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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class MemorizeBuiltin : BuiltinTool(), RawJsonBuiltinTool, RawOutcomeProducing {
    override val name: String = "memorize"

    override val description: String = "Add a concise item to persistent memory."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("content") {
            description = "The non-blank memory item to persist for future turns."
            required = true
        }
        config.rawJsonSchema(MEMORIZE_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "memorize must be executed through invokeRawJson().",
            hint = "Use BuiltinToolExecutor to execute this builtin.",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        return invokeRawJsonWithEvidence(request).resultJson
    }

    /**
     * The real exit path.
     *
     * Parsing and the blank-content check happen before the settings gateway is touched at all, so
     * those exits provably changed nothing and stay retryable. The write itself is a different
     * question: once it has been attempted, a throw says nothing about whether it landed.
     */
    override suspend fun invokeRawJsonWithEvidence(
        request: BuiltinToolRequest,
    ): RawToolOutcome {
        val content = try {
            parseContent(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return refusal(
                BuiltinToolResult.failure(
                    code = "INVALID_ARGUMENTS_JSON",
                    message = "memorize arguments must be a JSON object with a content field.",
                    hint = """Example: {"content":"The user prefers concise answers."}""",
                    fieldErrors = mapOf(
                        "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                    ),
                ).toJsonString()
            )
        }
        if (content.isBlank()) {
            return refusal(
                BuiltinToolResult.failure(
                    code = "MISSING_REQUIRED_FIELD",
                    message = "memorize requires non-blank content.",
                    hint = "Provide a concise memory item in the content field.",
                    fieldErrors = mapOf("content" to "Field 'content' must not be blank."),
                ).toJsonString()
            )
        }

        return try {
            RuntimeEnvironment.awaitSettingsGateway().addMemory(content)
            // The gateway returned normally, so the write really completed.
            rawOutcome(operation = MEMORIZE, stage = DispatchStage.DISPATCHED_CONFIRMED, json = SUCCESS_JSON)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            // The write was attempted and then threw: it may have landed, so reporting a retryable
            // failure would invite writing the same memory twice.
            rawOutcome(
                operation = MEMORIZE,
                stage = DispatchStage.DISPATCHED_UNCONFIRMED,
                json = BuiltinToolResult.unknown(
                    message = "The memory write was attempted but did not confirm whether it " +
                        "landed: ${throwable.message ?: throwable::class.java.simpleName}.",
                    hint = "Check whether the memory was added before writing it again. " +
                        BuiltinToolResult.OUTCOME_UNKNOWN_HINT,
                ).toJsonString(),
            )
        }
    }

    /** A rejection proven to have happened before the settings gateway was touched. */
    private fun refusal(json: String) =
        rawOutcome(operation = MEMORIZE, stage = DispatchStage.NOT_DISPATCHED, json = json)

    private fun parseContent(argumentsJson: String): String {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }

    companion object {
        private const val MEMORIZE = "memorize"
        private const val SUCCESS_JSON = """{"ok":true}"""
        private const val MEMORIZE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "content": {
                  "type": "string",
                  "description": "The non-blank memory item to persist for future turns."
                }
              },
              "required": ["content"]
            }
        """
    }
}
