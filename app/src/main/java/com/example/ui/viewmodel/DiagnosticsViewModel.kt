package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.SettingsDataStore
import com.example.engine.DiagnosticCategory
import com.example.engine.DiagnosticOutcome
import com.example.engine.DiagnosticSanitizer
import com.example.engine.DiagnosticTraceRecord
import com.example.engine.SafeDiagnosticLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import javax.inject.Inject

data class DiagnosticsUiState(
    val detailedLoggingEnabled: Boolean = false,
    val allRecords: List<DiagnosticTraceRecord> = emptyList(),
    val filteredRecords: List<DiagnosticTraceRecord> = emptyList(),
    val availableEngines: List<String> = emptyList(),
    val outcomeFilter: DiagnosticOutcome? = null,
    val categoryFilter: DiagnosticCategory? = null,
    val engineFilter: String? = null,
    val selectedRecord: DiagnosticTraceRecord? = null,
    val totalCount: Int = 0,
    val failureCount: Int = 0,
    val unknownCount: Int = 0,
    val successCount: Int = 0,
    val countsByCategory: Map<DiagnosticCategory, Int> = emptyMap(),
    val statusMessage: String? = null
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticLogger: SafeDiagnosticLogger,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val outcomeFilterFlow = MutableStateFlow<DiagnosticOutcome?>(null)
    private val categoryFilterFlow = MutableStateFlow<DiagnosticCategory?>(null)
    private val engineFilterFlow = MutableStateFlow<String?>(null)
    private val selectedRecordFlow = MutableStateFlow<DiagnosticTraceRecord?>(null)
    private val statusMessageFlow = MutableStateFlow<String?>(null)

    private val filtersFlow = combine(
        outcomeFilterFlow,
        categoryFilterFlow,
        engineFilterFlow,
        selectedRecordFlow,
        statusMessageFlow
    ) { outcome, category, engine, selected, msg ->
        FilterBundle(outcome, category, engine, selected, msg)
    }

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        diagnosticLogger.traceRecords,
        settingsDataStore.settingsFlow.map { it.diagnosticLoggingEnabled },
        filtersFlow
    ) { records, detailedEnabled, filters ->
        val filtered = records.filter { record ->
            val outcomeOk = filters.outcome == null || record.outcome == filters.outcome
            val categoryOk = filters.category == null || record.category == filters.category
            val engineOk = filters.engine == null ||
                record.engineUsed.equals(filters.engine, ignoreCase = true)
            outcomeOk && categoryOk && engineOk
        }

        val engines = records.mapNotNull { r ->
            r.engineUsed.trim().takeIf { it.isNotEmpty() }
        }.distinct().sorted()

        DiagnosticsUiState(
            detailedLoggingEnabled = detailedEnabled,
            allRecords = records,
            filteredRecords = filtered,
            availableEngines = engines,
            outcomeFilter = filters.outcome,
            categoryFilter = filters.category,
            engineFilter = filters.engine,
            selectedRecord = filters.selected,
            totalCount = records.size,
            failureCount = records.count { it.outcome == DiagnosticOutcome.FAILURE },
            unknownCount = records.count { it.outcome == DiagnosticOutcome.UNKNOWN },
            successCount = records.count { it.outcome == DiagnosticOutcome.SUCCESS },
            countsByCategory = records.groupingBy { it.category }.eachCount(),
            statusMessage = filters.statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DiagnosticsUiState(
            detailedLoggingEnabled = diagnosticLogger.isDetailedLoggingEnabled(),
            allRecords = diagnosticLogger.traceRecordsSnapshot(),
            filteredRecords = diagnosticLogger.traceRecordsSnapshot()
        )
    )

    fun setOutcomeFilter(outcome: DiagnosticOutcome?) {
        outcomeFilterFlow.value = outcome
    }

    fun setCategoryFilter(category: DiagnosticCategory?) {
        categoryFilterFlow.value = category
    }

    fun setEngineFilter(engine: String?) {
        engineFilterFlow.value = engine
    }

    fun selectRecord(record: DiagnosticTraceRecord?) {
        selectedRecordFlow.value = record
    }

    fun clearDiagnostics() {
        diagnosticLogger.clearTraceLogsOnly()
        selectedRecordFlow.value = null
        statusMessageFlow.value = "تم مسح سجل التشخيص والأخطاء"
    }

    fun buildSanitizedReportForCopy(): String =
        diagnosticLogger.exportSanitizedReportJson()

    fun buildSanitizedSingleRecordForCopy(record: DiagnosticTraceRecord): String =
        DiagnosticSanitizer.exportSanitizedReportJson(listOf(record))

    fun notifyCopied() {
        statusMessageFlow.value = "تم نسخ تقرير التشخيص المنقّح (JSON)"
    }

    fun exportSanitizedReport(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = diagnosticLogger.exportSanitizedReportJson()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                            writer.write(json)
                        }
                    } ?: error("تعذر فتح ملف التصدير")
                }
            }
            result.onSuccess {
                statusMessageFlow.value = "تم تصدير تقرير التشخيص بنجاح"
            }.onFailure { err ->
                statusMessageFlow.value = "تعذر تصدير التقرير: ${err.localizedMessage ?: "خطأ غير معروف"}"
            }
        }
    }

    fun consumeStatusMessage() {
        statusMessageFlow.value = null
    }

    private data class FilterBundle(
        val outcome: DiagnosticOutcome?,
        val category: DiagnosticCategory?,
        val engine: String?,
        val selected: DiagnosticTraceRecord?,
        val statusMessage: String?
    )
}
