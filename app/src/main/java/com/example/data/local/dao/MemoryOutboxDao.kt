package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.MemoryOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryOutboxDao {
    @Query("SELECT * FROM memory_outbox WHERE state = :pendingState ORDER BY createdAt ASC")
    suspend fun getPending(pendingState: String = MemoryOutboxEntity.STATE_PENDING): List<MemoryOutboxEntity>

    @Query("SELECT * FROM memory_outbox WHERE conversationKey = :conversationKey ORDER BY revision DESC LIMIT 1")
    suspend fun getLatestForConversation(conversationKey: String): MemoryOutboxEntity?

    @Query("SELECT * FROM memory_outbox WHERE eventId = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: String): MemoryOutboxEntity?

    @Query("SELECT COUNT(*) FROM memory_outbox WHERE state = :pendingState")
    fun observePendingCount(pendingState: String = MemoryOutboxEntity.STATE_PENDING): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: MemoryOutboxEntity): Long

    @Update
    suspend fun update(entity: MemoryOutboxEntity)

    @Query("DELETE FROM memory_outbox WHERE state = :sentState AND updatedAt < :olderThanMs")
    suspend fun purgeSentOlderThan(
        sentState: String = MemoryOutboxEntity.STATE_SENT,
        olderThanMs: Long
    )
}
