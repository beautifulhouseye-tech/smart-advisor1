package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ContactFilterEntity
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ReplyTemplateEntity
import com.example.data.preferences.AutoMateSettings
import com.example.data.preferences.ContactFilterMode
import com.example.data.repository.AutoMateRepository
import com.example.driving.DrivingModeManager
import com.example.engine.PendingReplyCoordinator
import com.example.engine.RuleMatcher
import com.example.engine.ServiceConnectionStatus
import com.example.service.AutoReplyKeepAliveService
import com.example.service.ServiceStatusHelper
import com.example.service.SystemPermissionsState
import com.example.worker.ScheduleCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val settings: AutoMateSettings = AutoMateSettings(),
    val rules: List<ReplyRuleEntity> = emptyList(),
    val templates: List<ReplyTemplateEntity> = emptyList(),
    val contactFilters: List<ContactFilterEntity> = emptyList(),
    val recentLogs: List<ReplyLogEntity> = emptyList(),
    val todayRepliesCount: Int = 0,
    val serviceConnection: ServiceConnectionStatus = ServiceConnectionStatus(),
    val permissions: SystemPermissionsState = SystemPermissionsState(),
    val statusMessage: String? = null,
    val isSettingsLoaded: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AutoMateRepository,
    private val pendingReplyCoordinator: PendingReplyCoordinator,
    private val scheduleCoordinator: ScheduleCoordinator,
    private val drivingModeManager: DrivingModeManager
) : ViewModel() {

    private val permissionsFlow = MutableStateFlow(ServiceStatusHelper.inspectPermissions(context))
    private val statusMessageFlow = MutableStateFlow<String?>(null)

    private val coreDataFlow = combine(
        repository.settingsFlow,
        repository.rulesFlow,
        repository.templatesFlow,
        repository.contactFiltersFlow,
        repository.recentLogsFlow
    ) { settings, rules, templates, filters, logs ->
        CoreDataBundle(settings, rules, templates, filters, logs, 0)
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreDataFlow,
        repository.observeTodayRepliesCount(),
        pendingReplyCoordinator.connectionStatus,
        permissionsFlow,
        statusMessageFlow
    ) { core, todayCount, conn, perms, msg ->
        MainUiState(
            settings = core.settings,
            rules = core.rules,
            templates = core.templates,
            contactFilters = core.filters,
            recentLogs = core.logs,
            todayRepliesCount = todayCount,
            serviceConnection = conn,
            permissions = perms,
            statusMessage = msg,
            isSettingsLoaded = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MainUiState(permissions = ServiceStatusHelper.inspectPermissions(context))
    )

    init {
        viewModelScope.launch {
            repository.ensureStarterRulesIfEmpty()
        }
    }

    fun refreshPermissions() {
        permissionsFlow.value = ServiceStatusHelper.inspectPermissions(context)
    }

    fun toggleAutoReply(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoReplyEnabled(enabled)
            val current = repository.getCurrentSettings()
            AutoReplyKeepAliveService.syncState(
                context = context,
                active = enabled || current.isDrivingModeEnabled,
                drivingMode = current.isDrivingModeEnabled
            )
            statusMessageFlow.value = if (enabled) {
                "تم تشغيل الرد التلقائي بنجاح"
            } else {
                "تم إيقاف الرد التلقائي"
            }
        }
    }

    fun toggleDrivingMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDrivingModeEnabled(enabled)
            val current = repository.getCurrentSettings()
            AutoReplyKeepAliveService.syncState(
                context = context,
                active = current.isAutoReplyEnabled || enabled,
                drivingMode = enabled
            )
            statusMessageFlow.value = if (enabled) {
                "تم تفعيل وضع القيادة الذكي"
            } else {
                "تم إيقاف وضع القيادة"
            }
        }
    }

    fun setAutoDetectDriving(enabled: Boolean) {
        viewModelScope.launch {
            val applied = drivingModeManager.setAutoDrivingDetection(enabled)
            if (enabled && !applied) {
                repository.setAutoDetectDrivingEnabled(false)
                statusMessageFlow.value = "يرجى منح صلاحية النشاط البدني لتفعيل الكشف التلقائي"
            } else {
                repository.setAutoDetectDrivingEnabled(enabled)
                statusMessageFlow.value = if (enabled) {
                    "تم تفعيل الكشف التلقائي للقيادة"
                } else {
                    "تم إيقاف الكشف التلقائي للقيادة"
                }
            }
            refreshPermissions()
        }
    }

    fun updateDrivingReplyText(text: String) {
        viewModelScope.launch {
            repository.setDrivingModeReplyText(text)
            statusMessageFlow.value = "تم تحديث رسالة وضع القيادة"
        }
    }

    fun saveRule(rule: ReplyRuleEntity) {
        viewModelScope.launch {
            repository.upsertRule(rule)
            statusMessageFlow.value = if (rule.id == 0L) "تمت إضافة القاعدة" else "تم تحديث القاعدة"
        }
    }

    fun toggleRuleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.setRuleEnabled(id, enabled)
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch {
            repository.deleteRule(id)
            statusMessageFlow.value = "تم حذف القاعدة"
        }
    }

    fun saveTemplate(template: ReplyTemplateEntity) {
        viewModelScope.launch {
            repository.upsertTemplate(template)
            statusMessageFlow.value = if (template.id == 0L) {
                "تم حفظ القالب الجديد بنجاح"
            } else {
                "تم تحديث القالب بنجاح"
            }
        }
    }

    fun duplicateTemplate(template: ReplyTemplateEntity) {
        viewModelScope.launch {
            repository.duplicateTemplate(template)
            statusMessageFlow.value = "تم نسخ القالب بنجاح"
        }
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            repository.deleteTemplate(id)
            statusMessageFlow.value = "تم حذف القالب"
        }
    }

    fun setReplyInGroups(enabled: Boolean) {
        viewModelScope.launch {
            repository.setReplyInGroups(enabled)
        }
    }

    fun setContactFilterMode(mode: ContactFilterMode) {
        viewModelScope.launch {
            repository.setContactFilterMode(mode)
        }
    }

    fun addContactFilter(name: String, listType: ContactFilterListType, notes: String = "") {
        viewModelScope.launch {
            repository.addContactFilter(name, listType, notes)
            statusMessageFlow.value = "تمت إضافة جهة الاتصال إلى القائمة"
        }
    }

    fun deleteContactFilter(id: Long) {
        viewModelScope.launch {
            repository.deleteContactFilter(id)
        }
    }

    fun setReplyDelaySeconds(seconds: Int) {
        viewModelScope.launch {
            repository.setReplyDelaySeconds(seconds)
        }
    }

    fun setCooldownMinutes(minutes: Int) {
        viewModelScope.launch {
            repository.setCooldownMinutes(minutes)
        }
    }

    fun setScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setScheduleEnabled(enabled)
            val s = repository.getCurrentSettings()
            scheduleCoordinator.syncSchedule(
                enabled = enabled,
                startHour = s.scheduleStartHour,
                startMinute = s.scheduleStartMinute,
                endHour = s.scheduleEndHour,
                endMinute = s.scheduleEndMinute
            )
            statusMessageFlow.value = if (enabled) {
                "تم تفعيل الجدول الزمني عبر WorkManager"
            } else {
                "تم إلغاء الجدول الزمني"
            }
        }
    }

    fun setScheduleWindow(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        viewModelScope.launch {
            repository.setScheduleWindow(startHour, startMinute, endHour, endMinute)
            val s = repository.getCurrentSettings()
            if (s.isScheduleEnabled) {
                scheduleCoordinator.syncSchedule(
                    enabled = true,
                    startHour = startHour,
                    startMinute = startMinute,
                    endHour = endHour,
                    endMinute = endMinute
                )
            }
            statusMessageFlow.value = "تم تحديث أوقات الجدول الزمني"
        }
    }

    fun toggleSupportedPackage(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleSupportedPackage(packageName, enabled)
        }
    }

    fun setUseAccessibilityFallback(enabled: Boolean) {
        viewModelScope.launch {
            repository.setUseAccessibilityFallback(enabled)
        }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setBiometricLockEnabled(enabled)
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            repository.exportBackup(uri)
                .onSuccess { count ->
                    statusMessageFlow.value = "تم تصدير النسخة الاحتياطية بنجاح ($count عنصر)"
                }
                .onFailure {
                    statusMessageFlow.value = "فشل تصدير الملف: ${it.localizedMessage}"
                }
        }
    }

    fun importBackup(uri: Uri, replaceExisting: Boolean = false) {
        viewModelScope.launch {
            repository.importBackup(uri, replaceExisting)
                .onSuccess { (rulesCount, contactsCount) ->
                    statusMessageFlow.value =
                        "تم استيراد $rulesCount قاعدة و $contactsCount جهة اتصال"
                }
                .onFailure {
                    statusMessageFlow.value = "تعذر قراءة ملف JSON: ${it.localizedMessage}"
                }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearReplyLogs()
            statusMessageFlow.value = "تم مسح سجل الردود"
        }
    }

    fun simulateReplyForMessage(sampleMessage: String): Pair<String, String?> {
        val state = uiState.value
        if (state.settings.isDrivingModeEnabled) {
            return "وضع القيادة" to state.settings.drivingModeReplyText
        }
        val matched = RuleMatcher.findBestMatch(sampleMessage, state.rules)
        return if (matched != null) {
            val linked = matched.templateId?.let { tid -> state.templates.firstOrNull { it.id == tid } }
            val text = linked?.body ?: matched.replyText
            val label = if (linked != null) "قاعدة: ${matched.keyword} (${linked.title})" else "قاعدة: ${matched.keyword}"
            label to text
        } else {
            "لا يوجد تطابق" to null
        }
    }

    fun consumeStatusMessage() {
        statusMessageFlow.value = null
    }

    private data class CoreDataBundle(
        val settings: AutoMateSettings,
        val rules: List<ReplyRuleEntity>,
        val templates: List<ReplyTemplateEntity>,
        val filters: List<ContactFilterEntity>,
        val logs: List<ReplyLogEntity>,
        val todayCount: Int
    )
}
