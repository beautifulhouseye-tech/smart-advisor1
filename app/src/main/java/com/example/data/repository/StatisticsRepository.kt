package com.example.data.repository

import com.example.data.local.dao.ReplyLogDao
import com.example.data.local.dao.ReplyRuleDao
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.engine.ReplySourceCategory
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class StatsPeriod(val labelAr: String, val days: Int?) {
    TODAY("اليوم", 1),
    WEEK("الأسبوع", 7),
    MONTH("الشهر", 30),
    ALL("الكل", null)
}

data class DailyReplyStat(
    val dayLabel: String,
    val directCount: Int,
    val accessibilityCount: Int,
    val webhookCount: Int,
    val failedCount: Int
) {
    val totalCount: Int
        get() = directCount + accessibilityCount + webhookCount + failedCount
}

data class TimelineCategoryStat(
    val bucketLabel: String,
    val keywordRulesCount: Int,
    val aiAndAdvisorCount: Int,
    val otherAutoCount: Int
) {
    val totalCount: Int
        get() = keywordRulesCount + aiAndAdvisorCount + otherAutoCount
}

data class KeywordRulePerformance(
    val keyword: String,
    val displayLabel: String,
    val replyCount: Int,
    val sharePercent: Float,
    val isWildcard: Boolean = false
)

data class SourceCategoryStat(
    val categoryKey: String,
    val labelAr: String,
    val count: Int,
    val sharePercent: Float
)

data class StatisticsSummary(
    val totalMessages: Int = 0,
    val totalReplies: Int = 0,
    val successRatePercent: Float = 0f,
    val topRules: List<ReplyRuleEntity> = emptyList(),
    val last7Days: List<DailyReplyStat> = emptyList(),
    val timelineByCategory: List<TimelineCategoryStat> = emptyList(),
    val keywordRuleBreakdown: List<KeywordRulePerformance> = emptyList(),
    val sourceDistribution: List<SourceCategoryStat> = emptyList()
)

