package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "server_config")
data class ServerConfigEntity(
    @PrimaryKey val id: Int = 1,
    val isEnabled: Boolean = false,
    val url: String = "https://example.com/webhook",
    val method: String = "POST",
    val contentType: String = "JSON",
    val headersJson: String = "[]",
    val requestFieldsJson: String = "[]",
    val responseReplyKey: String = "reply",
    val timeoutSeconds: Int = 25,
    val retryCount: Int = 2,
    val sendOnGroup: String = "KEYWORD_ONLY",
    val sendOnPrivate: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
