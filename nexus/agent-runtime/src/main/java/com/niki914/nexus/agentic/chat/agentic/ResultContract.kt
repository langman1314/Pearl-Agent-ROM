package com.niki914.nexus.agentic.chat.agentic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What a tool payload proves about the operation it came from. */
sealed interface PayloadReading {
    /** The tool proved the operation reached a verdict. [failed] tells which one. */
    data class Completed(val failed: Boolean, val exitCode: Int? = null) : PayloadReading

    /**
     * The tool proved it refused the call *before* dispatching anything: a blocked command, an
     * unknown session, a busy session, an invalid request, an async result that was not found.
     * Nothing reached the phone, so this is a decided failure the caller may retry.
     */
    data object Refused : PayloadReading

    /**
     * The call may already have been dispatched and its real effect is unproven: a timeout, an
     * internal error, a terminated runtime, a broken channel, a connection or authorisation failure
     * *after* the session existed, an error code this version has never seen, a write that was only
     * accepted into a terminal's input — or a payload whose completion credential cannot carry the
     * meaning it is being given.
     */
    data object Indeterminate : PayloadReading

    /** The work was accepted or is still running: it has not finished, so it proves nothing yet. */
    data object InFlight : PayloadReading

    /**
     * The payload carries a completion credential that is unusable — a missing, quoted, fractional
     * or object-valued `exit_code`, or `closed=false`. It claims a completion it cannot prove.
     */
    data object InvalidCredential : PayloadReading

    /** The payload looks like JSON but does not parse, so it proves nothing. */
    data object Malformed : PayloadReading

    /**
     * A well-formed payload with no completion credential either way — a plain observation, an
     * opaque tool body, or a legacy error string. It is *readable*, and it claims nothing. For a
     * read-only tool that is enough; for a side effect it proves nothing, so callers must not treat
     * it as a finished action.
     */
    data object Opaque : PayloadReading
}

/**
 * The result contract shared by the dispatcher, the guard and the classifier.
 *
 * Completion is decided by *what the tool exit reports*, never inferred from the presence of a key
 * name. One reader answers for every consumer, so the dispatcher's first return, the guard's
 * bookkeeping, the replay of a call id and the classifier that feeds the UI cannot drift apart.
 *
 * Two rules carry the safety of the whole boundary:
 *
 * 1. **An unproven failure is unknown, not a refusal.** Only the codes the production refusal exits
 *    actually emit are read as "nothing was dispatched" ([PRE_DISPATCH_REFUSAL_CODES]); every other
 *    code — a connection lost mid-command, an authorisation that expired, a code this version has
 *    never heard of, a code of the wrong type — is [PayloadReading.Indeterminate]. Defaulting the
 *    unknown case to "refused" is precisely what lets a command that already reached the device be
 *    retried.
 *
 * 2. **A credential is only a credential for the tool and the operation that emit it.** A session
 *    handle comes from a terminal, a snapshot handle from an observation, and an interactive write
 *    receipt reports that input was *written* — not that the command, let alone the phone goal,
 *    finished. A key name alone never authorises the next side effect.
 */
object ResultContract {
    fun read(resultJson: String?, toolName: String? = null): PayloadReading {
        val text = resultJson?.trim().orEmpty()
        if (text.isEmpty()) return PayloadReading.Malformed
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return if (text.startsWith("{") || text.startsWith("[")) {
                PayloadReading.Malformed
            } else {
                PayloadReading.Opaque
            }
        return readObject(obj, toolName)
    }

    fun readObject(obj: JsonObject, toolName: String? = null): PayloadReading {
        // A structured error is a verdict about the call, so it is read before anything else. It is
        // also the only way to separate a refusal from a dispatched-but-unconfirmed failure: both
        // carry `error`, and only the code tells them apart.
        if (obj["error"] is JsonObject) {
            val code = errorCodeOf(obj["error"])
            return when {
                code != null && code in PRE_DISPATCH_REFUSAL_CODES -> PayloadReading.Refused
                // Everything else — a known transport/authorisation failure, an unrecognised code,
                // or a code of the wrong type — does not prove the call never dispatched.
                else -> PayloadReading.Indeterminate
            }
        }

        // In flight: the work was handed off as async, or is being polled while it still produces
        // output. `elapsed_seconds` does not turn either into a completion.
        if (obj.containsKey("stdout_partial") || obj.containsKey("stderr_partial")) {
            return PayloadReading.InFlight
        }
        if (obj.containsKey("async_id") && !obj.containsKey("exit_code")) {
            return PayloadReading.InFlight
        }

        // A synchronous completion is exactly a valid integer exit code.
        if (obj.containsKey("exit_code")) {
            val exitCode = obj.integerField("exit_code")
                ?: return PayloadReading.InvalidCredential
            return PayloadReading.Completed(failed = exitCode != 0, exitCode = exitCode)
        }

        if (obj.containsKey("closed")) {
            return when (obj.booleanField("closed")) {
                true -> PayloadReading.Completed(failed = false)
                else -> PayloadReading.InvalidCredential
            }
        }

        // An interactive write receipt proves the *write* and nothing beyond it: the bytes reached
        // the terminal's input, not the command's exit and not the phone-side goal. It stays
        // indeterminate so the barrier holds, while `bytes_written`/`sequence`/`replayed` stay
        // readable as diagnostics.
        if (obj.containsKey("bytes_written")) return PayloadReading.Indeterminate

        // A handle completes an action only in a tool that can emit it, and only as the right type:
        // `{"session":true}` is not a session, and a terminal handle reported by a node action is
        // not evidence that a terminal was opened.
        if (toolName in TERMINAL_TOOLS && obj.hasTextHandle("session", "identity")) {
            return PayloadReading.Completed(failed = false)
        }
        if (toolName in OBSERVATION_TOOLS && obj.hasSnapshotHandle()) {
            return PayloadReading.Completed(failed = false)
        }

        return PayloadReading.Opaque
    }

