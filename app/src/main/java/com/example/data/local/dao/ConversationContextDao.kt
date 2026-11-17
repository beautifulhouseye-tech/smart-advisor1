package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ConversationContextEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationContextDao {
    @Query("SELECT * FROM conversation_contexts WHERE conversationKey = :conversationKey LIMIT 1")
    suspend fun getByKey(conversationKey: String): ConversationContextEntity?

    @Query("SELECT * FROM conversation_contexts WHERE conversationKey = :conversationKey LIMIT 1")
    fun observeByKey(conversationKey: String): Flow<ConversationContextEntity?>

    @Query("SELECT * FROM conversation_contexts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ConversationContextEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationContextEntity)

    @Query("DELETE FROM conversation_contexts WHERE conversationKey = :conversationKey")
    suspend fun deleteByKey(conversationKey: String)

    @Query("DELETE FROM conversation_contexts")
    suspend fun clearAll()
}
