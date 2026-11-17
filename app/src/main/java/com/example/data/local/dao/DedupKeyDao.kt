package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.DedupKeyEntity

@Dao
interface DedupKeyDao {
    @Query("SELECT 1 FROM dedup_keys WHERE `key` = :key AND createdAt > :since LIMIT 1")
    suspend fun exists(key: String, since: Long): Int?

    @Query("SELECT * FROM dedup_keys WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): DedupKeyEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: DedupKeyEntity): Long

    @Query(
        """
        UPDATE dedup_keys
        SET state = 'PROCESSING',
            leaseUntil = :leaseUntil,
            sendState = 'NONE',
            attempts = attempts + 1,
            updatedAt = :now
        WHERE `key` = :key
          AND (
            state IN ('FAILED_CONFIRMED', 'EXPIRED', 'NEW')
            OR (state = 'PROCESSING' AND leaseUntil < :now AND sendState NOT IN ('SENT', 'DELIVERY_UNKNOWN'))
            OR (state = 'COMPLETED' AND updatedAt < :completedExpiryCutoff)
          )
        """
    )
    suspend fun tryAcquireExisting(
        key: String,
        leaseUntil: Long,
        now: Long,
        completedExpiryCutoff: Long
    ): Int

    @Query(
        """
        UPDATE dedup_keys
        SET state = :state,
            leaseUntil = :leaseUntil,
            updatedAt = :now
        WHERE `key` = :key
          AND state != 'DELIVERY_UNKNOWN'
          AND sendState != 'DELIVERY_UNKNOWN'
          AND (:state = 'COMPLETED' OR sendState != 'SENT')
        """
    )
    suspend fun updateState(key: String, state: String, leaseUntil: Long, now: Long)

    @Query(
        """
        UPDATE dedup_keys
        SET createdAt = :now,
            state = 'COMPLETED',
            sendState = 'SENT',
            leaseUntil = 0,
            updatedAt = :now
        WHERE `key` = :key
        """
    )
    suspend fun refreshProcessed(key: String, now: Long)

    @Query(
        """
        UPDATE dedup_keys
        SET sendState = :sendState,
            updatedAt = :now
        WHERE `key` = :key
          AND sendState != 'SENT'
          AND sendState != 'DELIVERY_UNKNOWN'
          AND state != 'DELIVERY_UNKNOWN'
        """
    )
    suspend fun updateSendState(key: String, sendState: String, now: Long)

    @Query(
        """
        UPDATE dedup_keys
        SET state = 'DELIVERY_UNKNOWN',
            sendState = 'DELIVERY_UNKNOWN',
            leaseUntil = 0,
            updatedAt = :now
        WHERE `key` = :key
          AND sendState != 'SENT'
        """
    )
    suspend fun markDeliveryUnknown(key: String, now: Long)

    @Query(
        """
        UPDATE dedup_keys
        SET state = 'DELIVERY_UNKNOWN',
            sendState = 'DELIVERY_UNKNOWN',
            leaseUntil = 0,
            updatedAt = :now
        WHERE sendState = 'IN_FLIGHT'
        """
    )
    suspend fun recoverStaleInFlight(now: Long): Int

    @Query("DELETE FROM dedup_keys WHERE updatedAt < :before AND state != 'PROCESSING'")
    suspend fun cleanup(before: Long)
}
