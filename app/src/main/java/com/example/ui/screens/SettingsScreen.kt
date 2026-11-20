package com.example.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.preferences.AutoMateSettings
import com.example.data.preferences.SupportedMessagingApp
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AutoMateSettings,
    contactFilterCount: Int,
    onNavigateToContactsFilter: () -> Unit,
    onSetReplyInGroups: (Boolean) -> Unit,
    onSetDelaySeconds: (Int) -> Unit,
    onSetCooldownMinutes: (Int) -> Unit,
    onSetScheduleEnabled: (Boolean) -> Unit,
    onSetScheduleWindow: (Int, Int, Int, Int) -> Unit,
    onSetAutoDetectDriving: (Boolean) -> Unit,
    onUpdateDrivingReplyText: (String) -> Unit,
    onTogglePackage: (String, Boolean) -> Unit,
    onSetAccessibilityFallback: (Boolean) -> Unit,
    onExportJson: (Uri) -> Unit,
    onImportJson: (Uri, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isBiometricAvailable: Boolean = false,
    onSetBiometricLock: (Boolean) -> Unit = {}
) {
    var pickingStartTime by remember { mutableStateOf(false) }
    var pickingEndTime by remember { mutableStateOf(false) }
    var drivingTextDraft by remember(settings.drivingModeReplyText) {
        mutableStateOf(settings.drivingModeReplyText)
    }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onSetAutoDetectDriving(granted)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExportJson) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "الإعدادات العامة",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

            // 1. Contacts Filter & Group Chats
            item {
                SettingsSectionCard(title = "الجمهور والمجموعات") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onNavigateToContactsFilter)
                            .padding(vertical = 8.dp)
                            .testTag("open_contacts_filter_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.People,
                                contentDescription = "فلترة جهات الاتصال",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "فلترة جهات الاتصال",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${settings.contactFilterMode.labelAr} • $contactFilterCount اسم محفوظ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "فتح إدارة جهات الاتصال"
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Groups,
                                contentDescription = "دعم المجموعات",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Column {
                                Text(
                                    text = "الرد التلقائي في المجموعات",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "تفعيل أو إيقاف الرد التلقائي داخل القروبات",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.replyInGroups,
                            onCheckedChange = onSetReplyInGroups,
                            modifier = Modifier.testTag("reply_in_groups_switch")
                        )
                    }
                }
            }

            // 2. Smart Delay & Cooldown
            item {
                SettingsSectionCard(title = "الرد الذكي (التأخير والتهدئة)") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "تأخير الرد",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = if (settings.replyDelaySeconds == 0) {
                                "الرد فوري (0 ثانية)"
                            } else {
                                "تأخير الإرسال: ${settings.replyDelaySeconds} ثوانٍ"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Slider(
                        value = settings.replyDelaySeconds.toFloat(),
                        onValueChange = { onSetDelaySeconds(it.roundToInt()) },
                        valueRange = 0f..60f,
                        steps = 11,
                        modifier = Modifier.testTag("reply_delay_slider")
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 3, 5, 10, 30).forEach { sec ->
                            FilterChip(
                                selected = settings.replyDelaySeconds == sec,
                                onClick = { onSetDelaySeconds(sec) },
                                label = { Text(if (sec == 0) "فوري" else "${sec} ث") }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "عدم تكرار الرد لنفس الشخص قبل مرور:",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 1, 2, 5, 15).forEach { mins ->
                                FilterChip(
                                    selected = settings.cooldownMinutes == mins,
                                    onClick = { onSetCooldownMinutes(mins) },
                                    label = { Text(if (mins == 0) "بدون حد" else "$mins د") }
                                )
                            }
                        }
                    }
                }
            }

            // 3. WorkManager Schedule
            item {
                SettingsSectionCard(title = "الجدول الزمني (WorkManager)") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = "الجدول الزمني",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "تشغيل وإيقاف تلقائي حسب الوقت",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "يعمل بالخلفية بدقة عبر WorkManager",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.isScheduleEnabled,
                            onCheckedChange = onSetScheduleEnabled,
                            modifier = Modifier.testTag("schedule_enabled_switch")
                        )
                    }

                    AnimatedVisibility(visible = settings.isScheduleEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { pickingStartTime = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("schedule_start_time_btn")
                            ) {
                                Text(
                                    "من: ${formatTime(settings.scheduleStartHour, settings.scheduleStartMinute)}"
                                )
                            }
                            OutlinedButton(
                                onClick = { pickingEndTime = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("schedule_end_time_btn")
                            ) {
                                Text(
                                    "إلى: ${formatTime(settings.scheduleEndHour, settings.scheduleEndMinute)}"
                                )
                            }
                        }
                    }
                }
            }

            // 4. Driving Mode (Activity Recognition)
            item {
                SettingsSectionCard(title = "وضع القيادة (Activity Recognition)") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DirectionsCar,
                                contentDescription = "الكشف التلقائي للقيادة",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Column {
                                Text(
                                    text = "كشف ركوب السيارة تلقائياً",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "يستخدم Activity Recognition API لتفعيل وضع القيادة",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.isAutoDetectDrivingEnabled,
                            onCheckedChange = { enable ->
                                if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                } else {
                                    onSetAutoDetectDriving(enable)
                                }
                            },
                            modifier = Modifier.testTag("auto_detect_driving_switch")
                        )
                    }

                    OutlinedTextField(
                        value = drivingTextDraft,
                        onValueChange = { drivingTextDraft = it },
                        label = { Text("رسالة الرد أثناء القيادة") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onUpdateDrivingReplyText(drivingTextDraft.trim()) },
                        enabled = drivingTextDraft.isNotBlank() && drivingTextDraft != settings.drivingModeReplyText,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("حفظ رسالة القيادة")
                    }
                }
            }

            // 5. Supported Messaging Apps & Accessibility Fallback
            item {
                SettingsSectionCard(title = "التطبيقات المدعومة وطريقة الإرسال") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Apps,
                            contentDescription = "التطبيقات المدعومة",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "اختر التطبيقات التي يراقبها AutoMate AI:",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    SupportedMessagingApp.entries.forEach { app ->
                        val checked = app.packageName in settings.enabledPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTogglePackage(app.packageName, !checked) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(app.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onTogglePackage(app.packageName, it) }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccessibilityNew,
                                contentDescription = "إمكانية الوصول",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    "الإرسال عبر AccessibilityService",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "كتابة الرد والضغط على إرسال تلقائياً عند تعذر الرد المباشر",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.useAccessibilityFallback,
                            onCheckedChange = onSetAccessibilityFallback
                        )
                    }
                }
            }

            // 6. Security & Biometric Lock
            item {
                SettingsSectionCard(title = "الأمان والقفل البيومتري") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Fingerprint,
                                contentDescription = "القفل البيومتري",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "قفل التطبيق بالبصمة",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (isBiometricAvailable) {
                                        "طلب المصادقة البيومترية عند فتح التطبيق"
                                    } else {
                                        "المصادقة البيومترية غير مفعلة على هذا الجهاز"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.isBiometricLockEnabled,
                            onCheckedChange = onSetBiometricLock,
                            enabled = isBiometricAvailable,
                            modifier = Modifier.testTag("biometric_lock_switch")
                        )
                    }
                }
            }

            // 7. Local Backup (JSON)
            item {
                SettingsSectionCard(title = "النسخ الاحتياطي المحلي (JSON)") {
                    Text(
                        text = "احفظ قواعد الردود وقوائم جهات الاتصال في ملف JSON محلي أو استعدها في أي وقت.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { exportLauncher.launch("automate_ai_backup.json") },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("settings_export_json_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Upload,
                                contentDescription = "تصدير",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تصدير", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("settings_import_json_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "استيراد",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("استيراد", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
            }
        }
    }

    if (pickingStartTime) {
        TimePickerModal(
            title = "وقت بدء التشغيل",
            initialHour = settings.scheduleStartHour,
            initialMinute = settings.scheduleStartMinute,
            onDismiss = { pickingStartTime = false },
            onConfirm = { h, m ->
                onSetScheduleWindow(h, m, settings.scheduleEndHour, settings.scheduleEndMinute)
                pickingStartTime = false
            }
        )
    }

    if (pickingEndTime) {
        TimePickerModal(
            title = "وقت الإيقاف",
            initialHour = settings.scheduleEndHour,
            initialMinute = settings.scheduleEndMinute,
            onDismiss = { pickingEndTime = false },
            onConfirm = { h, m ->
                onSetScheduleWindow(settings.scheduleStartHour, settings.scheduleStartMinute, h, m)
                pickingEndTime = false
            }
        )
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("استيراد النسخة الاحتياطية") },
            text = { Text("هل تريد دمج القواعد المستوردة مع الحالية أم استبدالها بالكامل؟") },
            confirmButton = {
                Button(onClick = {
                    onImportJson(uri, false)
                    pendingImportUri = null
                }) {
                    Text("دمج")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onImportJson(uri, true)
                    pendingImportUri = null
                }) {
                    Text("استبدال الكل")
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerModal(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TimePicker(state = state)
        },
        confirmButton = {
            Button(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("تأكيد")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

private fun formatTime(hour: Int, minute: Int): String {
    val period = if (hour < 12) "ص" else "م"
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return String.format(Locale.US, "%02d:%02d %s", h12, minute, period)
}
