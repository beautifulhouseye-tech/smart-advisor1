package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class WebhookMode {
    AI_TOOL,
    STANDALONE_FALLBACK
}

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
    val webhookMode: String = WebhookMode.STANDALONE_FALLBACK.name,
    val toolDescription: String = "",
    val toolMaxResponseChars: Int = DEFAULT_TOOL_MAX_CHARS,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun effectiveWebhookMode(): WebhookMode =
        runCatching { WebhookMode.valueOf(webhookMode) }
            .getOrDefault(WebhookMode.STANDALONE_FALLBACK)

    fun effectiveToolDescription(): String =
        toolDescription.trim().ifBlank { DEFAULT_TOOL_DESCRIPTION }

    companion object {
        const val DEFAULT_TOOL_MAX_CHARS = 2000
        const val DEFAULT_TOOL_DESCRIPTION =
            "خادم خارجي للاستعلام عن حالة الطلبات أو الشحنات أو بيانات CRM/ERP أو تنفيذ إجراءات خارجية عند حاجة العميل."
    }
}
