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
     * opaque tool body, or a legacy error string. It stays readable: refusing it would break
     * observation, and it claims no completion.
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
 * - an async receipt (`accepted` / `async_id`) or a running poll (`stdout_partial`) is in flight,
 *   even when it carries `elapsed_seconds`;
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
        // In flight: the work was handed off as async, or is being polled while it still produces
        // output. `elapsed_seconds` does not turn either into a completion. An interactive write
        // receipt (`accepted` + `bytes_written`) is *not* in flight: it reports work already done.
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

        // Opening a session and observing the screen are completions in their own right.
        if (obj.containsKey("session") || obj.containsKey("identity")) {
            return PayloadReading.Completed(failed = false)
        }
        if (obj.containsKey("snapshot_id")) return PayloadReading.Completed(failed = false)

        return PayloadReading.Opaque
    }

    /**
     * True when a non-envelope payload is a *finished* call that the guard may keep verbatim as the
     * canonical result — a proven completion, or an opaque readable body such as observation YAML.
     *
     * In-flight, unusable-credential and malformed payloads all fail this, because they prove
     * nothing about an operation that may already have reached the phone.
     */
    fun legacyPayloadIsDecided(resultJson: String?): Boolean = when (read(resultJson)) {
        is PayloadReading.Completed -> true
        PayloadReading.Opaque -> true
        PayloadReading.InFlight,
        PayloadReading.InvalidCredential,
        PayloadReading.Malformed,
        -> false
    }

    private fun JsonObject.integerField(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        // A quoted "0" is a string, not an integer exit code.
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun JsonObject.booleanField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    @Suppress("unused")
    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
