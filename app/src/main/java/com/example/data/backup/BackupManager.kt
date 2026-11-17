package com.example.data.backup

import android.content.Context
import android.net.Uri
import com.example.data.local.entity.ContactFilterEntity
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity
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
    val priority: Int = 0
)

@Serializable
data class ContactFilterBackupDto(
    val contactName: String,
    val listType: ContactFilterListType,
    val notes: String = ""
)

@Serializable
data class AutoMateBackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<RuleBackupDto>,
    val contactFilters: List<ContactFilterBackupDto>
)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    suspend fun exportToUri(
        uri: Uri,
        rules: List<ReplyRuleEntity>,
        filters: List<ContactFilterEntity>
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = AutoMateBackupPayload(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                rules = rules.map {
                    RuleBackupDto(
                        keyword = it.keyword,
                        replyText = it.replyText,
                        matchType = it.matchType,
                        isEnabled = it.isEnabled,
                        priority = it.priority
                    )
                },
                contactFilters = filters.map {
                    ContactFilterBackupDto(
                        contactName = it.contactName,
                        listType = it.listType,
                        notes = it.notes
                    )
                }
            )
            val serialized = json.encodeToString(AutoMateBackupPayload.serializer(), payload)
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(serialized.toByteArray(Charsets.UTF_8))
            } ?: error("تعذر فتح ملف التصدير")
            payload.rules.size + payload.contactFilters.size
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
