package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.MenuReplyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuReplyDao {
    @Query("SELECT * FROM menu_replies ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MenuReplyEntity>>

    @Query("SELECT * FROM menu_replies WHERE trigger = :trigger AND enabled = 1 LIMIT 1")
    suspend fun findByTrigger(trigger: String): MenuReplyEntity?

    @Insert
    suspend fun insert(menu: MenuReplyEntity): Long

    @Update
    suspend fun update(menu: MenuReplyEntity)

    @Delete
    suspend fun delete(menu: MenuReplyEntity)
}
