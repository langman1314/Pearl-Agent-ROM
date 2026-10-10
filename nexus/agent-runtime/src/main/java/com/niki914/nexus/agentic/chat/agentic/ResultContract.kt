package com.niki914.nexus.agentic.chat.agentic

import kotlinx.serialization.json.Json
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
     * The call had already been dispatched when it lost its verdict — a timeout, an internal error,
     * a terminated runtime, a broken channel. The action may have landed, so this proves nothing
     * about the outcome and must never be reported as a plain retryable failure.
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
 * The credential rules that matter:
 *
 * - a synchronous terminal result is a completion only when `exit_code` is a valid integer; `0` and
 *   non-zero are explicit success and failure, and a process exiting proves nothing about the
 *   phone-side goal;
 * - a structured `error` is read *first*, because a timeout payload also carries a session handle
 *   and partial output: losing its verdict to a `session` key would report an unconfirmed action as
 *   a finished one;
 * - an async receipt (`async_id`) or a running poll (`stdout_partial`) is in flight, even when it
 *   carries `elapsed_seconds`; a bare `accepted` proves only that the request was taken, so it is
 *   readable but undecided. An interactive write receipt that reports the bytes it wrote did finish
 *   that write;
 * - a null or blank session/identity handle is not a session;
 * - `closed=false` is not a completed close.
 */
object ResultContract {
    fun read(resultJson: String?): PayloadReading {
        val text = resultJson?.trim().orEmpty()
        if (text.isEmpty()) return PayloadReading.Malformed
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return if (text.startsWith("{") || text.startsWith("[")) {
                PayloadReading.Malformed
            } else {
                PayloadReading.Opaque
            }
        return readObject(obj)
    }

    fun readObject(obj: JsonObject): PayloadReading {
        // A structured error is a verdict about the call itself, so it is read before anything else.
        // It is also the only way to tell a dispatched-but-unconfirmed timeout from a call that was
        // refused outright: both carry `error`, and only the code separates them.
        obj.errorCode()?.let { code ->
            return if (code in POST_DISPATCH_INDETERMINATE_CODES) {
                PayloadReading.Indeterminate
            } else {
                PayloadReading.Refused
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

        // An interactive write receipt proves the write it reports: the byte count is the evidence,
        // so a bare `accepted` without it is deliberately *not* covered here.
        if (obj.containsKey("bytes_written")) {
            return if (obj.integerField("bytes_written") != null) {
                PayloadReading.Completed(failed = false)
            } else {
                PayloadReading.InvalidCredential
            }
        }

        // Opening a session and observing the screen are completions in their own right — but only
        // with a usable handle: `{"session":null}` is the absence of a session, not a session.
        if (obj.hasUsableHandle("session") || obj.hasUsableHandle("identity")) {
            return PayloadReading.Completed(failed = false)
        }
        if (obj.hasUsableHandle("snapshot_id")) return PayloadReading.Completed(failed = false)

        return PayloadReading.Opaque
    }

    /**
     * True when a payload is a *decided* call: a proven completion, or a proven pre-dispatch
     * refusal. Only these may be handed back verbatim — anything else, including a readable but
     * verdict-less body, leaves a side effect unaccounted for.
     */
    fun provesDecided(resultJson: String?): Boolean = when (read(resultJson)) {
        is PayloadReading.Completed, PayloadReading.Refused -> true
        PayloadReading.Indeterminate,
        PayloadReading.InFlight,
        PayloadReading.InvalidCredential,
        PayloadReading.Malformed,
        PayloadReading.Opaque,
        -> false
    }

    private fun JsonObject.errorCode(): String? =
        ((this["error"] as? JsonObject)?.get("code") as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()

    private fun JsonObject.hasUsableHandle(key: String): Boolean =
        ((this[key] as? JsonPrimitive)?.contentOrNull)?.isNotBlank() == true

    private fun JsonObject.integerField(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        // A quoted "0" is a string, not an integer exit code.
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun JsonObject.booleanField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    /**
     * Error codes that mean the call had already been handed to the device when it lost its verdict.
     * Everything else — a blocked command, a missing session, an invalid request — was refused
     * before dispatch. Shared with the classifier so the two cannot disagree.
     */
    val POST_DISPATCH_INDETERMINATE_CODES = setOf(
        "TIMEOUT",
        "INTERNAL_ERROR",
        "RUNTIME_TERMINATED",
        "SSH_CHANNEL_FAILED",
    )
}
