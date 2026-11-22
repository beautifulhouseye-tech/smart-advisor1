package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.engine.DiagnosticAuthState
import com.example.engine.DiagnosticCategory
import com.example.engine.DiagnosticOutcome
import com.example.engine.DiagnosticSanitizer
import com.example.engine.DiagnosticTraceRecord
import com.example.ui.components.BidiDisplayFormatter
import com.example.ui.viewmodel.DiagnosticsUiState
import com.example.ui.viewmodel.DiagnosticsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let(viewModel::exportSanitizedReport)
    }

    DiagnosticsScreenContent(
        uiState = uiState,
        onBack = onBack,
        onOutcomeFilter = viewModel::setOutcomeFilter,
        onCategoryFilter = viewModel::setCategoryFilter,
        onEngineFilter = viewModel::setEngineFilter,
        onSelectRecord = viewModel::selectRecord,
        onClearLogs = viewModel::clearDiagnostics,
        onCopyReport = {
            clipboard.setText(AnnotatedString(viewModel.buildSanitizedReportForCopy()))
            viewModel.notifyCopied()
        },
        onCopySingleRecord = { record ->
            clipboard.setText(AnnotatedString(viewModel.buildSanitizedSingleRecordForCopy(record)))
            viewModel.notifyCopied()
        },
        onExportReport = {
            exportLauncher.launch("automate_sa_diagnostics.json")
        },
        onConsumeStatusMessage = viewModel::consumeStatusMessage,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreenContent(
    uiState: DiagnosticsUiState,
    onBack: () -> Unit,
    onOutcomeFilter: (DiagnosticOutcome?) -> Unit,
    onCategoryFilter: (DiagnosticCategory?) -> Unit,
    onEngineFilter: (String?) -> Unit,
    onSelectRecord: (DiagnosticTraceRecord?) -> Unit,
    onClearLogs: () -> Unit,
    onCopyReport: () -> Unit,
    onCopySingleRecord: (DiagnosticTraceRecord) -> Unit,
    onExportReport: () -> Unit,
    onConsumeStatusMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onConsumeStatusMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "سجل التشخيص والأخطاء",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("diagnostics_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onCopyReport,
                        enabled = uiState.allRecords.isNotEmpty(),
                        modifier = Modifier.testTag("diagnostics_copy_json_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "نسخ التقرير المنقح"
                        )
                    }
                    IconButton(
                        onClick = onExportReport,
                        enabled = uiState.allRecords.isNotEmpty(),
                        modifier = Modifier.testTag("diagnostics_export_json_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "تصدير تقرير التشخيص"
                        )
                    }
                    IconButton(
                        onClick = { showClearConfirm = true },
                        enabled = uiState.allRecords.isNotEmpty(),
                        modifier = Modifier.testTag("diagnostics_clear_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "مسح سجل التشخيص",
                            tint = if (uiState.allRecords.isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status & Privacy Banner
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diagnostics_status_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (uiState.detailedLoggingEnabled) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = "حالة التسجيل والخصوصية",
                                tint = if (uiState.detailedLoggingEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (uiState.detailedLoggingEnabled) {
                                        "التسجيل التشخيصي التفصيلي مفعّل"
                                    } else {
                                        "التسجيل التفصيلي متوقف (يتم تسجيل الأخطاء والشذوذات الحرجة فقط)"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "محفوظ محلياً بشكل مشفر (بحد أقصى 100 حدث) مع تنقيح المفاتيح وأرقام الهواتف ومحتوى الرسائل. للتحكم في المفتاح افتح الإعدادات العامة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Summary Strip
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SummaryStatCard(
                            label = "الإجمالي",
                            count = uiState.totalCount,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            textColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("diag_stat_total")
                        )
                        SummaryStatCard(
                            label = "فاشلة",
                            count = uiState.failureCount,
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                            textColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("diag_stat_failure")
                        )
                        SummaryStatCard(
                            label = "غير معروفة",
                            count = uiState.unknownCount,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                            textColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("diag_stat_unknown")
                        )
                        SummaryStatCard(
                            label = "ناجحة",
                            count = uiState.successCount,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            textColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("diag_stat_success")
                        )
                    }
                }

                // Outcome Filter Chips
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = uiState.outcomeFilter == null,
                            onClick = { onOutcomeFilter(null) },
                            label = { Text("الكل (${uiState.totalCount})") },
                            modifier = Modifier.testTag("diag_filter_outcome_ALL")
                        )
                        DiagnosticOutcome.entries.forEach { outcome ->
                            val count = when (outcome) {
                                DiagnosticOutcome.FAILURE -> uiState.failureCount
                                DiagnosticOutcome.UNKNOWN -> uiState.unknownCount
                                DiagnosticOutcome.SUCCESS -> uiState.successCount
                            }
                            FilterChip(
                                selected = uiState.outcomeFilter == outcome,
                                onClick = {
                                    onOutcomeFilter(if (uiState.outcomeFilter == outcome) null else outcome)
                                },
                                label = { Text("${outcome.labelAr} ($count)") },
                                modifier = Modifier.testTag("diag_filter_outcome_${outcome.name}")
                            )
                        }
                    }
                }

                // Category Filter Chips (Scrollable horizontal row)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagnosticCategory.entries.forEach { category ->
                            val count = uiState.countsByCategory[category] ?: 0
                            FilterChip(
                                selected = uiState.categoryFilter == category,
                                onClick = {
                                    onCategoryFilter(if (uiState.categoryFilter == category) null else category)
                                },
                                label = {
                                    Text(
                                        BidiDisplayFormatter.isolateTokensInRtlText(
                                            "${category.labelAr} ($count)"
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("diag_filter_category_${category.name}")
                            )
                        }
                    }
                }

                // Engine Filter Chips (when engines observed)
                if (uiState.availableEngines.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "المحرك:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            uiState.availableEngines.forEach { engine ->
                                FilterChip(
                                    selected = uiState.engineFilter.equals(engine, ignoreCase = true),
                                    onClick = {
                                        onEngineFilter(
                                            if (uiState.engineFilter.equals(engine, ignoreCase = true)) null else engine
                                        )
                                    },
                                    label = {
                                        Text(BidiDisplayFormatter.isolateTokensInRtlText(engine))
                                    },
                                    modifier = Modifier.testTag("diag_filter_engine_$engine")
                                )
                            }
                        }
                    }
                }

                // Records or Empty State
                if (uiState.filteredRecords.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diagnostics_empty_state"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.BugReport,
                                    contentDescription = "لا توجد أحداث تشخيصية",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(38.dp)
                                )
                                Text(
                                    text = if (uiState.allRecords.isEmpty()) {
                                        "لا توجد أحداث تشخيصية مسجلة بعد"
                                    } else {
                                        "لا توجد أحداث مطابقة للفلاتر المختارة"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "ستظهر هنا حالات الاتصال، المصادقة، مراحل المعالجة الـ 12، وانتقالات المحركات بمجرد تنفيذ اختبارات أو ردود.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.filteredRecords, key = { it.id }) { record ->
                        DiagnosticRecordCard(
                            record = record,
                            onClick = { onSelectRecord(record) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("مسح سجل التشخيص والأخطاء") },
            text = { Text("سيتم حذف جميع أحداث التشخيص المشفرة المحفوظة محلياً (الحد الأقصى 100 حدث). هل تريد المتابعة؟") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearLogs()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("diagnostics_confirm_clear_btn")
                ) {
                    Text("مسح الكل")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    uiState.selectedRecord?.let { record ->
        DiagnosticDetailDialog(
            record = record,
            onDismiss = { onSelectRecord(null) },
            onCopy = { onCopySingleRecord(record) }
        )
    }
}

@Composable
private fun SummaryStatCard(
    label: String,
    count: Int,
    containerColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticRecordCard(
    record: DiagnosticTraceRecord,
    onClick: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.US) }

    val effectiveHasIssues = record.outcome != DiagnosticOutcome.SUCCESS || record.anomalies.isNotEmpty()
    val statusIcon = when {
        record.outcome == DiagnosticOutcome.FAILURE -> Icons.Filled.Error
        record.outcome == DiagnosticOutcome.UNKNOWN || record.anomalies.isNotEmpty() ->
            Icons.AutoMirrored.Filled.HelpOutline
        else -> Icons.Filled.CheckCircle
    }
    val statusTint = when {
        record.outcome == DiagnosticOutcome.FAILURE -> MaterialTheme.colorScheme.error
        record.outcome == DiagnosticOutcome.UNKNOWN || record.anomalies.isNotEmpty() ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val badgeBg = when {
        record.outcome == DiagnosticOutcome.FAILURE ->
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        record.outcome == DiagnosticOutcome.UNKNOWN || record.anomalies.isNotEmpty() ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .testTag("diag_record_card_${record.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Outcome Icon + Operation + Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = record.outcome.labelAr,
                        tint = statusTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = BidiDisplayFormatter.isolateTokensInRtlText(
                            "${record.operationType} • ${record.httpMethod}"
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatter.format(Date(record.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sanitized URL/Path & Latency & HTTP status
            if (record.sanitizedUrlPath.isNotBlank() || record.httpStatusCode >= 0 || record.latencyMs > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = BidiDisplayFormatter.isolateTokensInRtlText(
                            record.sanitizedUrlPath.ifBlank { "داخلي (${record.sourceSide})" }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val metricsText = buildString {
                        if (record.httpStatusCode >= 0) append("HTTP ${record.httpStatusCode}")
                        if (record.latencyMs > 0L) {
                            if (isNotEmpty()) append(" • ")
                            append("${record.latencyMs}ms")
                        }
                    }
                    if (metricsText.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = metricsText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Stage + AuthState + Outcome chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(color = badgeBg, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = if (effectiveHasIssues && record.outcome == DiagnosticOutcome.SUCCESS) {
                            "${record.outcome.labelAr} (مع تحذير)"
                        } else {
                            record.outcome.labelAr
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusTint,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = BidiDisplayFormatter.isolateTokensInRtlText(record.stage.labelAr),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (record.authState != DiagnosticAuthState.NONE) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = record.authState.labelAr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (record.engineUsed.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = BidiDisplayFormatter.isolateTokensInRtlText(record.engineUsed),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                if (record.fallbackTriggered) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "تحويل احتياطي",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Error code + Short description
            if (record.errorCode.isNotBlank() || record.shortDescription.isNotBlank()) {
                val summary = buildString {
                    if (record.errorCode.isNotBlank()) {
                        append('[').append(record.errorCode).append("] ")
                    }
                    append(record.shortDescription)
                }
                Text(
                    text = BidiDisplayFormatter.isolateTokensInRtlText(summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (record.outcome == DiagnosticOutcome.FAILURE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            // Anomalies badges
            if (record.anomalies.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    record.anomalies.forEach { anomaly ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = BidiDisplayFormatter.isolateTokensInRtlText(anomaly.labelAr),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Trace ID footer
            Text(
                text = BidiDisplayFormatter.isolateTokensInRtlText(
                    "معرّف التتبع: ${record.traceId} • التصنيف: ${record.category.labelAr}"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticDetailDialog(
    record: DiagnosticTraceRecord,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = BidiDisplayFormatter.isolateTokensInRtlText("تفاصيل الحدث ${record.traceId}"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow("التاريخ والوقت", formatter.format(Date(record.timestampMs)))
                DetailRow("المصدر", record.sourceSide)
                DetailRow("العملية", "${record.operationType} (${record.httpMethod})")
                if (record.sanitizedUrlPath.isNotBlank()) {
                    DetailRow("المسار المنقح", record.sanitizedUrlPath)
                }
                DetailRow("المرحلة", record.stage.labelAr)
                DetailRow("حالة المصادقة", record.authState.labelAr)
                DetailRow("النتيجة", record.outcome.labelAr)
                DetailRow("التصنيف", record.category.labelAr)
                if (record.httpStatusCode >= 0) {
                    DetailRow("حالة HTTP", record.httpStatusCode.toString())
                }
                DetailRow("المدة", "${record.latencyMs} ms")
                if (record.engineUsed.isNotBlank()) {
                    DetailRow("المحرك", record.engineUsed)
                }
                DetailRow("تفعيل الاحتياطي", if (record.fallbackTriggered) "نعم" else "لا")
                if (record.errorCode.isNotBlank()) {
                    DetailRow("رمز الخطأ", record.errorCode)
                }
                if (record.shortDescription.isNotBlank()) {
                    DetailRow("الوصف", record.shortDescription)
                }
                if (record.anomalies.isNotEmpty()) {
                    DetailRow(
                        "الشذوذات المكتشفة",
                        record.anomalies.joinToString(" • ") { it.labelAr }
                    )
                }
                if (record.wpServerTraceSteps.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "مراحل الخادم (WordPress):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    record.wpServerTraceSteps.forEach { step ->
                        Text(
                            text = BidiDisplayFormatter.isolateTokensInRtlText("• $step"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCopy,
                modifier = Modifier.testTag("diag_detail_copy_btn")
            ) {
                Text("نسخ JSON المنقح")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = BidiDisplayFormatter.isolateTokensInRtlText(value),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
