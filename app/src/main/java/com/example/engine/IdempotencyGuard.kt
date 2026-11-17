package com.example.engine

import com.example.data.local.dao.DedupKeyDao
import com.example.data.local.entity.DedupKeyEntity
import com.example.network.HttpTransport
import java.security.MessageDigest

open class IdempotencyGuard(
    private val dedupDao: DedupKeyDao,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    open suspend fun tryAcquireReservation(
        msgKey: String,
        leaseDurationMs: Long = DEFAULT_LEASE_MS
    ): Boolean {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return false
        require(leaseDurationMs > 0L) { "leaseDurationMs must be positive" }

        val now = clockMs()
        val leaseUntil = now + leaseDurationMs
        val insertedRowId = dedupDao.insertIgnore(
            DedupKeyEntity(
                key = cleanKey,
                createdAt = now,
                state = DedupKeyEntity.STATE_PROCESSING,
                leaseUntil = leaseUntil,
                sendState = DedupKeyEntity.SEND_NONE,
                attempts = 1,
                updatedAt = now
            )
        )
        if (insertedRowId != -1L) {
            return true
        }

        val completedCutoff = now - COMPLETED_TTL_MS
        val updatedRows = dedupDao.tryAcquireExisting(
            key = cleanKey,
            leaseUntil = leaseUntil,
            now = now,
            completedExpiryCutoff = completedCutoff
        )
        return updatedRows > 0
    }

    open suspend fun markCompleted(msgKey: String) {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return
        dedupDao.updateState(
            key = cleanKey,
            state = DedupKeyEntity.STATE_COMPLETED,
            leaseUntil = 0L,
            now = clockMs()
        )
    }

    open suspend fun markFailedConfirmed(msgKey: String) {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return
        dedupDao.updateState(
            key = cleanKey,
            state = DedupKeyEntity.STATE_FAILED_CONFIRMED,
            leaseUntil = 0L,
            now = clockMs()
        )
    }

    open suspend fun markDeliveryUnknown(msgKey: String) {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return
        dedupDao.markDeliveryUnknown(
            key = cleanKey,
            now = clockMs()
        )
    }

    open suspend fun releaseReservation(msgKey: String) {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return
        dedupDao.updateState(
            key = cleanKey,
            state = DedupKeyEntity.STATE_EXPIRED,
            leaseUntil = 0L,
            now = clockMs()
        )
    }

    open suspend fun updateSendState(msgKey: String, sendState: String) {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return
        dedupDao.updateSendState(
            key = cleanKey,
            sendState = sendState,
            now = clockMs()
        )
    }

    open suspend fun getEntry(msgKey: String): DedupKeyEntity? {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return null
        return dedupDao.getByKey(cleanKey)
    }

    open suspend fun recoverStaleInFlight(): Int {
        return dedupDao.recoverStaleInFlight(clockMs())
    }

    open suspend fun shouldProcess(key: String): Boolean {
        val normalized = key.trim()
        if (normalized.isEmpty()) return true
        val since = clockMs() - WINDOW_24H_MS
        return dedupDao.exists(normalized, since) == null
    }

    open suspend fun markProcessed(key: String) {
        val normalized = key.trim()
        if (normalized.isEmpty()) return
        val now = clockMs()
        val inserted = dedupDao.insertIgnore(
            DedupKeyEntity(
                key = normalized,
                createdAt = now,
                state = DedupKeyEntity.STATE_COMPLETED,
                leaseUntil = 0L,
                sendState = DedupKeyEntity.SEND_SENT,
                attempts = 1,
                updatedAt = now
            )
        )
        if (inserted == -1L) {
            dedupDao.refreshProcessed(
                key = normalized,
                now = now
            )
        }
    }

    open suspend fun cleanupOlderThan(days: Int = 7) {
        val safeDays = days.coerceAtLeast(1)
        val before = clockMs() - safeDays * DAY_MS
        dedupDao.cleanup(before)
    }

    companion object {
        private const val DAY_MS = 24L * 3600L * 1000L
        const val WINDOW_24H_MS = DAY_MS
        const val COMPLETED_TTL_MS = 10L * 60_000L
        const val DEFAULT_LEASE_MS = 90_000L

        private val WHITESPACE_REGEX = Regex("\\s+")

        fun normalizeText(text: String): String =
            text.trim().replace(WHITESPACE_REGEX, " ")

        fun deriveMsgKey(
            packageName: String,
            sbnKey: String,
            text: String
        ): String {
            val payload = "${packageName.trim()}|${sbnKey.trim()}|${normalizeText(text)}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        fun validateLeaseDuration(
            leaseDurationMs: Long,
            maxAttempts: Int = HttpTransport.DEFAULT_MAX_ATTEMPTS,
            callTimeoutMs: Long = HttpTransport.DEFAULT_TIMEOUT_SECONDS * 1_000L,
            totalBackoffMs: Long = HttpTransport.totalBackoffMs(maxAttempts)
        ) {
            val minimumRequired = maxAttempts.toLong() * callTimeoutMs + totalBackoffMs
            require(leaseDurationMs > minimumRequired) {
                "Lease duration ($leaseDurationMs ms) must exceed worst-case attempts+backoff ($minimumRequired ms)"
            }
        }
    }
}
