package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.DailyReplyStat
import com.example.data.repository.KeywordRulePerformance
import com.example.data.repository.SourceCategoryStat
import com.example.data.repository.StatsPeriod
import com.example.data.repository.TimelineCategoryStat
import com.example.ui.viewmodel.StatisticsViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("الإحصائيات والتقارير") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("stats_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatsPeriod.entries.forEach { period ->
                            FilterChip(
                                selected = selectedPeriod == period,
                                onClick = { viewModel.selectPeriod(period) },
                                label = { Text(period.labelAr) },
                                modifier = Modifier.testTag("stats_period_${period.name.lowercase()}")
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatMetricBox(
                            title = "إجمالي الرسائل",
                            value = "${summary.totalMessages}",
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricBox(
                            title = "إجمالي الردود",
                            value = "${summary.totalReplies}",
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricBox(
                            title = "نسبة النجاح",
                            value = "${String.format(Locale.US, "%.0f", summary.successRatePercent)}%",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Performance Over Time Categorized by Keyword Rules & Sources
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                    imageVector = Icons.Filled.BarChart,
                                    contentDescription = "الأداء عبر الزمن",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = "أداء الردود عبر الزمن (${selectedPeriod.labelAr})",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "توزيع الردود المرسلة حسب القواعد والذكاء الاصطناعي",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            TimelineCategoryLegend()

                            val hasTimelineData = summary.timelineByCategory.any { it.totalCount > 0 }
                            if (!hasTimelineData) {
                                EmptyChartPlaceholder(
                                    message = "لا توجد ردود مسجلة ضمن فترة «${selectedPeriod.labelAr}» بعد.",
                                    modifier = Modifier.testTag("stats_category_timeline_chart")
                                )
                            } else {
                                CategoryTimelineBarChart(
                                    buckets = summary.timelineByCategory,
                                    keywordRulesColor = MaterialTheme.colorScheme.primary,
                                    aiAdvisorColor = MaterialTheme.colorScheme.secondary,
                                    otherAutoColor = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(175.dp)
                                        .testTag("stats_category_timeline_chart")
                                )
                            }
                        }
                    }
                }

                // Keyword Rules Breakdown Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stats_keyword_rules_breakdown"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = "أداء قواعد الكلمات المفتاحية",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "الردود المصنفة حسب قواعد الكلمات المفتاحية",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            if (summary.keywordRuleBreakdown.isEmpty()) {
                                Text(
                                    text = "لم تتطابق ردود مع قواعد كلمات مفتاحية في فترة «${selectedPeriod.labelAr}».",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                summary.keywordRuleBreakdown.forEachIndexed { idx, item ->
                                    if (idx > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                                        )
                                    }
                                    KeywordRulePerformanceRow(item = item)
                                }
                            }
                        }
                    }
                }

                // Source Categories Distribution Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stats_sources_distribution"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                    imageVector = Icons.Filled.Category,
                                    contentDescription = "توزيع مصادر الرد",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "توزيع الردود حسب المصدر (${selectedPeriod.labelAr})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            if (summary.sourceDistribution.isEmpty()) {
                                Text(
                                    text = "لا توجد بيانات لمصادر الرد في هذه الفترة بعد.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                summary.sourceDistribution.forEachIndexed { index, sourceStat ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                                        )
                                    }
                                    SourceDistributionRow(stat = sourceStat)
                                }
                            }
                        }
                    }
                }

                // Delivery Channels (7-day stacked bar chart)
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "قنوات التسليم خلال آخر 7 أيام",
                                style = MaterialTheme.typography.titleMedium
                            )

                            DeliveryLegend()

                            SevenDaysBarChart(
                                bars = summary.last7Days,
                                directColor = MaterialTheme.colorScheme.primary,
                                accessibilityColor = MaterialTheme.colorScheme.secondary,
                                webhookColor = MaterialTheme.colorScheme.tertiary,
                                failedColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .testTag("stats_7day_barchart")
                            )
                        }
                    }
                }

                // Cumulative Top 5 Rules
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "أكثر 5 قواعد استخداماً (إجمالي)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (summary.topRules.isEmpty()) {
                                Text(
                                    text = "لا توجد قواعد مفعّلة أو بيانات بعد.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                summary.topRules.forEachIndexed { index, rule ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${index + 1}. ${rule.keyword}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${rule.hitCount} مرة",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun KeywordRulePerformanceRow(item: KeywordRulePerformance) {
    val progress = (item.sharePercent / 100f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = if (item.isWildcard) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = item.displayLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (item.isWildcard) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = "${item.replyCount} رد (${String.format(Locale.US, "%.0f", item.sharePercent)}%)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(50.dp)),
            color = if (item.isWildcard) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun SourceDistributionRow(stat: SourceCategoryStat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stat.labelAr,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Surface(
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
            shape = RoundedCornerShape(50.dp)
        ) {
            Text(
                text = "${stat.count} (${String.format(Locale.US, "%.0f", stat.sharePercent)}%)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineCategoryLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot("قواعد الكلمات المفتاحية", MaterialTheme.colorScheme.primary)
        LegendDot("الذكاء الاصطناعي و Smart Advisor", MaterialTheme.colorScheme.secondary)
        LegendDot("القوائم والقيادة و Webhook", MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun EmptyChartPlaceholder(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 22.dp)
        )
    }
}

@Composable
private fun CategoryTimelineBarChart(
    buckets: List<TimelineCategoryStat>,
    keywordRulesColor: Color,
    aiAdvisorColor: Color,
    otherAutoColor: Color,
    modifier: Modifier = Modifier
) {
    val maxTotal = (buckets.maxOfOrNull { it.totalCount } ?: 1).coerceAtLeast(1)
    val guideColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (buckets.isEmpty()) return@Canvas
            val chartHeight = size.height
            // Subtle horizontal grid lines
            listOf(0.25f, 0.5f, 0.75f).forEach { ratio ->
                val y = chartHeight * ratio
                drawLine(
                    color = guideColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }

            val slotWidth = size.width / buckets.size
            val barWidth = (slotWidth * 0.46f).coerceIn(14f, 44f)

            buckets.forEachIndexed { idx, bucket ->
                val left = idx * slotWidth + (slotWidth - barWidth) / 2f
                var currentBottom = chartHeight

                val segments = listOf(
                    bucket.keywordRulesCount to keywordRulesColor,
                    bucket.aiAndAdvisorCount to aiAdvisorColor,
                    bucket.otherAutoCount to otherAutoColor
                )

                segments.forEach { (count, segColor) ->
                    if (count > 0) {
                        val segHeight = (count.toFloat() / maxTotal.toFloat()) * (chartHeight * 0.9f)
                        drawRoundRect(
                            color = segColor,
                            topLeft = Offset(left, currentBottom - segHeight),
                            size = Size(barWidth, segHeight),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                        currentBottom -= segHeight
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            buckets.forEach { bucket ->
                Text(
                    text = "${bucket.bucketLabel}\n(${bucket.totalCount})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatMetricBox(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeliveryLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot("DirectReply", MaterialTheme.colorScheme.primary)
        LegendDot("Accessibility", MaterialTheme.colorScheme.secondary)
        LegendDot("Webhook / SA", MaterialTheme.colorScheme.tertiary)
        LegendDot("فاشلة", MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = color, shape = CircleShape)
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SevenDaysBarChart(
    bars: List<DailyReplyStat>,
    directColor: Color,
    accessibilityColor: Color,
    webhookColor: Color,
    failedColor: Color,
    modifier: Modifier = Modifier
) {
    val maxTotal = (bars.maxOfOrNull { it.totalCount } ?: 1).coerceAtLeast(1)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (bars.isEmpty()) return@Canvas
            val slotWidth = size.width / bars.size
            val barWidth = (slotWidth * 0.48f).coerceAtLeast(12f)
            val chartHeight = size.height

            bars.forEachIndexed { idx, stat ->
                val left = idx * slotWidth + (slotWidth - barWidth) / 2f
                var currentBottom = chartHeight

                val segments = listOf(
                    stat.directCount to directColor,
                    stat.accessibilityCount to accessibilityColor,
                    stat.webhookCount to webhookColor,
                    stat.failedCount to failedColor
                )

                segments.forEach { (count, segColor) ->
                    if (count > 0) {
                        val segHeight = (count.toFloat() / maxTotal.toFloat()) * (chartHeight * 0.9f)
                        drawRect(
                            color = segColor,
                            topLeft = Offset(left, currentBottom - segHeight),
                            size = Size(barWidth, segHeight)
                        )
                        currentBottom -= segHeight
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            bars.forEach { stat ->
                Text(
                    text = "${stat.dayLabel}\n(${stat.totalCount})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
