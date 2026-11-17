package com.example

import com.example.data.local.dao.DedupKeyDao
import com.example.data.local.entity.DedupKeyEntity
import com.example.engine.IdempotencyGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotencyGuardTest {

    private class FakeDedupKeyDao : DedupKeyDao {
        val storage = linkedMapOf<String, DedupKeyEntity>()

        override suspend fun exists(key: String, since: Long): Int? {
            val item = storage[key] ?: return null
            return if (item.createdAt > since) 1 else null
        }

        override suspend fun getByKey(key: String): DedupKeyEntity? = storage[key]

        override suspend fun insertIgnore(entity: DedupKeyEntity): Long {
            if (storage.containsKey(entity.key)) return -1L
            storage[entity.key] = entity
            return 1L
        }

        override suspend fun tryAcquireExisting(
            key: String,
            leaseUntil: Long,
            now: Long,
            completedExpiryCutoff: Long
        ): Int {
            val existing = storage[key] ?: return 0
            val eligible = existing.state in setOf(
                DedupKeyEntity.STATE_FAILED_CONFIRMED,
                DedupKeyEntity.STATE_EXPIRED,
                DedupKeyEntity.STATE_NEW
            ) || (
                existing.state == DedupKeyEntity.STATE_PROCESSING &&
                    existing.leaseUntil < now &&
                    existing.sendState != DedupKeyEntity.SEND_SENT &&
                    existing.sendState != DedupKeyEntity.SEND_DELIVERY_UNKNOWN
                ) ||
                (existing.state == DedupKeyEntity.STATE_COMPLETED && existing.updatedAt < completedExpiryCutoff)
            if (!eligible) return 0
            storage[key] = existing.copy(
                state = DedupKeyEntity.STATE_PROCESSING,
                leaseUntil = leaseUntil,
                sendState = DedupKeyEntity.SEND_NONE,
                attempts = existing.attempts + 1,
                updatedAt = now
            )
            return 1
        }

        override suspend fun updateState(key: String, state: String, leaseUntil: Long, now: Long) {
            val existing = storage[key] ?: return
            if (existing.state == DedupKeyEntity.STATE_DELIVERY_UNKNOWN ||
                existing.sendState == DedupKeyEntity.SEND_DELIVERY_UNKNOWN
            ) {
                return
            }
            if (state != DedupKeyEntity.STATE_COMPLETED && existing.sendState == DedupKeyEntity.SEND_SENT) {
                return
            }
            storage[key] = existing.copy(state = state, leaseUntil = leaseUntil, updatedAt = now)
        }

        override suspend fun refreshProcessed(key: String, now: Long) {
            val existing = storage[key] ?: return
            storage[key] = existing.copy(
                createdAt = now,
                state = DedupKeyEntity.STATE_COMPLETED,
                sendState = DedupKeyEntity.SEND_SENT,
                leaseUntil = 0L,
                updatedAt = now
            )
        }

        override suspend fun updateSendState(key: String, sendState: String, now: Long) {
            val existing = storage[key] ?: return
            if (existing.sendState == DedupKeyEntity.SEND_SENT ||
                existing.sendState == DedupKeyEntity.SEND_DELIVERY_UNKNOWN ||
                existing.state == DedupKeyEntity.STATE_DELIVERY_UNKNOWN
            ) {
                return
            }
            storage[key] = existing.copy(sendState = sendState, updatedAt = now)
        }

        override suspend fun markDeliveryUnknown(key: String, now: Long) {
            val existing = storage[key] ?: return
            if (existing.sendState == DedupKeyEntity.SEND_SENT) return
            storage[key] = existing.copy(
                state = DedupKeyEntity.STATE_DELIVERY_UNKNOWN,
                sendState = DedupKeyEntity.SEND_DELIVERY_UNKNOWN,
                leaseUntil = 0L,
                updatedAt = now
            )
        }

        override suspend fun recoverStaleInFlight(now: Long): Int {
            var count = 0
            storage.entries.forEach { (k, v) ->
                if (v.sendState == DedupKeyEntity.SEND_IN_FLIGHT) {
                    storage[k] = v.copy(
                        state = DedupKeyEntity.STATE_DELIVERY_UNKNOWN,
                        sendState = DedupKeyEntity.SEND_DELIVERY_UNKNOWN,
                        leaseUntil = 0L,
                        updatedAt = now
                    )
                    count++
                }
            }
            return count
        }

        override suspend fun cleanup(before: Long) {
            val toRemove = storage.values.filter {
                it.updatedAt < before && it.state != DedupKeyEntity.STATE_PROCESSING
            }.map { it.key }
            toRemove.forEach { storage.remove(it) }
        }
    }

    @Test
    fun `blocks duplicate keys within 24h and allows reprocessing after 25h`() = runTest {
        var now = 10_000_000_000L
        val dao = FakeDedupKeyDao()
        val guard = IdempotencyGuard(dao, clockMs = { now })

        val notifKey = "0|com.whatsapp|1001|null|10234"
        assertTrue(guard.shouldProcess(notifKey))

        guard.markProcessed(notifKey)
        assertFalse(guard.shouldProcess(notifKey))

        now += 23L * 3600L * 1000L
        assertFalse(guard.shouldProcess(notifKey))

        now += 2L * 3600L * 1000L
        assertTrue(guard.shouldProcess(notifKey))

        guard.markProcessed(notifKey)
        assertFalse(guard.shouldProcess(notifKey))
    }

    @Test
    fun `cleanupOlderThan deletes expired entries and keeps recent entries`() = runTest {
        var now = 20_000_000_000L
        val dao = FakeDedupKeyDao()
        val guard = IdempotencyGuard(dao, clockMs = { now })

        guard.markProcessed("old-key")
        now += 8L * 24L * 3600L * 1000L
        guard.markProcessed("recent-key")

        guard.cleanupOlderThan(days = 7)

        assertEquals(1, dao.storage.size)
        assertTrue(dao.storage.containsKey("recent-key"))
        assertFalse(dao.storage.containsKey("old-key"))
    }
}
