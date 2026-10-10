package com.niki914.nexus.agentic.chat.agentic.buildin

import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a tool call resolved.
 *
 * [UNKNOWN] is the important one: the action may have reached the phone, but Nexus could not
 * confirm what happened (timeout, lost shell session, cancelled round). It is reported as
 * `ok=false` for backward compatibility, but it is *not* a failure the caller may replay —
 * the operation must not be re-sent, because it may already have taken effect.
 */
enum class ToolOutcome(val wireName: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    UNKNOWN("unknown"),
    ;

    val isSuccess: Boolean get() = this == SUCCESS

    companion object {
        fun fromWireName(value: String?): ToolOutcome? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

abstract class BuiltinTool {
    abstract val name: String

    open val description: String
        get() = "Builtin tool: $name"

    open val defaultEnabled: Boolean = false

    abstract fun configure(config: LocalToolConfig)

    abstract suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult
}

interface RawJsonBuiltinTool {
    suspend fun invokeRawJson(request: BuiltinToolRequest): String
}

data class BuiltinToolRequest(
    val name: String,
    val argumentsJson: String,
    /** Stable per-call id from the LLM session; used to dedupe and to latch unknown outcomes. */
    val callId: String? = null,
)

data class BuiltinToolResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val hint: String,
    val fieldErrors: Map<String, String>,
    val data: JsonObject,
    val outcome: ToolOutcome = if (ok) ToolOutcome.SUCCESS else ToolOutcome.FAILURE,
) {
    fun toJsonString(): String {
        // `ok` is the field older callers read, so it must never claim success for an outcome that
        // is not a success — an `unknown` result is always serialized as `ok=false`.
        val effectiveOk = ok && outcome.isSuccess
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(effectiveOk),
                "outcome" to JsonPrimitive(outcome.wireName),
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message),
                "hint" to JsonPrimitive(hint),
                "field_errors" to JsonObject(
                    fieldErrors.mapValues { (_, value) -> JsonPrimitive(value) }
                ),
                "data" to data,
            )
        ).toString()
    }

    companion object {
        /** Error code used when an action's real outcome is indeterminate. */
        const val OUTCOME_UNKNOWN_CODE = "ACTION_OUTCOME_UNKNOWN"

        /** Hint shown to the agent so it does not replay an unconfirmed action. */
        const val OUTCOME_UNKNOWN_HINT =
            "结果未确认，未重复操作。请重新观察屏幕后再决定下一步。"

        private val ENVELOPE_KEYS = setOf("ok", "outcome", "hint", "field_errors", "data")

        fun success(
            message: String,
            data: JsonObject = JsonObject(emptyMap()),
            hint: String = "",
        ): BuiltinToolResult {
            return BuiltinToolResult(
                ok = true,
                code = "OK",
                message = message,
                hint = hint,
                fieldErrors = emptyMap(),
                data = data,
                outcome = ToolOutcome.SUCCESS,
            )
        }

        fun failure(
            code: String,
            message: String,
            hint: String = "",
            fieldErrors: Map<String, String> = emptyMap(),
            data: JsonObject = JsonObject(emptyMap()),
        ): BuiltinToolResult {
            return BuiltinToolResult(
                ok = false,
                code = code,
                message = message,
                hint = hint,
                fieldErrors = fieldErrors,
                data = data,
                outcome = ToolOutcome.FAILURE,
            )
        }

        /**
         * The action was dispatched but its effect could not be confirmed. Serialized as
         * `ok=false` so legacy callers keep working, with a stable [OUTCOME_UNKNOWN_CODE] and a
         * hint telling the agent not to replay it.
         */
        fun unknown(
            code: String = OUTCOME_UNKNOWN_CODE,
            message: String,
            hint: String = OUTCOME_UNKNOWN_HINT,
            data: JsonObject = JsonObject(emptyMap()),
        ): BuiltinToolResult {
            return BuiltinToolResult(
                ok = false,
                code = code,
                message = message,
                hint = hint,
                fieldErrors = emptyMap(),
                data = data,
                outcome = ToolOutcome.UNKNOWN,
            )
        }

        /**
         * Decodes a Nexus tool envelope, including payloads written before `outcome` existed.
         *
         * Returns null when [json] is not a Nexus envelope at all (plain text, YAML, or a raw
         * terminal payload), so callers can fall back to their own classification.
         *
         * Decoding is total: a missing field, a field of the wrong type (`{"ok":{}}`), an
         * unrecognized `outcome` name, or a payload that contradicts itself (`ok=false` next to
         * `outcome=success`, or `ok=true` next to `outcome=unknown`) never throws and never reads
         * as success. Anything undecidable resolves to [ToolOutcome.UNKNOWN], which is reported as
         * `ok=false` — the caller must not treat it as a failure it may replay either.
         */
        fun fromJsonString(json: String?): BuiltinToolResult? {
            val text = json?.trim().orEmpty()
            if (text.isEmpty()) return null
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return null
            if (obj.keys.none { it in ENVELOPE_KEYS }) return null

            // Read via `as? JsonPrimitive` rather than `jsonPrimitive`: the latter throws for an
            // object/array value, which would crash the decoder on a caller-supplied payload.
            val okValue = obj["ok"] as? JsonPrimitive
            val ok = okValue?.booleanOrNull
            val code = obj.stringField("code").orEmpty()

            val outcomeElement = obj["outcome"]
            val rawOutcome = (outcomeElement as? JsonPrimitive)?.contentOrNull
            val wireOutcome = ToolOutcome.fromWireName(rawOutcome)
            // Declared but unusable: wrong type, blank, or an outcome name we do not recognize.
            val outcomeUnusable = outcomeElement != null && wireOutcome == null
            // `ok` is present but carries no usable boolean (object, array, null, "yes"). An
            // unusable verdict is never a success, and a declared `outcome=success` cannot
            // override it: the two fields disagree and the payload proves nothing.
            val okDeclaredButUnusable = obj.containsKey("ok") && ok == null

            val resolved = when {
                // Contradictions are indeterminate, never success.
                ok == false && wireOutcome == ToolOutcome.SUCCESS -> ToolOutcome.UNKNOWN
                ok == true && wireOutcome != null && !wireOutcome.isSuccess -> ToolOutcome.UNKNOWN
                okDeclaredButUnusable -> ToolOutcome.UNKNOWN
                wireOutcome != null -> wireOutcome
                outcomeUnusable -> ToolOutcome.UNKNOWN
                ok == true -> ToolOutcome.SUCCESS
                ok == false -> ToolOutcome.FAILURE
                code.equals("OK", ignoreCase = true) -> ToolOutcome.SUCCESS
                else -> ToolOutcome.UNKNOWN
            }

            return BuiltinToolResult(
                // `ok` always agrees with the outcome, so `unknown` is externally `ok=false`.
                ok = resolved.isSuccess,
                code = code,
                message = obj.stringField("message").orEmpty(),
                hint = obj.stringField("hint").orEmpty(),
                fieldErrors = (obj["field_errors"] as? JsonObject)
                    ?.mapValues { (_, value) ->
                        (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                    }
                    .orEmpty(),
                data = obj["data"] as? JsonObject ?: JsonObject(emptyMap()),
                outcome = resolved,
            )
        }

        private fun JsonObject.stringField(key: String): String? {
            return (this[key] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }
    }
}
