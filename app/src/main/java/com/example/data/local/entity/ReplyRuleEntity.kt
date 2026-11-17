package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class MatchType(val labelAr: String) {
    CONTAINS("يحتوي على"),
    EXACT("تطابق تام"),
    STARTS_WITH("يبدأ بـ"),
    REGEX("تعبير نمطي (Regex)")
}

@Entity(tableName = "reply_rules")
data class ReplyRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val keyword: String,
    val replyText: String,
    val matchType: MatchType = MatchType.CONTAINS,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val hitCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val replyType: String = REPLY_TYPE_TEXT,
    val imageUri: String? = null
) {
    companion object {
        const val REPLY_TYPE_TEXT = "TEXT"
        const val REPLY_TYPE_IMAGE = "IMAGE"
        const val REPLY_TYPE_BOTH = "BOTH"
    }
}
