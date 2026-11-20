package com.example.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

data class PendingAccessibilityReply(
    val id: Long = System.nanoTime(),
    val msgKey: String = "",
    val targetPackage: String,
    val senderName: String,
    val replyText: String,
    val imageUri: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val responseType: ResponseType = ResponseType.TEXT_MESSAGE,
    val deliveryState: ImageDeliveryState = ImageDeliveryState.QUEUED,
    val mediaStoreUri: String? = null,
    val fallbackTextReply: String = "",
    val requireCaptionBeforeSend: Boolean = false,
    val onPreSendImageFailure: (suspend () -> Unit)? = null
)

data class ServiceConnectionStatus(
    val isNotificationListenerConnected: Boolean = false,
    val isAccessibilityConnected: Boolean = false,
    val lastActiveEventAtMs: Long? = null
)

@Singleton
class PendingReplyCoordinator internal constructor(
    private val clockMs: () -> Long
) {
    @Inject
    constructor() : this(clockMs = { System.currentTimeMillis() })

    private val queueLock = Any()
    private val queue = ConcurrentLinkedDeque<PendingAccessibilityReply>()
    private val imageDeliveryStates = java.util.concurrent.ConcurrentHashMap<String, ImageDeliveryState>()

    private val _connectionStatus = MutableStateFlow(ServiceConnectionStatus())
    val connectionStatus: StateFlow<ServiceConnectionStatus> = _connectionStatus.asStateFlow()

    fun setNotificationListenerConnected(connected: Boolean) {
        _connectionStatus.update { it.copy(isNotificationListenerConnected = connected) }
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _connectionStatus.update { it.copy(isAccessibilityConnected = connected) }
    }

    fun markServiceActivity() {
        _connectionStatus.update { it.copy(lastActiveEventAtMs = clockMs()) }
    }

    fun recordDeliveryState(msgKey: String, state: ImageDeliveryState) {
        val clean = msgKey.trim()
        if (clean.isNotEmpty()) {
            imageDeliveryStates[clean] = state
        }
    }

    fun getDeliveryState(msgKey: String): ImageDeliveryState? {
        val clean = msgKey.trim()
        return if (clean.isEmpty()) null else imageDeliveryStates[clean]
    }

    fun enqueue(reply: PendingAccessibilityReply) {
        synchronized(queueLock) {
            pruneExpiredLocked()
            queue.addLast(reply)
            if (reply.msgKey.isNotBlank() && reply.imageUri != null) {
                imageDeliveryStates[reply.msgKey.trim()] = reply.deliveryState
            }
        }
    }

    fun peekValidForPackage(packageName: String): PendingAccessibilityReply? {
        synchronized(queueLock) {
            pruneExpiredLocked()
            return queue.firstOrNull { it.targetPackage == packageName }
        }
    }

    fun consumeAtomic(replyId: Long): Boolean {
        synchronized(queueLock) {
            pruneExpiredLocked()
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().id == replyId) {
                    iterator.remove()
                    return true
                }
            }
            return false
        }
    }

    fun consume(replyId: Long) {
        consumeAtomic(replyId)
    }

    fun removeByMsgKey(msgKey: String): Int {
        val clean = msgKey.trim()
        if (clean.isEmpty()) return 0
        synchronized(queueLock) {
            var removed = 0
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().msgKey == clean) {
                    iterator.remove()
                    removed++
                }
            }
            return removed
        }
    }

    fun clear() {
        synchronized(queueLock) {
            queue.clear()
            imageDeliveryStates.clear()
        }
    }

    fun snapshot(): List<PendingAccessibilityReply> {
        synchronized(queueLock) {
            pruneExpiredLocked()
            return queue.toList()
        }
    }

    fun pendingCount(): Int {
        synchronized(queueLock) {
            pruneExpiredLocked()
            return queue.size
        }
    }

    private fun pruneExpiredLocked() {
        val now = clockMs()
        queue.removeIf { now - it.createdAtMs > REPLY_EXPIRY_MS }
    }

    companion object {
        const val REPLY_EXPIRY_MS = 30_000L
    }
}
