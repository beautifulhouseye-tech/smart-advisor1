package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ServerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {
    @Query("SELECT * FROM server_config WHERE id = 1")
    fun observe(): Flow<ServerConfigEntity?>

    @Query("SELECT * FROM server_config WHERE id = 1")
    suspend fun get(): ServerConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ServerConfigEntity)

    @Query("DELETE FROM server_config")
    suspend fun clear()
}
