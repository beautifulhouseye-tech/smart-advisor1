package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_contexts")
data class ConversationContextEntity(
    @PrimaryKey val conversationKey: String,
    val packageName: String,
    val isGroup: Boolean = false,
    val displayName: String = "",
    val phone: String = "",
    val customerKey: String = "",
    val turnsJson: String = "[]",
    val currentTopic: String = "",
    val lastProductDiscussed: String = "",
    val temporaryPreferencesJson: String = "[]",
    val unansweredQuestionsJson: String = "[]",
    val pendingAction: String = "",
    val pendingAssistantTurnJson: String = "",
    val consentGranted: Boolean = false,
    val consentUpdatedAt: Long = 0L,
    val lastMemoryRevision: Long = 0L,
    val lastSyncedSummary: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
