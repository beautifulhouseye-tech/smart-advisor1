package com.example

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.IdempotencyGuard
import com.example.engine.MenuReplyManager
import com.example.engine.NotificationCoordinator
import com.example.engine.NotificationPayload
import com.example.engine.PendingAccessibilityReply
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.service.AutoReplyAccessibilityService
import com.example.worker.FollowUpScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendHandoffRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AutoMateDatabase
    private lateinit var guard: IdempotencyGuard

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        guard = IdempotencyGuard(db.dedupKeyDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildDummyReplyAction(): Notification.Action {
        val remoteInput = android.app.RemoteInput.Builder("key_text_reply").setLabel("Reply").build()
        val pi = PendingIntent.getBroadcast(
            context,
            301,
            Intent("com.example.ACTION_REPLY_HANDOFF"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(0, "Reply", pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun buildDummyContentIntent(): PendingIntent {
        return PendingIntent.getActivity(
            context,
            302,
            Intent("com.example.ACTION_OPEN_HANDOFF"),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildChatTree(
        packageName: String,
        chatTitle: String,
        setTextCount: AtomicInteger,
        clickCount: AtomicInteger
    ): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = packageName
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val titleNode = AccessibilityNodeInfo.obtain()
        titleNode.packageName = packageName
        titleNode.className = "android.widget.TextView"
        titleNode.viewIdResourceName = "$packageName:id/conversation_contact_name"
        titleNode.text = chatTitle
        titleNode.isVisibleToUser = true
        titleNode.isEditable = false
        Shadows.shadowOf(root).addChild(titleNode)

        val editNode = AccessibilityNodeInfo.obtain()
        editNode.packageName = packageName
        editNode.className = "android.widget.EditText"
        editNode.isVisibleToUser = true
        editNode.isEditable = true
        Shadows.shadowOf(editNode).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                setTextCount.incrementAndGet()
            }
            true
        }
        Shadows.shadowOf(root).addChild(editNode)

        val sendNode = AccessibilityNodeInfo.obtain()
        sendNode.packageName = packageName
        sendNode.className = "android.widget.ImageButton"
        sendNode.viewIdResourceName = "$packageName:id/send"
        sendNode.isVisibleToUser = true
        sendNode.isClickable = true
        Shadows.shadowOf(sendNode).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                clickCount.incrementAndGet()
            }
            true
        }
        Shadows.shadowOf(root).addChild(sendNode)

        return root
    }

    // Critical Test (Cases 1, 2, 3, 4, 9):
    // contentIntent.send() writes HANDOFF (NOT SENT) -> Accessibility consumes once & transitions to SENT ->
    // second event writes 0 times -> late markDeliveryUnknown cannot downgrade SENT.
    @Test
    fun test_critical_contentIntent_writes_handoff_then_accessibility_completes_sent_and_blocks_downgrade() = runTest {
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "handoff-crit", "مرحبا")
        assertTrue(guard.tryAcquireReservation(msgKey))

        val pendingCoordinator = PendingReplyCoordinator()
        val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard)
        var fallbackCount = 0
        var enqueuedBeforeSend = false

        // Step 1: DirectReply throws CanceledException, contentIntent.send() succeeds
        val outcome = dispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "أحمد",
            replyText = "رد بعد handoff",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") },
            contentSendInvoker = {
                fallbackCount++
                enqueuedBeforeSend = pendingCoordinator.peekValidForPackage("com.whatsapp")?.msgKey == msgKey
            }
        )

        assertTrue(enqueuedBeforeSend)
        assertEquals(1, fallbackCount)
        assertTrue(outcome is ReplyDispatcher.SendOutcome.Sent)

        // (1) contentIntent.send() MUST write HANDOFF and NOT SENT
        val afterHandoff = db.dedupKeyDao().getByKey(msgKey)
        assertNotEquals(DedupKeyEntity.SEND_SENT, afterHandoff?.sendState)
        assertEquals(DedupKeyEntity.SEND_HANDOFF, afterHandoff?.sendState)

        // (2 & 3) Accessibility consumes pending entry, writes & clicks ONCE, then transitions to SENT
        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val setTextCount = AtomicInteger(0)
        val clickCount = AtomicInteger(0)
        val chatRoot = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount)

        val consumedFirst = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = chatRoot,
            coordinatorOverride = pendingCoordinator,
            idempotencyGuardOverride = guard,
            sendButtonDelayMs = 0L
        )
        assertTrue(consumedFirst)
        assertEquals(1, setTextCount.get())
        assertEquals(1, clickCount.get())
        assertEquals(DedupKeyEntity.SEND_SENT, db.dedupKeyDao().getByKey(msgKey)?.sendState)
        assertEquals(DedupKeyEntity.STATE_COMPLETED, db.dedupKeyDao().getByKey(msgKey)?.state)

        // (4) Second Accessibility event after SENT writes 0 times
        val consumedSecond = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = chatRoot,
            coordinatorOverride = pendingCoordinator,
            idempotencyGuardOverride = guard,
            sendButtonDelayMs = 0L
        )
        assertFalse(consumedSecond)
        assertEquals(1, setTextCount.get())
        assertEquals(1, clickCount.get())

        // (9) Late exception / markDeliveryUnknown / markFailedConfirmed / updateSendState NEVER downgrades SEND_SENT
        guard.markDeliveryUnknown(msgKey)
        guard.markFailedConfirmed(msgKey)
        guard.updateSendState(msgKey, DedupKeyEntity.SEND_DELIVERY_UNKNOWN)
        val afterLateError = db.dedupKeyDao().getByKey(msgKey)
        assertEquals(DedupKeyEntity.SEND_SENT, afterLateError?.sendState)
        assertEquals(DedupKeyEntity.STATE_COMPLETED, afterLateError?.state)
        assertNotEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, afterLateError?.state)
    }

    // End-to-end Coordinator + Accessibility handoff test: ensures NotificationCoordinator doesn't
    // mark STATE_COMPLETED prematurely before Accessibility finishes sending.
    @Test
    fun test_coordinator_to_accessibility_handoff_not_blocked_by_completed_state() = runTest {
        val settingsStore = SettingsDataStore(context)
        try {
            settingsStore.setAutoReplyEnabled(true)
            settingsStore.setDrivingModeEnabled(false)
            settingsStore.setReplyDelaySeconds(0)
            settingsStore.setUseAccessibilityFallback(true)

            val testJson = Json { ignoreUnknownKeys = true }
            val repo = AutoMateRepository(
                replyRuleDao = db.replyRuleDao(),
                contactFilterDao = db.contactFilterDao(),
                replyLogDao = db.replyLogDao(),
                settingsDataStore = settingsStore,
                backupManager = BackupManager(context, testJson)
            )
            repo.upsertRule(ReplyRuleEntity(keyword = "سعر", replyText = "100 ريال", matchType = MatchType.CONTAINS))

            val pendingCoordinator = PendingReplyCoordinator()
            val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard)
            val vault = SecretVault(context, context.getSharedPreferences("hf_v_${System.nanoTime()}", Context.MODE_PRIVATE))
            val saPrefs = SmartAdvisorPrefs(context, context.getSharedPreferences("hf_sa_${System.nanoTime()}", Context.MODE_PRIVATE))
            val aiPrefs = AiSettingsPrefs(context, context.getSharedPreferences("hf_ai_${System.nanoTime()}", Context.MODE_PRIVATE))
            val wm = runCatching { WorkManager.getInstance(context) }.getOrElse {
                WorkManager.initialize(context, Configuration.Builder().setExecutor { r -> r.run() }.build())
                WorkManager.getInstance(context)
            }
            val engine = AutoReplyEngine(
                repository = repo,
                pendingReplyCoordinator = pendingCoordinator,
                menuReplyManager = MenuReplyManager(db.menuReplyDao(), testJson),
                followUpScheduler = FollowUpScheduler(wm, db.followUpDao()),
                aiSettingsPrefs = aiPrefs,
                aiReplyEngine = AIReplyEngine(HttpTransport(), vault),
                secretVault = vault,
                replyDispatcher = dispatcher
            )
            val fakeServerRepo = object : ServerConfigRepository {
                private val flow = MutableStateFlow<ServerConfigEntity?>(null)
                override fun observe(): Flow<ServerConfigEntity?> = flow
                override suspend fun get(): ServerConfigEntity? = flow.value
                override suspend fun save(config: ServerConfigEntity) { flow.value = config }
            }
            val coordinator = NotificationCoordinator(
                autoReplyEngine = engine,
                replyDispatcher = dispatcher,
                repository = repo,
                serverRepo = fakeServerRepo,
                webhookClient = WebhookClient(HttpTransport(), vault),
                smartAdvisorClient = SmartAdvisorClient(HttpTransport(), saPrefs, vault),
                smartAdvisorPrefs = saPrefs,
                idempotencyGuard = guard,
                rateLimiter = TokenBucketRateLimiter(10, 10),
                workManager = wm
            )

            val res = coordinator.process(
                NotificationPayload(
                    packageName = "com.whatsapp",
                    sbnKey = "sbn-hf-e2e",
                    notificationId = "700",
                    senderName = "فهد",
                    rawTitle = "فهد",
                    messageText = "كم سعر المنتج؟",
                    replyAction = buildDummyReplyAction(),
                    contentIntent = buildDummyContentIntent(),
                    directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") },
                    contentSendInvoker = { }
                )
            )
            assertTrue(res.isHandled)
            assertEquals(DedupKeyEntity.SEND_HANDOFF, db.dedupKeyDao().getByKey(res.msgKey)?.sendState)
            assertNotEquals(DedupKeyEntity.STATE_COMPLETED, db.dedupKeyDao().getByKey(res.msgKey)?.state)

            val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
            val setTextCount = AtomicInteger(0)
            val clickCount = AtomicInteger(0)
            val chatRoot = buildChatTree("com.whatsapp", "فهد", setTextCount, clickCount)

            val handled = service.handleEventInternal(
                eventPackage = "com.whatsapp",
                rootNode = chatRoot,
                coordinatorOverride = pendingCoordinator,
                idempotencyGuardOverride = guard,
                sendButtonDelayMs = 0L
            )
            assertTrue(handled)
            assertEquals(1, setTextCount.get())
            assertEquals(1, clickCount.get())
            assertEquals(DedupKeyEntity.SEND_SENT, db.dedupKeyDao().getByKey(res.msgKey)?.sendState)
            assertEquals(DedupKeyEntity.STATE_COMPLETED, db.dedupKeyDao().getByKey(res.msgKey)?.state)
        } finally {
            settingsStore.setAutoReplyEnabled(false)
            settingsStore.setDrivingModeEnabled(false)
        }
    }

    // Cases 5 & 6: SEND_SENT and DELIVERY_UNKNOWN both block Accessibility consumption
    @Test
    fun test_sent_and_deliveryUnknown_block_accessibility_consumption() = runTest {
        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val coordinator = PendingReplyCoordinator()
        val setTextCount = AtomicInteger(0)
        val clickCount = AtomicInteger(0)

        // Case 5: SEND_SENT blocks
        val sentKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "hf-sent", "hi")
        assertTrue(guard.tryAcquireReservation(sentKey))
        guard.updateSendState(sentKey, DedupKeyEntity.SEND_SENT)
        coordinator.enqueue(PendingAccessibilityReply(msgKey = sentKey, targetPackage = "com.whatsapp", senderName = "أحمد", replyText = "1"))
        assertFalse(
            service.handleEventInternal(
                eventPackage = "com.whatsapp",
                rootNode = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount),
                coordinatorOverride = coordinator,
                idempotencyGuardOverride = guard,
                sendButtonDelayMs = 0L
            )
        )

        // Case 6: DELIVERY_UNKNOWN blocks
        val unkKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "hf-unk", "hi")
        assertTrue(guard.tryAcquireReservation(unkKey))
        guard.markDeliveryUnknown(unkKey)
        coordinator.enqueue(PendingAccessibilityReply(msgKey = unkKey, targetPackage = "com.whatsapp", senderName = "أحمد", replyText = "2"))
        assertFalse(
            service.handleEventInternal(
                eventPackage = "com.whatsapp",
                rootNode = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount),
                coordinatorOverride = coordinator,
                idempotencyGuardOverride = guard,
                sendButtonDelayMs = 0L
            )
        )

        // Verification of non-blocking sendStates: IN_FLIGHT, NONE, and HANDOFF are allowed
        assertFalse(service.isBlockedDedupState(DedupKeyEntity(key = "a", state = DedupKeyEntity.STATE_PROCESSING, sendState = DedupKeyEntity.SEND_NONE)))
        assertFalse(service.isBlockedDedupState(DedupKeyEntity(key = "b", state = DedupKeyEntity.STATE_PROCESSING, sendState = DedupKeyEntity.SEND_IN_FLIGHT)))
        assertFalse(service.isBlockedDedupState(DedupKeyEntity(key = "c", state = DedupKeyEntity.STATE_PROCESSING, sendState = DedupKeyEntity.SEND_HANDOFF)))
        assertTrue(service.isBlockedDedupState(DedupKeyEntity(key = "d", state = DedupKeyEntity.STATE_PROCESSING, sendState = DedupKeyEntity.SEND_SENT)))
        assertTrue(service.isBlockedDedupState(DedupKeyEntity(key = "e", state = DedupKeyEntity.STATE_PROCESSING, sendState = DedupKeyEntity.SEND_DELIVERY_UNKNOWN)))
        assertTrue(service.isBlockedDedupState(DedupKeyEntity(key = "f", state = DedupKeyEntity.STATE_COMPLETED, sendState = DedupKeyEntity.SEND_HANDOFF)))

        assertEquals(0, setTextCount.get())
        assertEquals(0, clickCount.get())
    }

    // Cases 7 & 8: Ambiguous contentIntent failure leaves 0 pending; DELIVERY_UNKNOWN blocks secondary channel;
    // CanceledException allows single confirmed fallback -> SEND_HANDOFF
    @Test
    fun test_ambiguous_contentIntent_removes_pending_and_canceledException_allows_single_fallback() = runTest {
        val pendingCoordinator = PendingReplyCoordinator()
        val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard)

        val unkKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "hf-amb", "hello")
        assertTrue(guard.tryAcquireReservation(unkKey))
        val unkOutcome = dispatcher.dispatchReply(
            msgKey = unkKey,
            targetPackage = "com.whatsapp",
            senderName = "خالد",
            replyText = "رد",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") },
            contentSendInvoker = { throw RuntimeException("Binder dead") }
        )
        assertTrue(unkOutcome is ReplyDispatcher.SendOutcome.DeliveryUnknown)
        assertNull(pendingCoordinator.peekValidForPackage("com.whatsapp"))

        // Secondary channel resend attempt when state is already DELIVERY_UNKNOWN must stay blocked
        var secondChannelCalled = false
        val blockedRetryOutcome = dispatcher.dispatchReply(
            msgKey = unkKey,
            targetPackage = "com.whatsapp",
            senderName = "خالد",
            replyText = "رد ثان",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> secondChannelCalled = true },
            contentSendInvoker = { secondChannelCalled = true }
        )
        assertTrue(blockedRetryOutcome is ReplyDispatcher.SendOutcome.DeliveryUnknown)
        assertFalse(secondChannelCalled)
        assertNull(pendingCoordinator.peekValidForPackage("com.whatsapp"))

        val okKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "hf-ok", "hello")
        assertTrue(guard.tryAcquireReservation(okKey))
        var fallbackCount = 0
        val okOutcome = dispatcher.dispatchReply(
            msgKey = okKey,
            targetPackage = "com.whatsapp",
            senderName = "خالد",
            replyText = "رد مؤكد",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") },
            contentSendInvoker = { fallbackCount++ }
        )
        assertTrue(okOutcome is ReplyDispatcher.SendOutcome.Sent)
        assertEquals(1, fallbackCount)
        assertEquals(DedupKeyEntity.SEND_HANDOFF, db.dedupKeyDao().getByKey(okKey)?.sendState)
    }
}
