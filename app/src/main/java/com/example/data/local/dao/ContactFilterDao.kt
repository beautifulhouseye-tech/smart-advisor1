package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ContactFilterEntity
import com.example.data.local.entity.ContactFilterListType
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactFilterDao {
    @Query("SELECT * FROM contact_filters ORDER BY createdAt DESC")
    fun observeAllFilters(): Flow<List<ContactFilterEntity>>

    @Query("SELECT * FROM contact_filters WHERE listType = :listType")
    suspend fun getFiltersByTypeOnce(listType: ContactFilterListType): List<ContactFilterEntity>

    @Query("SELECT * FROM contact_filters")
    suspend fun getAllFiltersOnce(): List<ContactFilterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFilter(filter: ContactFilterEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFilters(filters: List<ContactFilterEntity>)

    @Query("DELETE FROM contact_filters WHERE id = :id")
    suspend fun deleteFilterById(id: Long)

    @Query("DELETE FROM contact_filters")
    suspend fun clearAllFilters()
}
