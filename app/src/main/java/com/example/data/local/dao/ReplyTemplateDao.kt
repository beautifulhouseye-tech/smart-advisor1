package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.ReplyTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplyTemplateDao {
    @Query("SELECT * FROM reply_templates ORDER BY updatedAt DESC, id DESC")
    fun observeAllTemplates(): Flow<List<ReplyTemplateEntity>>

    @Query("SELECT * FROM reply_templates ORDER BY updatedAt DESC, id DESC")
    suspend fun getAllTemplatesOnce(): List<ReplyTemplateEntity>

    @Query("SELECT * FROM reply_templates WHERE id = :id LIMIT 1")
    suspend fun getTemplateById(id: Long): ReplyTemplateEntity?

    @Query("SELECT COUNT(*) FROM reply_templates")
    suspend fun getTemplateCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: ReplyTemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<ReplyTemplateEntity>)

    @Update
    suspend fun updateTemplate(template: ReplyTemplateEntity)

    @Query("UPDATE reply_templates SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsageCount(id: Long)

    @Query("DELETE FROM reply_templates WHERE id = :id")
    suspend fun deleteTemplateById(id: Long)

    @Query("DELETE FROM reply_templates")
    suspend fun clearAllTemplates()
}
