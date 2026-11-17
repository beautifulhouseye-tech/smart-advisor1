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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleDrivingMode: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onSimulateReply: (String) -> Pair<String, String?>,
    onClearLogs: () -> Unit,
    onNavigateToRules: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToServer: () -> Unit = {},
    onNavigateToSaSettings: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToAISettings: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToMenus: () -> Unit = {},
    onNavigateToFollowUps: () -> Unit = {},
    onNavigateToTestReply: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showSimulatorDialog by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefreshPermissions()
    }

    val isSystemReady = uiState.permissions.isNotificationListenerEnabled &&
        uiState.permissions.isAccessibilityEnabled

    val enabledRules = remember(uiState.rules) {
        uiState.rules
            .filter { it.isEnabled }
            .sortedWith(compareByDescending<com.example.data.local.entity.ReplyRuleEntity> { it.hitCount }.thenByDescending { it.priority })
    }
    val activeRulesCount = enabledRules.size
    val totalRulesCount = uiState.rules.size

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            // 2. Quick Stats Row
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

            // 3. Driving Mode Status Card
            item {
                DrivingModeQuickCard(
                    isDrivingModeEnabled = uiState.settings.isDrivingModeEnabled,
                    drivingReplyText = uiState.settings.drivingModeReplyText,
                    autoDetectEnabled = uiState.settings.isAutoDetectDrivingEnabled,
                    onToggleDrivingMode = onToggleDrivingMode,
                    onConfigureDriving = onNavigateToSettings
                )
            }

            // 3b. Enabled Auto-Reply Rules Summary Card
            item {
                EnabledRulesSummaryCard(
                    enabledRules = enabledRules,
                    totalRulesCount = totalRulesCount,
                    onManageRules = onNavigateToRules
                )
            }

            // 4. Permissions & Services Readiness Card
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

            // 5. Quick Actions Bar (Live Tester)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (onNavigateToTestReply != null) {
                                onNavigateToTestReply()
                            } else {
                                showSimulatorDialog = true
                            }
                        },
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("اختبار الرد الموحد")
                    }

                    OutlinedButton(
                        onClick = onNavigateToRules,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("quick_add_rule_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = "إدارة القواعد",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("إدارة القواعد")
                    }
                }
            }

            // 5b. Server Webhook Feature Card
            item {
                FeatureCard(
                    icon = Icons.Filled.Cloud,
                    label = "الخادم",
                    subtitle = "ربط Webhook مخصص للرد التلقائي الذكي",
                    onClick = onNavigateToServer,
                    modifier = Modifier.testTag("server_feature_card")
                )
            }

            // 5c. Smart Advisor Feature Card
            item {
                FeatureCard(
                    icon = Icons.Filled.SmartToy,
                    label = "Smart Advisor",
                    subtitle = "ربط مع WooCommerce Smart Advisor للردود الذكية",
                    onClick = onNavigateToSaSettings,
                    modifier = Modifier.testTag("sa_settings_feature_card")
                )
            }

            // 5d. AI Engine Settings Feature Card
            item {
                FeatureCard(
                    icon = Icons.Filled.Psychology,
                    label = "الذكاء الاصطناعي (AI)",
                    subtitle = "إعداد OpenAI / Gemini والردود الذكية التلقائية",
                    onClick = onNavigateToAISettings,
                    modifier = Modifier.testTag("ai_settings_feature_card")
                )
            }

            // 5e. Logs History Feature Card
            item {
                FeatureCard(
                    icon = Icons.Filled.History,
                    label = "سجل الرسائل",
                    subtitle = "عرض وبحث جميع الرسائل وحالات التسليم وإخفاء الأسرار",
                    onClick = onNavigateToLogs,
                    modifier = Modifier.testTag("logs_feature_card")
                )
            }

            // 5f. Statistics Feature Card
            item {
                FeatureCard(
                    icon = Icons.Filled.Analytics,
                    label = "الإحصائيات",
                    subtitle = "معدل النجاح، الرسم البياني لـ 7 أيام، وأكثر القواعد",
                    onClick = onNavigateToStatistics,
                    modifier = Modifier.testTag("statistics_feature_card")
                )
            }

            // 5g. Interactive Menus Feature Card
            item {
                FeatureCard(
                    icon = Icons.Filled.FormatListNumbered,
                    label = "القوائم التفاعلية",
                    subtitle = "قوائم اختيارات مرقمة للرد التلقائي على العملاء",
                    onClick = onNavigateToMenus,
                    modifier = Modifier.testTag("menu_reply_feature_card")
                )
            }

            // 5h. Follow-Up Messages Feature Card
            item {
                FeatureCard(
                    icon = Icons.AutoMirrored.Filled.ScheduleSend,
                    label = "رسائل المتابعة",
                    subtitle = "جدولة رسائل متابعة تلقائية بعد الردود",
                    onClick = onNavigateToFollowUps,
                    modifier = Modifier.testTag("followup_feature_card")
                )
            }

            // 6. Recent Auto-Replies Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "سجل الردود الأخيرة",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (uiState.recentLogs.isNotEmpty()) {
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.testTag("clear_logs_button")
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

            if (uiState.recentLogs.isEmpty()) {
                item {
                    EmptyLogsCard()
                }
            } else {
                items(uiState.recentLogs, key = { it.id }) { log ->
                    ReplyLogCard(log = log)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showSimulatorDialog) {
        RuleSimulatorDialog(
            onDismiss = { showSimulatorDialog = false },
            onSimulate = onSimulateReply
        )
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
                .height(196.dp)
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
                                color = Color.White
                            )
                        }
                    }

                    Text(
                        text = "AutoMate AI",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFA7F3D0)
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
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isScheduleEnabled) {
                                "الجدول الزمني نشط: $scheduleText"
                            } else {
                                "مجاني بالكامل • بدون إعلانات • خصوصية محلية"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFCBD5E1)
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
        modifier = modifier,
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
                color = MaterialTheme.colorScheme.onSurface
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.DirectionsCar,
                                contentDescription = "وضع القيادة",
                                tint = accentTint
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "وضع القيادة الذكي",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = if (isDrivingModeEnabled) {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.20f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(50.dp)
                            ) {
                                Text(
                                    text = if (isDrivingModeEnabled) "نشط الآن" else "متوقف",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDrivingModeEnabled) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                color = if (autoDetectEnabled) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                },
                                shape = RoundedCornerShape(50.dp)
                            ) {
                                Text(
                                    text = if (autoDetectEnabled) "كشف تلقائي مفعّل" else "تحكم يدوي",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (autoDetectEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isDrivingModeEnabled,
                    onCheckedChange = onToggleDrivingMode,
                    modifier = Modifier.testTag("driving_mode_switch")
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "الرد أثناء القيادة: ${drivingReplyText.ifBlank { "غير محدد" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
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
                }
            }
        }
    }
}

