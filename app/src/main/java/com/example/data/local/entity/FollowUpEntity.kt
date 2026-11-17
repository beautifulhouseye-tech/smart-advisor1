package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "follow_ups",
    foreignKeys = [
        ForeignKey(
            entity = ReplyRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FollowUpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val ruleId: Long,
    val delayMinutes: Int,
    val messageTemplate: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