@Singleton
class StatisticsRepository @Inject constructor(
    private val replyLogDao: ReplyLogDao,
    private val replyRuleDao: ReplyRuleDao
) {
    fun observeStatistics(period: StatsPeriod, nowMs: Long = System.currentTimeMillis()): Flow<StatisticsSummary> {
        return combine(
            replyLogDao.observeAllLogs(),
            replyRuleDao.observeAllRules()
        ) { logs, rules ->
            buildSummary(logs, rules, period, nowMs)
        }
    }

    fun buildSummary(
        allLogs: List<ReplyLogEntity>,
        allRules: List<ReplyRuleEntity>,
        period: StatsPeriod,
        nowMs: Long = System.currentTimeMillis()
    ): StatisticsSummary {
        val cutoffMs = periodStartMs(period, nowMs)
        val filteredLogs = if (cutoffMs == null) {
            allLogs
        } else {
            allLogs.filter { it.timestamp >= cutoffMs }
        }

        val attemptedLogs = filteredLogs.filterNot { it.deliveryMethod.startsWith("SKIPPED_") }
        val totalMessages = attemptedLogs.size
        val successfulLogs = attemptedLogs.filter { log ->
            !isNonSuccessMethod(log.deliveryMethod)
        }
        val successfulReplies = successfulLogs.size
        val successRate = if (totalMessages == 0) 0f else (successfulReplies * 100f) / totalMessages
        val topRules = allRules.sortedByDescending { it.hitCount }.take(5)
        val last7Days = computeLast7DaysBars(allLogs, nowMs)
        val timelineByCategory = computeTimelineByCategory(
            logs = if (period == StatsPeriod.ALL) allLogs else filteredLogs,
            period = period,
            nowMs = nowMs
        )
        val keywordRuleBreakdown = computeKeywordRuleBreakdown(successfulLogs)
        val sourceDistribution = computeSourceDistribution(successfulLogs)

        return StatisticsSummary(
            totalMessages = totalMessages,
            totalReplies = successfulReplies,
            successRatePercent = successRate,
            topRules = topRules,
            last7Days = last7Days,
            timelineByCategory = timelineByCategory,
            keywordRuleBreakdown = keywordRuleBreakdown,
            sourceDistribution = sourceDistribution
        )
    }

    private fun periodStartMs(period: StatsPeriod, nowMs: Long): Long? {
        val days = period.days ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (days > 1) {
                add(Calendar.DAY_OF_YEAR, -(days - 1))
            }
        }
        return cal.timeInMillis
    }

    private fun computeLast7DaysBars(
        allLogs: List<ReplyLogEntity>,
        nowMs: Long
    ): List<DailyReplyStat> {
        val labelFormatter = SimpleDateFormat("EEE", Locale("ar"))
        val result = mutableListOf<DailyReplyStat>()

        for (daysAgo in 6 downTo 0) {
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -daysAgo)
            }.timeInMillis
            val dayEnd = dayStart + 86_400_000L

            val dayLogs = allLogs.filter { it.timestamp in dayStart until dayEnd }
            var direct = 0
            var accessibility = 0
            var webhook = 0
            var failed = 0

            for (log in dayLogs) {
                when {
                    log.deliveryMethod.startsWith("SKIPPED_") -> Unit
                    isNonSuccessMethod(log.deliveryMethod) -> failed++
                    log.deliveryMethod.contains("Webhook", ignoreCase = true) ||
                        log.deliveryMethod == MessageQueueWorker.STATUS_SUCCESS -> webhook++
                    log.deliveryMethod.contains("Accessibility", ignoreCase = true) -> accessibility++
                    else -> direct++
                }
            }

            result += DailyReplyStat(
                dayLabel = labelFormatter.format(Date(dayStart)),
                directCount = direct,
                accessibilityCount = accessibility,
                webhookCount = webhook,
                failedCount = failed
            )
        }
        return result
    }

    private fun computeTimelineByCategory(
        logs: List<ReplyLogEntity>,
        period: StatsPeriod,
        nowMs: Long
    ): List<TimelineCategoryStat> {
        val validLogs = logs.filter { log ->
            !log.deliveryMethod.startsWith("SKIPPED_") && !isNonSuccessMethod(log.deliveryMethod)
        }

        return when (period) {
            StatsPeriod.TODAY -> {
                val dayStart = periodStartMs(StatsPeriod.TODAY, nowMs) ?: nowMs
                val hourSpanMs = 6L * 3_600_000L
                val labels = listOf("00–06", "06–12", "12–18", "18–24")
                labels.mapIndexed { idx, label ->
                    val start = dayStart + idx * hourSpanMs
                    val end = start + hourSpanMs
                    summarizeBucket(label, validLogs.filter { it.timestamp in start until end })
                }
            }
            StatsPeriod.WEEK -> {
                val labelFormatter = SimpleDateFormat("EEE", Locale("ar"))
                (6 downTo 0).map { daysAgo ->
                    val dayStart = Calendar.getInstance().apply {
                        timeInMillis = nowMs
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        add(Calendar.DAY_OF_YEAR, -daysAgo)
                    }.timeInMillis
                    val dayEnd = dayStart + 86_400_000L
                    summarizeBucket(
                        label = labelFormatter.format(Date(dayStart)),
                        bucketLogs = validLogs.filter { it.timestamp in dayStart until dayEnd }
                    )
                }
            }
            StatsPeriod.MONTH -> {
                val monthStart = periodStartMs(StatsPeriod.MONTH, nowMs) ?: nowMs
                val bucketCount = 4
                val totalSpan = ((nowMs - monthStart).coerceAtLeast(86_400_000L)) + 1L
                val bucketSpan = (totalSpan / bucketCount).coerceAtLeast(1L)
                (0 until bucketCount).map { idx ->
                    val start = monthStart + idx * bucketSpan
                    val end = if (idx == bucketCount - 1) Long.MAX_VALUE else monthStart + (idx + 1) * bucketSpan
                    summarizeBucket(
                        label = "أسبوع ${idx + 1}",
                        bucketLogs = validLogs.filter { it.timestamp in start until end }
                    )
                }
            }
            StatsPeriod.ALL -> {
                val monthFormatter = SimpleDateFormat("MMM", Locale("ar"))
                (5 downTo 0).map { monthsAgo ->
                    val startCal = Calendar.getInstance().apply {
                        timeInMillis = nowMs
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        add(Calendar.MONTH, -monthsAgo)
                    }
                    val startMs = startCal.timeInMillis
                    val endCal = (startCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
                    val endMs = endCal.timeInMillis
                    summarizeBucket(
                        label = monthFormatter.format(Date(startMs)),
                        bucketLogs = validLogs.filter { it.timestamp in startMs until endMs }
                    )
                }
            }
        }
    }

    private fun summarizeBucket(label: String, bucketLogs: List<ReplyLogEntity>): TimelineCategoryStat {
        var keywordRules = 0
        var aiAndAdvisor = 0
        var otherAuto = 0

        for (log in bucketLogs) {
            when (classifyTimelineBucket(log)) {
                TimelineBucket.KEYWORD_RULE -> keywordRules++
                TimelineBucket.AI_OR_ADVISOR -> aiAndAdvisor++
                TimelineBucket.OTHER_AUTO -> otherAuto++
            }
        }
        return TimelineCategoryStat(
            bucketLabel = label,
            keywordRulesCount = keywordRules,
            aiAndAdvisorCount = aiAndAdvisor,
            otherAutoCount = otherAuto
        )
    }

    private fun computeKeywordRuleBreakdown(successfulLogs: List<ReplyLogEntity>): List<KeywordRulePerformance> {
        val ruleLogs = successfulLogs.filter { log ->
            log.matchedRuleKeyword.isNotBlank() ||
                log.sourceCategory == ReplySourceCategory.LOCAL_RULE.name ||
                log.sourceCategory == ReplySourceCategory.WILDCARD_RULE.name
        }
        if (ruleLogs.isEmpty()) return emptyList()

        val totalRuleReplies = ruleLogs.size.toFloat()
        return ruleLogs
            .groupBy { log ->
                log.matchedRuleKeyword.trim().ifBlank { "قاعدة غير مسماة" }
            }
            .map { (keyword, items) ->
                val isWildcard = keyword == "*"
                KeywordRulePerformance(
                    keyword = keyword,
                    displayLabel = if (isWildcard) "الرد الافتراضي (*)" else keyword,
                    replyCount = items.size,
                    sharePercent = (items.size * 100f) / totalRuleReplies,
                    isWildcard = isWildcard
                )
            }
            .sortedByDescending { it.replyCount }
            .take(8)
    }

    private fun computeSourceDistribution(successfulLogs: List<ReplyLogEntity>): List<SourceCategoryStat> {
        if (successfulLogs.isEmpty()) return emptyList()
        val total = successfulLogs.size.toFloat()

        return successfulLogs
            .groupBy { normalizeSourceCategoryKey(it) }
            .map { (key, items) ->
                SourceCategoryStat(
                    categoryKey = key,
                    labelAr = sourceCategoryLabelAr(key),
                    count = items.size,
                    sharePercent = (items.size * 100f) / total
                )
            }
            .sortedByDescending { it.count }
    }

    private enum class TimelineBucket {
        KEYWORD_RULE,
        AI_OR_ADVISOR,
        OTHER_AUTO
    }

    private fun classifyTimelineBucket(log: ReplyLogEntity): TimelineBucket {
        val key = normalizeSourceCategoryKey(log)
        return when (key) {
            ReplySourceCategory.LOCAL_RULE.name,
            ReplySourceCategory.WILDCARD_RULE.name -> TimelineBucket.KEYWORD_RULE
            ReplySourceCategory.AI_ENGINE.name,
            ReplySourceCategory.SMART_ADVISOR.name -> TimelineBucket.AI_OR_ADVISOR
            else -> TimelineBucket.OTHER_AUTO
        }
    }

    private fun normalizeSourceCategoryKey(log: ReplyLogEntity): String {
        val raw = log.sourceCategory.trim()
        if (raw.isNotEmpty()) return raw
        if (log.matchedRuleKeyword.isNotBlank()) {
            return if (log.matchedRuleKeyword.trim() == "*") {
                ReplySourceCategory.WILDCARD_RULE.name
            } else {
                ReplySourceCategory.LOCAL_RULE.name
            }
        }
        return if (log.deliveryMethod.contains("Webhook", ignoreCase = true)) {
            ReplySourceCategory.WEBHOOK.name
        } else {
            ReplySourceCategory.LOCAL_RULE.name
        }
    }

    private fun sourceCategoryLabelAr(key: String): String {
        return ReplySourceCategory.entries.firstOrNull { it.name == key }?.labelAr ?: key
    }

    private fun isNonSuccessMethod(method: String): Boolean {
        return method == MessageQueueWorker.STATUS_FAILED ||
            method == MessageQueueWorker.STATUS_FAILED_CONFIRMED ||
            method == MessageQueueWorker.STATUS_PERMANENTLY_FAILED ||
            method == MessageQueueWorker.STATUS_DELIVERY_UNKNOWN ||
            method == MessageQueueWorker.STATUS_IN_FLIGHT
    }
}
