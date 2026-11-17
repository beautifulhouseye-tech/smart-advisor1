package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.LogFilter
import com.example.ui.viewmodel.LogsViewModel
import com.example.ui.viewmodel.RedactedLogItem
import com.example.worker.MessageQueueWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val logs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val retryingLogIds by viewModel.retryingLogIds.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("سجل الرسائل") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("logs_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.testTag("logs_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "مسح السجل",
                            tint = MaterialTheme.colorScheme.error
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
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        label = { Text("بحث بالاسم أو الرسالة") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "بحث"
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("logs_search_input")
                    )
                }

                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LogFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { viewModel.selectFilter(filter) },
                                label = { Text(filter.labelAr) }
                            )
                        }
                    }
                }

                if (logs.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
                                    imageVector = Icons.Filled.History,
                                    contentDescription = "لا توجد سجلات",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "لا توجد رسائل مطابقة في السجل",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                } else {
                    items(logs, key = { it.id }) { item ->
                        LogItemCard(
                            item = item,
                            isRetrying = item.id in retryingLogIds,
                            onManualRetry = { viewModel.manualRetry(item.id) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("تأكيد مسح السجل") },
            text = { Text("هل أنت متأكد من مسح جميع سجلات الرسائل والردود المحفوظة؟") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllLogs()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("confirm_clear_logs_btn")
                ) {
                    Text("مسح الكل")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun LogItemCard(
    item: RedactedLogItem,
    isRetrying: Boolean,
    onManualRetry: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault()) }

    val cardContainerColor = when {
        item.isSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        item.isDeliveryUnknown -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }

    val statusIcon = when {
        item.isSkipped -> Icons.Filled.Block
        item.isDeliveryUnknown -> Icons.AutoMirrored.Filled.HelpOutline
        item.isSuccess -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Error
    }

    val statusContentDesc = when {
        item.isSkipped -> "متخطى"
        item.isDeliveryUnknown -> "حالة تسليم غير مؤكدة"
        item.isSuccess -> "ناجح"
        else -> "فاشل"
    }

    val statusTint = when {
        item.isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant
        item.isDeliveryUnknown -> MaterialTheme.colorScheme.tertiary
        item.isSuccess -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    val badgeContainerColor = when {
        item.isSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        item.isDeliveryUnknown -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        item.isSuccess -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    }

    val badgeTextColor = when {
        item.isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant
        item.isDeliveryUnknown -> MaterialTheme.colorScheme.onTertiaryContainer
        item.isSuccess -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    val badgePrefix = when {
        item.isSkipped -> "سبب التخطي"
        item.isDeliveryUnknown -> "غير مؤكد"
        item.isSuccess -> "الرد"
        else -> "الخطأ"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("log_item_${item.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardContainerColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                        contentDescription = statusContentDesc,
                        tint = statusTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (item.isGroup) "${item.senderName} (مجموعة)" else item.senderName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (item.isSkipped) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatter.format(Date(item.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "الوارد: ${item.incomingMessage}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                color = badgeContainerColor,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "$badgePrefix: ${item.sentReply}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = badgeTextColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            if (item.isDeliveryUnknown) {
                OutlinedButton(
                    onClick = onManualRetry,
                    enabled = !isRetrying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_retry_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "إعادة محاولة يدوي",
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isRetrying) "جارٍ إعادة المحاولة..." else "إعادة محاولة يدوي",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            Text(
                text = "${item.packageName} • ${formatDeliveryLabel(item.deliveryMethod)}" +
                    if (item.retryCount > 0) " • محاولات: ${item.retryCount}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDeliveryLabel(method: String): String {
    return when (method) {
        MessageQueueWorker.STATUS_SKIPPED_DISABLED -> "تخطي: معطل ($method)"
        MessageQueueWorker.STATUS_SKIPPED_SELF -> "تخطي: صدى/ملخص ($method)"
        MessageQueueWorker.STATUS_SKIPPED_POLICY -> "تخطي: السياسة ($method)"
        MessageQueueWorker.STATUS_SKIPPED_COOLDOWN -> "تخطي: فترة التهدئة ($method)"
        MessageQueueWorker.STATUS_SKIPPED_DEDUP -> "تخطي: مكرر ($method)"
        MessageQueueWorker.STATUS_SKIPPED_RATE -> "تخطي: حد المعدل ($method)"
        MessageQueueWorker.STATUS_DELIVERY_UNKNOWN -> "تسليم غير مؤكد ($method)"
        MessageQueueWorker.STATUS_FAILED_CONFIRMED -> "فشل مؤكد ($method)"
        else -> method
    }
}