@Composable
private fun EnabledRulesSummaryCard(
    enabledRules: List<com.example.data.local.entity.ReplyRuleEntity>,
    totalRulesCount: Int,
    onManageRules: () -> Unit
) {
    val enabledCount = enabledRules.size
    val totalHits = remember(enabledRules) { enabledRules.sumOf { it.hitCount } }
    val ratio = if (totalRulesCount > 0) enabledCount.toFloat() / totalRulesCount.toFloat() else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("enabled_rules_summary_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = "ملخص القواعد المفعلة",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "ملخص القواعد المفعّلة",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$enabledCount من أصل $totalRulesCount قاعدة نشطة • $totalHits استجابة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    onClick = onManageRules,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("enabled_rules_manage_button")
                ) {
                    Text("إدارة الكل")
                }
            }

            if (totalRulesCount > 0) {
                LinearProgressIndicator(
                    progress = { ratio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (enabledRules.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "لا توجد قواعد رد مفعّلة حالياً. أضف قاعدة كلمات مفتاحية أو فعّل القواعد الحالية.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                enabledRules.take(4).forEachIndexed { index, rule ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (rule.keyword.trim() == "*") "افتراضي (*)" else rule.keyword,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = rule.matchType.labelAr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = rule.replyText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${rule.hitCount} رد",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isAllReady) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                    contentDescription = "حالة الصلاحيات",
                    tint = if (isAllReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = if (isAllReady) "جميع الخدمات جاهزة للعمل" else "الصلاحيات المطلوبة للتشغيل",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

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

            AnimatedVisibility(visible = !postNotifGranted) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Spacer(modifier = Modifier.height(10.dp))
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
                    style = MaterialTheme.typography.bodyMedium,
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
                Text("تفعيل")
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
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${timeFormat.format(Date(log.timestamp))} • ${log.deliveryMethod}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "الرد: ${log.sentReply}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
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

@Composable
private fun RuleSimulatorDialog(
    onDismiss: () -> Unit,
    onSimulate: (String) -> Pair<String, String?>
) {
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<String, String?>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختبار الرد الذكي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "اكتب رسالة تجريبية لمعرفة القاعدة المطابقة والرد الذي سيرسله AutoMate AI:",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("نص الرسالة الواردة") },
                    placeholder = { Text("مثال: السلام عليكم، بكم السعر؟") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("simulator_input")
                )
                result?.let { (source, reply) ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = source,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = reply ?: "لم تطابق أي قاعدة مفعّلة هذه الرسالة.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { result = onSimulate(input) },
                enabled = input.isNotBlank(),
                modifier = Modifier.testTag("run_simulation_button")
            ) {
                Text("فحص المطابقة")
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
private fun FeatureCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatWindow(sh: Int, sm: Int, eh: Int, em: Int): String {
    return String.format(Locale.US, "%02d:%02d - %02d:%02d", sh, sm, eh, em)
}
