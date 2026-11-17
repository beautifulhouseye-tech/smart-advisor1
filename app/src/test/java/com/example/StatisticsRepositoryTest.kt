package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.repository.StatisticsRepository
import com.example.data.repository.StatsPeriod
import com.example.engine.ReplySourceCategory
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatisticsRepositoryTest {

    private lateinit var db: AutoMateDatabase
    private lateinit var repository: StatisticsRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AutoMateDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = StatisticsRepository(
            replyLogDao = db.replyLogDao(),
            replyRuleDao = db.replyRuleDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeStatistics computes totals success rate top 5 rules and 7 day bars`() = runTest {
        val now = System.currentTimeMillis()
        val oneDayMs = 86_400_000L

        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "A",
                packageName = "com.whatsapp",
                incomingMessage = "1",
                sentReply = "r1",
                isGroup = false,
                deliveryMethod = "DirectReply",
                timestamp = now - 1000L
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "B",
                packageName = "com.whatsapp",
                incomingMessage = "2",
                sentReply = "r2",
                isGroup = false,
                deliveryMethod = "Accessibility",
                timestamp = now - 2000L
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "C",
                packageName = "com.whatsapp",
                incomingMessage = "3",
                sentReply = "r3",
                isGroup = false,
                deliveryMethod = "Webhook-DirectReply",
                timestamp = now - oneDayMs
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "D",
                packageName = "com.whatsapp",
                incomingMessage = "4",
                sentReply = "err",
                isGroup = false,
                deliveryMethod = MessageQueueWorker.STATUS_FAILED,
                timestamp = now - 3000L
            )
        )

        db.replyRuleDao().insertRules(
            (1..6).map { i ->
                ReplyRuleEntity(
                    keyword = "kw-$i",
                    replyText = "reply-$i",
                    hitCount = i * 10
                )
            }
        )

        val summary = repository.observeStatistics(StatsPeriod.WEEK, nowMs = now).first()
        assertEquals(4, summary.totalMessages)
        assertEquals(3, summary.totalReplies)
        assertEquals(75f, summary.successRatePercent, 0.1f)
        assertEquals(5, summary.topRules.size)
        assertEquals("kw-6", summary.topRules.first().keyword)
        assertEquals(7, summary.last7Days.size)
        assertEquals(4, summary.last7Days.sumOf { it.totalCount })
    }

    @Test
    fun `observeStatistics categorizes replies by keyword rules and timeline period`() = runTest {
        val now = System.currentTimeMillis()

        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "Ali",
                packageName = "com.whatsapp",
                incomingMessage = "كم السعر",
                sentReply = "السعر 50",
                isGroup = false,
                deliveryMethod = "DirectReply",
                timestamp = now - 5_000L,
                sourceCategory = ReplySourceCategory.LOCAL_RULE.name,
                matchedRuleKeyword = "السعر"
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "Omar",
                packageName = "com.whatsapp",
                incomingMessage = "بكم المنتج",
                sentReply = "السعر 50",
                isGroup = false,
                deliveryMethod = "DirectReply",
                timestamp = now - 4_000L,
                sourceCategory = ReplySourceCategory.LOCAL_RULE.name,
                matchedRuleKeyword = "السعر"
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "Sara",
                packageName = "com.whatsapp",
                incomingMessage = "أين موقعكم",
                sentReply = "الرياض",
                isGroup = false,
                deliveryMethod = "DirectReply",
                timestamp = now - 3_000L,
                sourceCategory = ReplySourceCategory.LOCAL_RULE.name,
                matchedRuleKeyword = "الموقع"
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "Noor",
                packageName = "com.whatsapp",
                incomingMessage = "اقترح لي عطر",
                sentReply = "أنصحك بعود",
                isGroup = false,
                deliveryMethod = "DirectReply",
                timestamp = now - 2_000L,
                sourceCategory = ReplySourceCategory.SMART_ADVISOR.name
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "FailedUser",
                packageName = "com.whatsapp",
                incomingMessage = "السعر",
                sentReply = "failed",
                isGroup = false,
                deliveryMethod = MessageQueueWorker.STATUS_FAILED,
                timestamp = now - 1_000L,
                sourceCategory = ReplySourceCategory.LOCAL_RULE.name,
                matchedRuleKeyword = "السعر"
            )
        )

        val todaySummary = repository.observeStatistics(StatsPeriod.TODAY, nowMs = now).first()
        assertEquals(4, todaySummary.timelineByCategory.size)
        assertEquals(3, todaySummary.timelineByCategory.sumOf { it.keywordRulesCount })
        assertEquals(1, todaySummary.timelineByCategory.sumOf { it.aiAndAdvisorCount })

        assertEquals(2, todaySummary.keywordRuleBreakdown.size)
        assertEquals("السعر", todaySummary.keywordRuleBreakdown.first().keyword)
        assertEquals(2, todaySummary.keywordRuleBreakdown.first().replyCount)
        assertEquals("الموقع", todaySummary.keywordRuleBreakdown[1].keyword)
        assertEquals(1, todaySummary.keywordRuleBreakdown[1].replyCount)

        assertTrue(todaySummary.sourceDistribution.any { it.categoryKey == ReplySourceCategory.LOCAL_RULE.name && it.count == 3 })
        assertTrue(todaySummary.sourceDistribution.any { it.categoryKey == ReplySourceCategory.SMART_ADVISOR.name && it.count == 1 })

        val monthSummary = repository.observeStatistics(StatsPeriod.MONTH, nowMs = now).first()
        assertEquals(4, monthSummary.timelineByCategory.size)
        assertEquals(4, monthSummary.timelineByCategory.sumOf { it.totalCount })
    }
}
