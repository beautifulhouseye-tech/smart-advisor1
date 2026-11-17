package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.FollowUpEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowUpDao {
    @Query("SELECT * FROM follow_ups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FollowUpEntity>>

    @Query("SELECT * FROM follow_ups WHERE ruleId = :ruleId")
    fun observeByRule(ruleId: Long): Flow<List<FollowUpEntity>>

    @Query("SELECT * FROM follow_ups WHERE ruleId = :ruleId AND enabled = 1")
    suspend fun getEnabledByRuleOnce(ruleId: Long): List<FollowUpEntity>

    @Insert
    suspend fun insert(fu: FollowUpEntity): Long

    @Update
    suspend fun update(fu: FollowUpEntity)

    @Delete
    suspend fun delete(fu: FollowUpEntity)
}
