package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** How a completed local tool call should be read. */
sealed interface LocalToolResultStatus {
    /** The tool reported a confirmed success. */
    data object Success : LocalToolResultStatus

    /** The tool reported a definite failure; nothing is left undecided. */
    data class Failed(val message: String) : LocalToolResultStatus

    /**
     * The tool may have acted, but the result was never confirmed. Callers must show this to the
     * user and must not replay the operation.
     */
    data class Unconfirmed(val message: String) : LocalToolResultStatus
}

/**
 * Reads local tool results.
 *
 * The rule that matters: a payload is only treated as a success when it says so. An empty result,
 * malformed JSON, or a payload that contradicts itself (`ok=false` next to `outcome=success`) is
 * never success. Raw payloads that were never Nexus envelopes — the YAML from `screen_content` and
 * `search_nodes`, or a custom tool's output — keep their previous non-failure reading, because
 * they carry no verdict either way and treating them as failures would break observation.
 */
object LocalToolResultClassifier {
    /** Legacy entry point: the failure message for a tool result, or null when it is not a failure. */
    fun failureMessage(resultJson: String?): String? {
        return when (val status = status(resultJson)) {
            is LocalToolResultStatus.Failed -> status.message
            is LocalToolResultStatus.Unconfirmed -> status.message
            LocalToolResultStatus.Success -> null
        }
    }

    fun status(resultJson: String?): LocalToolResultStatus {
        val raw = resultJson ?: return emptyResult()
        val text = raw.trim()
        if (text.isEmpty()) return emptyResult()

        BuiltinToolResult.fromJsonString(text)?.let { return it.toStatus() }

        val result = parseJsonObject(text)
            ?: return if (looksLikeJson(text)) {
                LocalToolResultStatus.Failed("Tool returned malformed JSON, so its result is unconfirmed.")
            } else {
                plainTextStatus(text)
            }

        result.structuredErrorMessage()?.let { return LocalToolResultStatus.Failed(it) }

        val explicitOk = result["ok"]?.jsonPrimitive?.booleanOrNull
        if (explicitOk == false) {
            return LocalToolResultStatus.Failed(result.statusMessage() ?: "Tool returned ok=false.")
        }

        val exitCode = result["exit_code"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull()
        if (exitCode != null && exitCode != 0) {
            return LocalToolResultStatus.Failed(result.nonZeroExitMessage(exitCode))
        }

        return LocalToolResultStatus.Success
    }

    private fun BuiltinToolResult.toStatus(): LocalToolResultStatus {
        return when (outcome) {
            ToolOutcome.SUCCESS -> LocalToolResultStatus.Success
            ToolOutcome.FAILURE -> LocalToolResultStatus.Failed(
                message.takeIf { it.isNotBlank() } ?: "Tool returned ok=false."
            )
            ToolOutcome.UNKNOWN -> {
                val detail = message.takeIf { it.isNotBlank() }
                    ?: "The tool did not confirm whether its action took effect."
                val suffix = hint.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                LocalToolResultStatus.Unconfirmed("$detail$suffix")
            }
        }
    }

    private fun emptyResult(): LocalToolResultStatus {
        return LocalToolResultStatus.Failed(
            "Tool returned no result, so nothing could be confirmed."
        )
    }

    /**
     * Plain text from a tool that never produced a JSON envelope. `error: ...` is the shape the
     * raw search/observation tools use for their own errors; anything else is opaque output that
     * carries no verdict.
     */
    private fun plainTextStatus(text: String): LocalToolResultStatus {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.startsWith("error:", ignoreCase = true)) {
            return LocalToolResultStatus.Failed(firstLine.substringAfter(':').trim())
        }
        return LocalToolResultStatus.Success
    }

    /** True when the payload clearly meant to be JSON, so failing to parse it is a real problem. */
    private fun looksLikeJson(text: String): Boolean {
        return text.startsWith("{") || text.startsWith("[")
    }

    private fun parseJsonObject(value: String): JsonObject? {
        return runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.structuredErrorMessage(): String? {
        val error = this["error"] as? JsonObject ?: return null
        val code = error["code"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return error["message"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: code
    }

    private fun JsonObject.nonZeroExitMessage(exitCode: Int): String {
        return statusMessage() ?: "Command completed with non-zero exit code $exitCode."
    }

    private fun JsonObject.statusMessage(): String? {
        return listOf("stderr", "message", "code")
            .firstNotNullOfOrNull { key ->
                this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
    }
}