    /**
     * True when a payload is a *decided* call: a proven completion, or a proven pre-dispatch
     * refusal. Only these may be handed back verbatim — anything else, including a readable but
     * verdict-less body, leaves a side effect unaccounted for.
     */
    fun provesDecided(resultJson: String?, toolName: String? = null): Boolean =
        when (read(resultJson, toolName)) {
            is PayloadReading.Completed, PayloadReading.Refused -> true
            PayloadReading.Indeterminate,
            PayloadReading.InFlight,
            PayloadReading.InvalidCredential,
            PayloadReading.Malformed,
            PayloadReading.Opaque,
            -> false
        }

    /**
     * True only when a payload *proves* nothing was dispatched: a structured error whose code is one
     * of the production refusal exits. Shared so the guard and the classifier cannot disagree about
     * which errors the caller may retry.
     */
    fun refusalProven(obj: JsonObject): Boolean =
        errorCodeOf(obj["error"])?.let { it in PRE_DISPATCH_REFUSAL_CODES } == true

    /** The usable code of a structured `error` value, or null when it is absent or not text. */
    private fun errorCodeOf(error: JsonElement?): String? {
        val code = (error as? JsonObject)?.get("code") as? JsonPrimitive ?: return null
        return code.contentOrNull?.takeIf { it.isNotBlank() }?.uppercase()
    }

    private fun JsonObject.hasTextHandle(vararg keys: String): Boolean {
        return keys.any { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@any false
            // A number or boolean is not a handle, even though both serialise as primitives.
            primitive.isString && primitive.contentOrNull?.isNotBlank() == true
        }
    }

    /** A snapshot handle is an id: a non-blank string or a number, but never a boolean. */
    private fun JsonObject.hasSnapshotHandle(): Boolean {
        val element = this["snapshot_id"] as? JsonPrimitive ?: return false
        if (element.isString) return element.contentOrNull?.isNotBlank() == true
        return element.intOrNull != null
    }

    private fun JsonObject.integerField(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        // A quoted "0" is a string, not an integer exit code.
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun JsonObject.booleanField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    /** Tools that can open a terminal session, and so may legitimately report a session handle. */
    val TERMINAL_TOOLS = setOf("terminal", "ssh_terminal")

    /** Tools that observe the screen, and so may legitimately report a snapshot handle. */
    val OBSERVATION_TOOLS = setOf("screen_content", "search_nodes")

    /**
     * The error codes the production *refusal* exits emit, each meaning the call was rejected
     * before anything was handed to the device: a command blocked by policy, a session that does not
     * exist, an async result already read, a session already busy, or a malformed request.
     *
     * Every other code — the transport and authorisation failures, and anything unrecognised — is
     * read as indeterminate, because those payloads do not prove the call never dispatched.
     */
    val PRE_DISPATCH_REFUSAL_CODES = setOf(
        "COMMAND_BLOCKED",
        "SESSION_NOT_FOUND",
        "ASYNC_NOT_FOUND",
        "SESSION_BUSY",
        "INVALID_REQUEST",
    )

    /**
     * The codes known to mean the call had already been handed to the device when it lost its
     * verdict. Every code outside [PRE_DISPATCH_REFUSAL_CODES] is treated the same way, so an
     * unknown code can never be read as a refusal.
     */
    val POST_DISPATCH_INDETERMINATE_CODES = setOf(
        "TIMEOUT",
        "INTERNAL_ERROR",
        "RUNTIME_TERMINATED",
        "SSH_CHANNEL_FAILED",
    )
}
