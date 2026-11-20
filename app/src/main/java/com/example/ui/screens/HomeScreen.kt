package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.entity.ReplyLogEntity
import com.example.service.ServiceStatusHelper
import com.example.ui.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleDrivingMode: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onClearLogs: () -> Unit,
    onNavigateToRules: () -> Unit,
    onNavigateToToolsAndMemory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToTestReply: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefreshPermissions()
    }

    val isSystemReady = uiState.permissions.isNotificationListenerEnabled &&
        uiState.permissions.isAccessibilityEnabled

    val activeRulesCount = remember(uiState.rules) {
        uiState.rules.count { it.isEnabled }
    }
    val recentPreviewLogs = remember(uiState.recentLogs) {
        uiState.recentLogs.take(3)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AutoMate AI",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TextButton(
                        onClick = onNavigateToTestReply,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_topbar_test_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "اختبار الرد",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "اختبار",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Hero Cockpit Switch Card
                item {
                    MasterControlHeroCard(
                        isAutoReplyEnabled = uiState.settings.isAutoReplyEnabled,
                        isDrivingModeEnabled = uiState.settings.isDrivingModeEnabled,
                        isScheduleEnabled = uiState.settings.isScheduleEnabled,
                        scheduleText = formatWindow(
                            uiState.settings.scheduleStartHour,
                            uiState.settings.scheduleStartMinute,
                            uiState.settings.scheduleEndHour,
                            uiState.settings.scheduleEndMinute
                        ),
                        onToggleAutoReply = onToggleAutoReply
                    )
                }

                // 2. Compact 3-Metric Row
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricChipCard(
                            title = "القواعد النشطة",
                            value = "$activeRulesCount",
                            icon = Icons.Filled.Bolt,
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = onNavigateToRules,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("metric_rules_card")
                        )
                        MetricChipCard(
                            title = "ردود اليوم",
                            value = "${uiState.todayRepliesCount}",
                            icon = Icons.AutoMirrored.Filled.Send,
                            tint = MaterialTheme.colorScheme.secondary,
                            onClick = onNavigateToStatistics,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("metric_replies_card")
                        )
                        MetricChipCard(
                            title = "زمن التأخير",
                            value = if (uiState.settings.replyDelaySeconds == 0) "فوري" else "${uiState.settings.replyDelaySeconds} ث",
                            icon = Icons.Filled.Schedule,
                            tint = MaterialTheme.colorScheme.tertiary,
                            onClick = onNavigateToSettings,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("metric_delay_card")
                        )
                    }
                }

                // 3. Smart Collapsible Permissions Card
                item {
                    PermissionsHealthCard(
                        notifListenerGranted = uiState.permissions.isNotificationListenerEnabled,
                        accessibilityGranted = uiState.permissions.isAccessibilityEnabled,
                        postNotifGranted = uiState.permissions.isPostNotificationsGranted,
                        isAllReady = isSystemReady,
                        onGrantNotifListener = {
                            ServiceStatusHelper.openNotificationListenerSettings(context)
                        },
                        onGrantAccessibility = {
                            ServiceStatusHelper.openAccessibilitySettings(context)
                        },
                        onRequestPostNotif = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }

                // 4. Compact Driving Mode Card
                item {
                    DrivingModeQuickCard(
                        isDrivingModeEnabled = uiState.settings.isDrivingModeEnabled,
                        drivingReplyText = uiState.settings.drivingModeReplyText,
                        autoDetectEnabled = uiState.settings.isAutoDetectDrivingEnabled,
                        onToggleDrivingMode = onToggleDrivingMode,
                        onConfigureDriving = onNavigateToSettings
                    )
                }

                // 5. Primary Unified CTA Bar (No duplicate simulator, no long 8-card stack)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onNavigateToTestReply,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("open_simulator_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = "اختبار الرد الموحد",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "اختبار الرد الموحد",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = onNavigateToToolsAndMemory,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("home_tools_memory_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Memory,
                                contentDescription = "الأدوات والذاكرة",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "الأدوات والذاكرة",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 6. Recent Auto-Replies Preview (Max 3 items)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "آخر الردود التلقائية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = onNavigateToLogs,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("home_view_all_logs_btn")
                            ) {
                                Text(
                                    text = "عرض السجل",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (uiState.recentLogs.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearLogs,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("clear_logs_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteSweep,
                                        contentDescription = "مسح السجل",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (recentPreviewLogs.isEmpty()) {
                    item {
                        EmptyLogsCard()
                    }
                } else {
                    items(recentPreviewLogs, key = { it.id }) { log ->
                        ReplyLogCard(log = log)
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun MasterControlHeroCard(
    isAutoReplyEnabled: Boolean,
    isDrivingModeEnabled: Boolean,
    isScheduleEnabled: Boolean,
    scheduleText: String,
    onToggleAutoReply: (Boolean) -> Unit
) {
    val active = isAutoReplyEnabled || isDrivingModeEnabled
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val badgeColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "badgeColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(184.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_automate_hero),
                contentDescription = "خلفية AutoMate AI",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC090E1A),
                                Color(0xEA0F172A)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(50.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .scale(if (active) pulseScale else 1f)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary
                                        else Color(0xFF94A3B8)
                                    )
                            )
                            Text(
                                text = when {
                                    isDrivingModeEnabled -> "وضع القيادة نشط"
                                    isAutoReplyEnabled -> "الرد التلقائي يعمل"
                                    else -> "النظام متوقف"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Text(
                        text = "AutoMate AI",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFA7F3D0),
                        maxLines = 1
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAutoReplyEnabled) "الرد الذكي مفعّل" else "تشغيل الرد التلقائي",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isScheduleEnabled) {
                                "الجدول الزمني نشط: $scheduleText"
                            } else {
                                "لوحة التحكم السريعة للردود الذكية"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isAutoReplyEnabled,
                        onCheckedChange = onToggleAutoReply,
                        modifier = Modifier.testTag("master_auto_reply_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChipCard(
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DrivingModeQuickCard(
    isDrivingModeEnabled: Boolean,
    drivingReplyText: String,
    autoDetectEnabled: Boolean,
    onToggleDrivingMode: (Boolean) -> Unit,
    onConfigureDriving: () -> Unit
) {
    val accentTint = if (isDrivingModeEnabled) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("driving_mode_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isDrivingModeEnabled) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = accentTint.copy(alpha = 0.16f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.DirectionsCar,
                                contentDescription = "وضع القيادة",
                                tint = accentTint
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "وضع القيادة الذكي",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(if (isDrivingModeEnabled) "نشط الآن" else "متوقف")
                                append(" • ")
                                append(if (autoDetectEnabled) "كشف تلقائي" else "يدوي")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDrivingModeEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onConfigureDriving,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("driving_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "تخصيص وضع القيادة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Switch(
                    checked = isDrivingModeEnabled,
                    onCheckedChange = onToggleDrivingMode,
                    modifier = Modifier.testTag("driving_mode_switch")
                )
            }

            if (isDrivingModeEnabled && drivingReplyText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "رد القيادة: $drivingReplyText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsHealthCard(
    notifListenerGranted: Boolean,
    accessibilityGranted: Boolean,
    postNotifGranted: Boolean,
    isAllReady: Boolean,
    onGrantNotifListener: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onRequestPostNotif: () -> Unit
) {
    var expandedWhenReady by rememberSaveable(isAllReady) { mutableStateOf(!isAllReady) }
    val showDetails = !isAllReady || expandedWhenReady

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isAllReady) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isAllReady) { expandedWhenReady = !expandedWhenReady },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isAllReady) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                        contentDescription = "حالة الصلاحيات",
                        tint = if (isAllReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = if (isAllReady) "جميع الصلاحيات والخدمات جاهزة" else "الصلاحيات المطلوبة للتشغيل",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isAllReady) {
                    Icon(
                        imageVector = if (expandedWhenReady) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "تفاصيل الصلاحيات",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionRowItem(
                        title = "قراءة الإشعارات (Notification Listener)",
                        subtitle = "لالتقاط رسائل WhatsApp والتطبيقات المدعومة",
                        icon = Icons.Filled.NotificationsActive,
                        isGranted = notifListenerGranted,
                        buttonTag = "grant_notif_listener_btn",
                        onAction = onGrantNotifListener
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    PermissionRowItem(
                        title = "خدمة إمكانية الوصول (Accessibility)",
                        subtitle = "للكتابة والضغط على زر الإرسال تلقائياً",
                        icon = Icons.Filled.AccessibilityNew,
                        isGranted = accessibilityGranted,
                        buttonTag = "grant_accessibility_btn",
                        onAction = onGrantAccessibility
                    )

                    if (!postNotifGranted) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        PermissionRowItem(
                            title = "صلاحية الإشعارات (Android 13+)",
                            subtitle = "لعرض مؤشر استمرار الخدمة في الخلفية",
                            icon = Icons.Outlined.Notifications,
                            isGranted = false,
                            buttonTag = "grant_post_notif_btn",
                            onAction = onRequestPostNotif
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRowItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isGranted: Boolean,
    buttonTag: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isGranted) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50.dp)
            ) {
                Text(
                    text = "مفعّلة",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else {
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(buttonTag)
            ) {
                Text("تفعيل", maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReplyLogCard(log: ReplyLogEntity) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (log.isGroup) "${log.senderName} (مجموعة)" else log.senderName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${timeFormat.format(Date(log.timestamp))} • ${log.deliveryMethod}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                text = "الوارد: ${log.incomingMessage}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "الرد: ${log.sentReply}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyLogsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
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
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = "لا توجد ردود بعد",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "لم يتم إرسال أي ردود تلقائية بعد",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "فعّل الصلاحيات وأضف قواعدك، ثم شغّل المفتاح الرئيسي أعلاه للبدء.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatWindow(sh: Int, sm: Int, eh: Int, em: Int): String {
    return String.format(Locale.US, "%02d:%02d - %02d:%02d", sh, sm, eh, em)
}
