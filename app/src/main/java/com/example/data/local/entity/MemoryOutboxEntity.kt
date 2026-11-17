package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_outbox",
    indices = [Index(value = ["state"]), Index(value = ["conversationKey"])]
)
data class MemoryOutboxEntity(
    @PrimaryKey val eventId: String,
    val conversationKey: String,
    val customerKey: String,
    val encryptedPayload: String,
    val revision: Long,
    val state: String = STATE_PENDING,
    val attempts: Int = 0,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_SENT = "SENT"
        const val STATE_FAILED_PERMANENT = "FAILED_PERMANENT"
        const val STATE_CONFLICT_MERGED = "CONFLICT_MERGED"
    }
}
