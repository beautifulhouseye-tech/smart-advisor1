package com.example.data.backup

import android.content.Context
import android.net.Uri
import com.example.data.local.entity.ContactFilterEntity
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ReplyTemplateEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RuleBackupDto(
    val keyword: String,
    val replyText: String,
    val matchType: MatchType = MatchType.CONTAINS,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val replyType: String = ReplyRuleEntity.REPLY_TYPE_TEXT,
    val imageUri: String? = null,
    val templateTitle: String? = null
)

@Serializable
data class TemplateBackupDto(
    val title: String,
    val category: String = ReplyTemplateEntity.CATEGORY_GENERAL,
    val body: String,
    val replyType: String = ReplyRuleEntity.REPLY_TYPE_TEXT,
    val imageUri: String? = null
)

@Serializable
data class ContactFilterBackupDto(
    val contactName: String,
    val listType: ContactFilterListType,
    val notes: String = ""
)

@Serializable
data class AutoMateBackupPayload(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<RuleBackupDto>,
    val contactFilters: List<ContactFilterBackupDto>,
    val templates: List<TemplateBackupDto> = emptyList()
)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    suspend fun exportToUri(
        uri: Uri,
        rules: List<ReplyRuleEntity>,
        filters: List<ContactFilterEntity>,
        templates: List<ReplyTemplateEntity> = emptyList()
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val templateTitleById = templates.associate { it.id to it.title }
            val payload = AutoMateBackupPayload(
                version = 2,
                exportedAt = System.currentTimeMillis(),
                rules = rules.map {
                    RuleBackupDto(
                        keyword = it.keyword,
                        replyText = it.replyText,
                        matchType = it.matchType,
                        isEnabled = it.isEnabled,
                        priority = it.priority,
                        replyType = it.replyType,
                        imageUri = it.imageUri,
                        templateTitle = it.templateId?.let { tid -> templateTitleById[tid] }
                    )
                },
                contactFilters = filters.map {
                    ContactFilterBackupDto(
                        contactName = it.contactName,
                        listType = it.listType,
                        notes = it.notes
                    )
                },
                templates = templates.map {
                    TemplateBackupDto(
                        title = it.title,
                        category = it.category,
                        body = it.body,
                        replyType = it.replyType,
                        imageUri = it.imageUri
                    )
                }
            )
            val serialized = json.encodeToString(AutoMateBackupPayload.serializer(), payload)
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(serialized.toByteArray(Charsets.UTF_8))
            } ?: error("تعذر فتح ملف التصدير")
            payload.rules.size + payload.contactFilters.size + payload.templates.size
        }
    }

    suspend fun importFromUri(uri: Uri): Result<AutoMateBackupPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("تعذر قراءة الملف المختار")
                json.decodeFromString(AutoMateBackupPayload.serializer(), raw)
            }
        }
}
