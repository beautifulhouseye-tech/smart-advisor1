package com.example

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.DedupKeyEntity
import com.example.engine.IdempotencyGuard
import com.example.engine.PendingAccessibilityReply
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import com.example.service.AutoReplyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class G2AccessibilityClosureTest {

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
            201,
            Intent("com.example.ACTION_REPLY_G2"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(0, "Reply", pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun buildDummyContentIntent(): PendingIntent {
        return PendingIntent.getActivity(
            context,
            202,
            Intent("com.example.ACTION_OPEN_G2"),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildChatTree(
        packageName: String,
        chatTitle: String?,
        setTextCount: AtomicInteger,
        clickCount: AtomicInteger
    ): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = packageName
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        if (chatTitle != null) {
            val titleNode = AccessibilityNodeInfo.obtain()
            titleNode.packageName = packageName
            titleNode.className = "android.widget.TextView"
            titleNode.viewIdResourceName = "$packageName:id/conversation_contact_name"
            titleNode.text = chatTitle
            titleNode.isVisibleToUser = true
            titleNode.isEditable = false
            Shadows.shadowOf(root).addChild(titleNode)
        }

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

    // 1. Ambiguous contentIntent failure -> 0 pending entries remaining for this msgKey
    @Test
    fun test_01_ambiguous_contentIntent_failure_removes_pending_entry_for_msgKey() = runTest {
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "g2-1", "مرحبا")
        assertTrue(guard.tryAcquireReservation(msgKey))

        val coordinator = PendingReplyCoordinator()
        val dispatcher = ReplyDispatcher(context, coordinator, guard)

        val outcome = dispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "أحمد",
            replyText = "رد تلقائي",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") },
            contentSendInvoker = { throw RuntimeException("Binder failure") }
        )

        assertTrue(outcome is ReplyDispatcher.SendOutcome.DeliveryUnknown)
        assertEquals(0, coordinator.pendingCount())
        assertNull(coordinator.peekValidForPackage("com.whatsapp"))
        assertEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, db.dedupKeyDao().getByKey(msgKey)?.state)
    }

    // 2. Pending entry older than 30s -> 0 writes
    @Test
    fun test_02_expired_pending_entry_older_than_30s_zero_writes() = runTest {
        var now = 1_000_000_000L
        val coordinator = PendingReplyCoordinator(clockMs = { now })
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "g2-2", "سؤال")
        assertTrue(guard.tryAcquireReservation(msgKey))

        coordinator.enqueue(
            PendingAccessibilityReply(
                msgKey = msgKey,
                targetPackage = "com.whatsapp",
                senderName = "أحمد",
                replyText = "رد منتهي",
                createdAtMs = now
            )
        )

        now += 31_000L
        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val setTextCount = AtomicInteger(0)
        val clickCount = AtomicInteger(0)
        val root = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount)

        val handled = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = root,
            coordinatorOverride = coordinator,
            idempotencyGuardOverride = guard,
            clockMs = { now },
            sendButtonDelayMs = 0L
        )

        assertFalse(handled)
        assertEquals(0, setTextCount.get())
        assertEquals(0, clickCount.get())
    }

    // 3. sendState == SEND_SENT -> 0 writes
    @Test
    fun test_03_sendState_SENT_zero_writes() = runTest {
        val coordinator = PendingReplyCoordinator()
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "g2-3", "سؤال")
        assertTrue(guard.tryAcquireReservation(msgKey))
        guard.updateSendState(msgKey, DedupKeyEntity.SEND_SENT)

        coordinator.enqueue(
            PendingAccessibilityReply(
                msgKey = msgKey,
                targetPackage = "com.whatsapp",
                senderName = "أحمد",
                replyText = "رد مكرر"
            )
        )

        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val setTextCount = AtomicInteger(0)
        val clickCount = AtomicInteger(0)
        val root = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount)

        val handled = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = root,
            coordinatorOverride = coordinator,
            idempotencyGuardOverride = guard,
            sendButtonDelayMs = 0L
        )

        assertFalse(handled)
        assertEquals(0, setTextCount.get())
        assertEquals(0, clickCount.get())
        assertEquals(0, coordinator.pendingCount())
    }

    // 4. sendState == DELIVERY_UNKNOWN -> 0 writes
    @Test
    fun test_04_sendState_DELIVERY_UNKNOWN_zero_writes() = runTest {
        val coordinator = PendingReplyCoordinator()
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "g2-4", "سؤال")
        assertTrue(guard.tryAcquireReservation(msgKey))
        guard.markDeliveryUnknown(msgKey)

        coordinator.enqueue(
            PendingAccessibilityReply(
                msgKey = msgKey,
                targetPackage = "com.whatsapp",
                senderName = "أحمد",
                replyText = "محاولة غير آمنة"
            )
        )

        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val setTextCount = AtomicInteger(0)
        val clickCount = AtomicInteger(0)
        val root = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount)

        val handled = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = root,
            coordinatorOverride = coordinator,
            idempotencyGuardOverride = guard,
            sendButtonDelayMs = 0L
        )

        assertFalse(handled)
        assertEquals(0, setTextCount.get())
        assertEquals(0, clickCount.get())
        assertEquals(0, coordinator.pendingCount())
    }

    // 5. Two concurrent accessibility events + single pending entry -> strictly 1 write + updates SEND_SENT
    @Test
    fun test_05_concurrent_accessibility_events_single_entry_writes_strictly_once() = runTest {
        val coordinator = PendingReplyCoordinator()
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "g2-5", "سؤال")
        assertTrue(guard.tryAcquireReservation(msgKey))
        guard.updateSendState(msgKey, DedupKeyEntity.SEND_IN_FLIGHT)

        coordinator.enqueue(
            PendingAccessibilityReply(
                msgKey = msgKey,
                targetPackage = "com.whatsapp",
                senderName = "أحمد",
                replyText = "رد واحد فقط"
            )
        )

        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val setTextCount = AtomicInteger(0)
        val clickCount = AtomicInteger(0)
        val rootA = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount)
        val rootB = buildChatTree("com.whatsapp", "أحمد", setTextCount, clickCount)

        val outcomes = listOf(
            async(Dispatchers.Default) {
                service.handleEventInternal(
                    eventPackage = "com.whatsapp",
                    rootNode = rootA,
                    coordinatorOverride = coordinator,
                    idempotencyGuardOverride = guard,
                    sendButtonDelayMs = 0L
                )
            },
            async(Dispatchers.Default) {
                service.handleEventInternal(
                    eventPackage = "com.whatsapp",
                    rootNode = rootB,
                    coordinatorOverride = coordinator,
                    idempotencyGuardOverride = guard,
                    sendButtonDelayMs = 0L
                )
            }
        ).awaitAll()

        assertEquals(1, outcomes.count { it })
        assertEquals(1, outcomes.count { !it })
        assertEquals(1, setTextCount.get())
        assertEquals(1, clickCount.get())
        assertEquals(0, coordinator.pendingCount())
        assertEquals(DedupKeyEntity.SEND_SENT, db.dedupKeyDao().getByKey(msgKey)?.sendState)
    }

    // 6. Different open conversation title (or unverifiable title) -> 0 writes
    @Test
    fun test_06_different_open_conversation_zero_writes() = runTest {
        val coordinator = PendingReplyCoordinator()
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "g2-6", "سؤال")
        assertTrue(guard.tryAcquireReservation(msgKey))

        coordinator.enqueue(
            PendingAccessibilityReply(
                msgKey = msgKey,
                targetPackage = "com.whatsapp",
                senderName = "أحمد",
                replyText = "سري لأحمد"
            )
        )

        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val setTextCount = AtomicInteger(0)
        val clickCount = AtomicInteger(0)

        val wrongChatRoot = buildChatTree("com.whatsapp", "سارة", setTextCount, clickCount)
        val handledWrong = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = wrongChatRoot,
            coordinatorOverride = coordinator,
            idempotencyGuardOverride = guard,
            sendButtonDelayMs = 0L
        )
        assertFalse(handledWrong)

        val unverifiableChatRoot = buildChatTree("com.whatsapp", null, setTextCount, clickCount)
        val handledUnverifiable = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = unverifiableChatRoot,
            coordinatorOverride = coordinator,
            idempotencyGuardOverride = guard,
            sendButtonDelayMs = 0L
        )
        assertFalse(handledUnverifiable)

        assertEquals(0, setTextCount.get())
        assertEquals(0, clickCount.get())
    }

    // 7. App restart (onCreate) -> pending queue is cleared completely
    @Test
    fun test_07_app_restart_clears_pending_queue() {
        val app = context as AutoMateApp
        app.pendingReplyCoordinator.enqueue(
            PendingAccessibilityReply(
                msgKey = "restart-k7",
                targetPackage = "com.whatsapp",
                senderName = "أحمد",
                replyText = "رد قديم"
            )
        )
        assertTrue(app.pendingReplyCoordinator.pendingCount() >= 1)

        app.onCreate()

        assertEquals(0, app.pendingReplyCoordinator.pendingCount())
    }

    // 8. Confirmed failure (CanceledException) -> enqueue before send (I1) & single fallback allowed
    @Test
    fun test_08_confirmed_canceledException_enqueues_before_send_and_allows_single_fallback() = runTest {
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "g2-8", "مرحبا")
        assertTrue(guard.tryAcquireReservation(msgKey))

        val coordinator = PendingReplyCoordinator()
        val dispatcher = ReplyDispatcher(context, coordinator, guard)
        var fallbackCount = 0
        var queueCountAtSendMoment = -1
        var msgKeyAtSendMoment: String? = null

        val outcome = dispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "أحمد",
            replyText = "رد عبر الوصول",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") },
            contentSendInvoker = {
                fallbackCount++
                queueCountAtSendMoment = coordinator.pendingCount()
                msgKeyAtSendMoment = coordinator.peekValidForPackage("com.whatsapp")?.msgKey
            }
        )

        assertTrue(outcome is ReplyDispatcher.SendOutcome.Sent)
        assertEquals(1, fallbackCount)
        assertEquals(1, queueCountAtSendMoment)
        assertEquals(msgKey, msgKeyAtSendMoment)
    }
}
