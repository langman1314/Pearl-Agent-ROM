package com.niki914.nexus.agentic.runtime.service

/**
 * Serializes reservation and activation of runtime turns.
 *
 * A reservation never starts work. Only [commit] publishes an active value, and
 * an expired or released reservation cannot be committed by a late callback.
 */
internal class TurnAdmissionGate<T>(
    private val reservationTimeoutMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(reservationTimeoutMs > 0L)
    }

    private data class Reservation(
        val requestId: String,
        val expiresAtMs: Long,
    )

    private var reservation: Reservation? = null
    private var activeRequestId: String? = null
    private var activeValue: T? = null

    @Synchronized
    fun reserve(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        expireReservationIfNeeded()
        if (activeRequestId != null) return activeRequestId == requestId
        val current = reservation
        if (current != null) return current.requestId == requestId
        reservation = Reservation(
            requestId = requestId,
            expiresAtMs = nowMs() + reservationTimeoutMs,
        )
        return true
    }

    @Synchronized
    fun commit(requestId: String, value: T): Boolean {
        expireReservationIfNeeded()
        if (activeRequestId != null) return false
        val current = reservation ?: return false
        if (current.requestId != requestId) return false
        reservation = null
        activeRequestId = requestId
        activeValue = value
        return true
    }

    @Synchronized
    fun releaseReservation(requestId: String): Boolean {
        val current = reservation ?: return false
        if (current.requestId != requestId) return false
        reservation = null
        return true
    }

    @Synchronized
    fun active(): T? = activeValue

    @Synchronized
    fun clearActive(requestId: String): T? {
        if (activeRequestId != requestId) return null
        val value = activeValue
        activeRequestId = null
        activeValue = null
        return value
    }

    @Synchronized
    fun clearAll(): T? {
        reservation = null
        val value = activeValue
        activeRequestId = null
        activeValue = null
        return value
    }

    @Synchronized
    fun isReserved(requestId: String): Boolean {
        expireReservationIfNeeded()
        return reservation?.requestId == requestId
    }

    private fun expireReservationIfNeeded() {
        val current = reservation ?: return
        if (nowMs() >= current.expiresAtMs) reservation = null
    }
}
