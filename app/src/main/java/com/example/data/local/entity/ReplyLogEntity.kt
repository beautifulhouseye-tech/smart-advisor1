package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reply_logs")
data class ReplyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val senderName: String,
    val packageName: String,
    val incomingMessage: String,
    val sentReply: String,
    val isGroup: Boolean,
    val deliveryMethod: String,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val msgKey: String = "",
    val conversationKey: String = "",
    val sourceCategory: String = "",
    val matchedRuleKeyword: String = ""
)
