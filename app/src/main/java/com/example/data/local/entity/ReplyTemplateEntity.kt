package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "reply_templates",
    indices = [
        Index(value = ["category"]),
        Index(value = ["updatedAt"])
    ]
)
data class ReplyTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val category: String = CATEGORY_GENERAL,
    val body: String,
    val replyType: String = ReplyRuleEntity.REPLY_TYPE_TEXT,
    val imageUri: String? = null,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CATEGORY_GENERAL = "عام"
        const val CATEGORY_WELCOME = "ترحيب"
        const val CATEGORY_PRICING = "الأسعار والعروض"
        const val CATEGORY_SHIPPING = "الشحن والتوصيل"
        const val CATEGORY_SUPPORT = "الدعم والمساعدة"
        const val CATEGORY_FOLLOWUP = "المتابعة"

        val DEFAULT_CATEGORIES = listOf(
            CATEGORY_GENERAL,
            CATEGORY_WELCOME,
            CATEGORY_PRICING,
            CATEGORY_SHIPPING,
            CATEGORY_SUPPORT,
            CATEGORY_FOLLOWUP
        )
    }
}
