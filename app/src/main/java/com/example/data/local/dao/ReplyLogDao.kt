package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.ReplyLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplyLogDao {
    @Query("SELECT * FROM reply_logs ORDER BY timestamp DESC LIMIT 50")
    fun observeRecentLogs(): Flow<List<ReplyLogEntity>>

    @Query("SELECT * FROM reply_logs ORDER BY timestamp DESC")
    fun observeAllLogs(): Flow<List<ReplyLogEntity>>

    @Query("SELECT COUNT(*) FROM reply_logs WHERE timestamp >= :sinceEpochMs")
    fun observeCountSince(sinceEpochMs: Long): Flow<Int>

    @Query("SELECT * FROM reply_logs WHERE timestamp >= :sinceEpochMs ORDER BY timestamp DESC")
    suspend fun getLogsSince(sinceEpochMs: Long): List<ReplyLogEntity>

    @Query("SELECT * FROM reply_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReplyLogEntity?

    @Query(
        """
        SELECT * FROM reply_logs
        WHERE (deliveryMethod = :deliveryMethod OR deliveryMethod = 'FAILED_CONFIRMED')
          AND retryCount < :maxRetries
        ORDER BY timestamp ASC
        """
    )
    suspend fun getFailedLogsForRetry(
        deliveryMethod: String = "FAILED",
        maxRetries: Int = 3
    ): List<ReplyLogEntity>

    @Query(
        """
        UPDATE reply_logs
        SET deliveryMethod = 'DELIVERY_UNKNOWN',
            sentReply = CASE WHEN sentReply = '' THEN 'حالة إرسال غير مؤكدة بعد إعادة التشغيل' ELSE sentReply END
        WHERE deliveryMethod = 'IN_FLIGHT'
        """
    )
    suspend fun markInFlightLogsAsDeliveryUnknown(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ReplyLogEntity): Long

    @Update
    suspend fun updateLog(log: ReplyLogEntity)

    @Query("DELETE FROM reply_logs")
    suspend fun clearLogs()
}
