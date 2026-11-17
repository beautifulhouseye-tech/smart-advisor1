package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.ReplyRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplyRuleDao {
    @Query("SELECT * FROM reply_rules ORDER BY priority DESC, createdAt DESC")
    fun observeAllRules(): Flow<List<ReplyRuleEntity>>

    @Query("SELECT * FROM reply_rules WHERE isEnabled = 1 ORDER BY priority DESC, createdAt DESC")
    suspend fun getEnabledRulesOnce(): List<ReplyRuleEntity>

    @Query("SELECT * FROM reply_rules ORDER BY priority DESC, createdAt DESC")
    suspend fun getAllRulesOnce(): List<ReplyRuleEntity>

    @Query("SELECT COUNT(*) FROM reply_rules")
    suspend fun getRuleCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: ReplyRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<ReplyRuleEntity>)

    @Update
    suspend fun updateRule(rule: ReplyRuleEntity)

    @Query("UPDATE reply_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE reply_rules SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun incrementHitCount(id: Long)

    @Query("DELETE FROM reply_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("DELETE FROM reply_rules")
    suspend fun clearAllRules()
}
