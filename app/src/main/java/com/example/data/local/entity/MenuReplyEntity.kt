package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "menu_replies",
    indices = [Index(value = ["trigger"], unique = true)]
)
data class MenuReplyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trigger: String,
    val title: String,
    val optionsJson: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
