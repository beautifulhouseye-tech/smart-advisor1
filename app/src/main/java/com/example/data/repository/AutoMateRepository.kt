package com.example.data.repository

import android.net.Uri
import com.example.data.backup.BackupManager
import com.example.data.local.dao.ContactFilterDao
import com.example.data.local.dao.ReplyLogDao
import com.example.data.local.dao.ReplyRuleDao
import com.example.data.local.dao.ReplyTemplateDao
import com.example.data.local.entity.ContactFilterEntity
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ReplyTemplateEntity
import com.example.data.preferences.AutoMateSettings
import com.example.data.preferences.ContactFilterMode
import com.example.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedRuleContent(
    val replyText: String,
    val replyType: String,
    val imageUri: String?,
    val templateId: Long?,
    val templateTitle: String? = null
)

@Singleton
class AutoMateRepository @Inject constructor(
    private val replyRuleDao: ReplyRuleDao,
    private val contactFilterDao: ContactFilterDao,
    private val replyLogDao: ReplyLogDao,
    private val settingsDataStore: SettingsDataStore,
    private val backupManager: BackupManager,
    private val replyTemplateDao: ReplyTemplateDao? = null
) {
    constructor(
        replyRuleDao: ReplyRuleDao,
        contactFilterDao: ContactFilterDao,
        replyLogDao: ReplyLogDao,
        settingsDataStore: SettingsDataStore,
        backupManager: BackupManager
    ) : this(
        replyRuleDao = replyRuleDao,
        contactFilterDao = contactFilterDao,
        replyLogDao = replyLogDao,
        settingsDataStore = settingsDataStore,
        backupManager = backupManager,
        replyTemplateDao = null
    )

    val rulesFlow: Flow<List<ReplyRuleEntity>> = replyRuleDao.observeAllRules()
    val templatesFlow: Flow<List<ReplyTemplateEntity>> =
        replyTemplateDao?.observeAllTemplates() ?: flowOf(emptyList())
    val contactFiltersFlow: Flow<List<ContactFilterEntity>> = contactFilterDao.observeAllFilters()
    val recentLogsFlow: Flow<List<ReplyLogEntity>> = replyLogDao.observeRecentLogs()
    val settingsFlow: Flow<AutoMateSettings> = settingsDataStore.settingsFlow

    fun observeTodayRepliesCount(): Flow<Int> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return replyLogDao.observeCountSince(cal.timeInMillis)
    }

    suspend fun getCurrentSettings(): AutoMateSettings = settingsDataStore.currentSettings()

    suspend fun ensureStarterRulesIfEmpty() {
        if (replyRuleDao.getRuleCount() == 0) {
            val starterRules = listOf(
                ReplyRuleEntity(
                    keyword = "السلام عليكم, مرحبا, أهلا",
                    replyText = "وعليكم السلام ورحمة الله، أهلاً بك! أنا مشغول حالياً وسأرد عليك في أقرب وقت.",
                    matchType = MatchType.CONTAINS,
                    isEnabled = true,
                    priority = 10
                ),
                ReplyRuleEntity(
                    keyword = "السعر, بكم, تفاصيل",
                    replyText = "أهلاً بك! شكراً لتواصلك، سأزودك بكافة التفاصيل والأسعار فور تفرغي.",
                    matchType = MatchType.CONTAINS,
                    isEnabled = true,
                    priority = 8
                ),
                ReplyRuleEntity(
                    keyword = "*",
                    replyText = "شكراً لرسالتك! تلقيت إشعارك وسأعود للرد عليك قريباً.",
                    matchType = MatchType.CONTAINS,
                    isEnabled = false,
                    priority = 1
                )
            )
            replyRuleDao.insertRules(starterRules)
        }
        ensureStarterTemplatesIfEmpty()
    }

    suspend fun ensureStarterTemplatesIfEmpty() {
        val dao = replyTemplateDao ?: return
        if (dao.getTemplateCount() == 0) {
            val now = System.currentTimeMillis()
            val starters = listOf(
                ReplyTemplateEntity(
                    title = "ترحيب بالعميل",
                    category = ReplyTemplateEntity.CATEGORY_WELCOME,
                    body = "أهلاً بك {{sender_name}} 🌹 يسعدنا تواصلك معنا! كيف يمكنني مساعدتك اليوم؟",
                    createdAt = now,
                    updatedAt = now
                ),
                ReplyTemplateEntity(
                    title = "تفاصيل الشحن والتوصيل",
                    category = ReplyTemplateEntity.CATEGORY_SHIPPING,
                    body = "مرحباً {{sender_name}}، التوصيل متاح خلال 24-48 ساعة داخل المدينة و2-4 أيام لباقي المناطق 🚚",
                    createdAt = now,
                    updatedAt = now - 1000L
                ),
                ReplyTemplateEntity(
                    title = "رد سريع أثناء الانشغال",
                    category = ReplyTemplateEntity.CATEGORY_SUPPORT,
                    body = "أهلاً {{sender_name}}، وصلتنا رسالتك: \"{{message}}\" وسيتواصل معك فريقنا في أقرب وقت 🙏",
                    createdAt = now,
                    updatedAt = now - 2000L
                )
            )
            dao.insertTemplates(starters)
        }
    }

    suspend fun getEnabledRulesOnce(): List<ReplyRuleEntity> = replyRuleDao.getEnabledRulesOnce()

    suspend fun upsertRule(rule: ReplyRuleEntity) {
        if (rule.id == 0L) {
            replyRuleDao.insertRule(rule)
        } else {
            replyRuleDao.updateRule(rule)
        }
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = replyRuleDao.setRuleEnabled(id, enabled)

    suspend fun deleteRule(id: Long) = replyRuleDao.deleteRuleById(id)

    suspend fun incrementRuleHit(id: Long) = replyRuleDao.incrementHitCount(id)

    // Reply Templates CRUD & Resolution
    suspend fun getAllTemplatesOnce(): List<ReplyTemplateEntity> =
        replyTemplateDao?.getAllTemplatesOnce().orEmpty()

    suspend fun getTemplateById(id: Long): ReplyTemplateEntity? =
        replyTemplateDao?.getTemplateById(id)

    suspend fun upsertTemplate(template: ReplyTemplateEntity): Long {
        val dao = replyTemplateDao ?: return 0L
        val now = System.currentTimeMillis()
        val normalized = template.copy(
            title = template.title.trim(),
            category = template.category.trim().ifBlank { ReplyTemplateEntity.CATEGORY_GENERAL },
            body = template.body.trim(),
            updatedAt = now
        )
        return if (normalized.id == 0L) {
            dao.insertTemplate(normalized.copy(createdAt = now))
        } else {
            dao.updateTemplate(normalized)
            normalized.id
        }
    }

    suspend fun duplicateTemplate(template: ReplyTemplateEntity): Long {
        val dao = replyTemplateDao ?: return 0L
        val now = System.currentTimeMillis()
        return dao.insertTemplate(
            template.copy(
                id = 0L,
                title = "${template.title} (نسخة)",
                usageCount = 0,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun deleteTemplate(id: Long) {
        replyTemplateDao?.deleteTemplateById(id)
    }

    suspend fun incrementTemplateUsage(id: Long) {
        replyTemplateDao?.incrementUsageCount(id)
    }

    suspend fun resolveRuleContent(rule: ReplyRuleEntity): ResolvedRuleContent {
        val linkedTemplate = rule.templateId?.let { tid ->
            replyTemplateDao?.getTemplateById(tid)
        }
        return if (linkedTemplate != null) {
            ResolvedRuleContent(
                replyText = linkedTemplate.body,
                replyType = linkedTemplate.replyType,
                imageUri = linkedTemplate.imageUri,
                templateId = linkedTemplate.id,
                templateTitle = linkedTemplate.title
            )
        } else {
            ResolvedRuleContent(
                replyText = rule.replyText,
                replyType = rule.replyType,
                imageUri = rule.imageUri,
                templateId = null,
                templateTitle = null
            )
        }
    }

    suspend fun getContactFiltersByType(type: ContactFilterListType): List<ContactFilterEntity> =
        contactFilterDao.getFiltersByTypeOnce(type)

    suspend fun addContactFilter(name: String, type: ContactFilterListType, notes: String = "") {
        val cleaned = name.trim()
        if (cleaned.isNotEmpty()) {
            contactFilterDao.insertFilter(
                ContactFilterEntity(
                    contactName = cleaned,
                    listType = type,
                    notes = notes.trim()
                )
            )
        }
    }

    suspend fun deleteContactFilter(id: Long) = contactFilterDao.deleteFilterById(id)

    suspend fun recordReplyLog(log: ReplyLogEntity) = replyLogDao.insertLog(log)

    suspend fun clearReplyLogs() = replyLogDao.clearLogs()

    // Settings mutations
    suspend fun setAutoReplyEnabled(enabled: Boolean) = settingsDataStore.setAutoReplyEnabled(enabled)
    suspend fun setReplyInGroups(enabled: Boolean) = settingsDataStore.setReplyInGroups(enabled)
    suspend fun setContactFilterMode(mode: ContactFilterMode) = settingsDataStore.setContactFilterMode(mode)
    suspend fun setReplyDelaySeconds(seconds: Int) = settingsDataStore.setReplyDelaySeconds(seconds)
    suspend fun setCooldownMinutes(minutes: Int) = settingsDataStore.setCooldownMinutes(minutes)
    suspend fun setScheduleEnabled(enabled: Boolean) = settingsDataStore.setScheduleEnabled(enabled)
    suspend fun setScheduleWindow(sh: Int, sm: Int, eh: Int, em: Int) =
        settingsDataStore.setScheduleWindow(sh, sm, eh, em)

    suspend fun setDrivingModeEnabled(enabled: Boolean) = settingsDataStore.setDrivingModeEnabled(enabled)
    suspend fun setAutoDetectDrivingEnabled(enabled: Boolean) =
        settingsDataStore.setAutoDetectDrivingEnabled(enabled)

    suspend fun setDrivingModeReplyText(text: String) = settingsDataStore.setDrivingModeReplyText(text)
    suspend fun toggleSupportedPackage(packageName: String, enabled: Boolean) =
        settingsDataStore.toggleSupportedPackage(packageName, enabled)

    suspend fun setUseAccessibilityFallback(enabled: Boolean) =
        settingsDataStore.setUseAccessibilityFallback(enabled)

    suspend fun setBiometricLockEnabled(enabled: Boolean) =
        settingsDataStore.setBiometricLockEnabled(enabled)

    // Local JSON Backup
    suspend fun exportBackup(uri: Uri): Result<Int> {
        val rules = replyRuleDao.getAllRulesOnce()
        val filters = contactFilterDao.getAllFiltersOnce()
        val templates = replyTemplateDao?.getAllTemplatesOnce().orEmpty()
        return backupManager.exportToUri(uri, rules, filters, templates)
    }

    suspend fun importBackup(uri: Uri, replaceExisting: Boolean): Result<Pair<Int, Int>> {
        return backupManager.importFromUri(uri).map { payload ->
            if (replaceExisting) {
                replyRuleDao.clearAllRules()
                contactFilterDao.clearAllFilters()
                replyTemplateDao?.clearAllTemplates()
            }

            val titleToId = mutableMapOf<String, Long>()
            replyTemplateDao?.getAllTemplatesOnce()?.forEach { existing ->
                titleToId[existing.title.trim().lowercase()] = existing.id
            }

            if (replyTemplateDao != null && payload.templates.isNotEmpty()) {
                val now = System.currentTimeMillis()
                payload.templates.forEach { dto ->
                    val cleanTitle = dto.title.trim()
                    if (cleanTitle.isNotEmpty()) {
                        val existingId = titleToId[cleanTitle.lowercase()]
                        if (existingId == null || replaceExisting) {
                            val newId = replyTemplateDao.insertTemplate(
                                ReplyTemplateEntity(
                                    title = cleanTitle,
                                    category = dto.category.trim().ifBlank { ReplyTemplateEntity.CATEGORY_GENERAL },
                                    body = dto.body,
                                    replyType = dto.replyType,
                                    imageUri = dto.imageUri,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                            titleToId[cleanTitle.lowercase()] = newId
                        }
                    }
                }
            }

            val importedRules = payload.rules.map {
                val linkedId = it.templateTitle?.trim()?.lowercase()?.let { key -> titleToId[key] }
                ReplyRuleEntity(
                    keyword = it.keyword,
                    replyText = it.replyText,
                    matchType = it.matchType,
                    isEnabled = it.isEnabled,
                    priority = it.priority,
                    replyType = it.replyType,
                    imageUri = it.imageUri,
                    templateId = linkedId
                )
            }
            val importedFilters = payload.contactFilters.map {
                ContactFilterEntity(
                    contactName = it.contactName,
                    listType = it.listType,
                    notes = it.notes
                )
            }
            if (importedRules.isNotEmpty()) replyRuleDao.insertRules(importedRules)
            if (importedFilters.isNotEmpty()) contactFilterDao.insertFilters(importedFilters)
            importedRules.size to importedFilters.size
        }
    }
}
