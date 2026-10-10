package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.agentic.PayloadReading
import com.niki914.nexus.agentic.chat.agentic.ResultContract
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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

        // A refusal that happened before anything ran is an ordinary failure. A command that was
        // already dispatched and then timed out or lost its channel is *not*: its effect is
        // unknown, and showing it as a plain failure invites replaying an action that may already
        // have landed.
        val errorCode = (result["error"] as? JsonObject)
            ?.let { (it["code"] as? JsonPrimitive)?.contentOrNull }
            ?.uppercase()
        if (errorCode != null && errorCode in POST_DISPATCH_INDETERMINATE_CODES) {
            return LocalToolResultStatus.Unconfirmed(
                result.structuredErrorMessage()
                    ?: "The tool did not confirm whether its action took effect."
            )
        }

        result.structuredErrorMessage()?.let { return LocalToolResultStatus.Failed(it) }

        // `as? JsonPrimitive` rather than `jsonPrimitive`: the latter throws on an object or array
        // value, and a caller-supplied payload must never crash the classifier.
        val okElement = result["ok"]
        val explicitOk = (okElement as? JsonPrimitive)?.booleanOrNull
        if (explicitOk == false) {
            return LocalToolResultStatus.Failed(result.statusMessage() ?: "Tool returned ok=false.")
        }
        if (okElement != null && explicitOk == null) {
            // An `ok` that is present but unreadable is an unusable verdict: the call may already
            // have landed, so it must not read as a pass either.
            return LocalToolResultStatus.Unconfirmed(
                result.statusMessage()
                    ?: "Tool returned an unreadable ok field, so its result is unconfirmed."
            )
        }

        val exitCode = (result["exit_code"] as? JsonPrimitive)
            ?.contentOrNull
            ?.toIntOrNull()
        if (exitCode != null && exitCode != 0) {
            return LocalToolResultStatus.Failed(result.nonZeroExitMessage(exitCode))
        }

        // Consistent with the guard: a payload whose completion credential is present but unusable
        // (a quoted, fractional or object-valued exit_code, closed=false) claims a completion it
        // cannot prove, and an in-flight receipt has not finished. Neither may read as a success.
        when (ResultContract.readObject(result)) {
            is PayloadReading.Completed -> Unit
            PayloadReading.InFlight -> return LocalToolResultStatus.Unconfirmed(
                result.statusMessage()
                    ?: "The operation was accepted but has not finished, so its result is unconfirmed."
            )
            PayloadReading.InvalidCredential -> return LocalToolResultStatus.Unconfirmed(
                result.statusMessage()
                    ?: "Tool returned an unusable completion credential, so its result is unconfirmed."
            )
            PayloadReading.Malformed,
            PayloadReading.Opaque,
            -> Unit
        }

        // An object with no field at all says nothing about what happened, so it cannot confirm a
        // success. Objects that do carry tool-specific fields keep their previous reading: the raw
        // observation shapes are data, not verdicts, and failing them would break observation.
        if (result.isEmpty()) return emptyResult()

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
        val code = (error["code"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return (error["message"] as? JsonPrimitive)
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
                (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }
    }

    /**
     * Error codes that mean the command had already been handed to the device when it failed, so
     * its effect is unknown rather than absent. Anything else — a blocked command, a missing
     * session, an invalid request — was refused before dispatch and stays retryable.
     */
    private val POST_DISPATCH_INDETERMINATE_CODES = setOf(
        "TIMEOUT",
        "INTERNAL_ERROR",
        "RUNTIME_TERMINATED",
        "SSH_CHANNEL_FAILED",
    )
}
