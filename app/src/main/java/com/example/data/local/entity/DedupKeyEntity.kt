package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "dedup_keys")
data class DedupKeyEntity(
    @PrimaryKey val key: String,
    val createdAt: Long = System.currentTimeMillis(),
    val state: String = STATE_NEW,
    val leaseUntil: Long = 0L,
    val sendState: String = SEND_NONE,
    val attempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATE_NEW = "NEW"
        const val STATE_PROCESSING = "PROCESSING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED_CONFIRMED = "FAILED_CONFIRMED"
        const val STATE_DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN"
        const val STATE_EXPIRED = "EXPIRED"

        const val SEND_NONE = "NONE"
        const val SEND_IN_FLIGHT = "IN_FLIGHT"
        const val SEND_HANDOFF = "HANDOFF"
        const val SEND_SENT = "SENT"
        const val SEND_FAILED_CONFIRMED = "FAILED_CONFIRMED"
        const val SEND_DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN"
    }
}
