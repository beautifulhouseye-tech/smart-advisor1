package com.example.service

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.engine.NotificationCoordinator
import com.example.engine.NotificationPayload
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AutoReplyNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var notificationCoordinator: NotificationCoordinator

    @Inject
    lateinit var pendingReplyCoordinator: PendingReplyCoordinator

    @Inject
    lateinit var replyDispatcher: ReplyDispatcher

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        pendingReplyCoordinator.setNotificationListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        pendingReplyCoordinator.setNotificationListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return

        // Ignore our own app notifications and group summary rollups
        if (pkg == packageName) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val messageText = (bigText ?: normalText).orEmpty()

        if (rawTitle.isEmpty() || messageText.isEmpty()) return

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val isGroupExplicit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        } else {
            !conversationTitle.isNullOrEmpty()
        }
        val isGroup = isGroupExplicit || (!conversationTitle.isNullOrEmpty() && conversationTitle != rawTitle)

        val senderName = if (isGroup && !conversationTitle.isNullOrEmpty()) {
            conversationTitle
        } else {
            rawTitle
        }

        val replyAction = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }

        val payload = NotificationPayload(
            packageName = pkg,
            sbnKey = sbn.key.orEmpty(),
            notificationId = sbn.id.toString(),
            senderName = senderName,
            rawTitle = rawTitle,
            messageText = messageText,
            phone = extractPhoneIfPresent(rawTitle),
            groupName = conversationTitle,
            isGroup = isGroup,
            replyAction = replyAction,
            contentIntent = notification.contentIntent
        )

        serviceScope.launch {
            runCatching {
                notificationCoordinator.process(payload)
            }.onFailure { err ->
                Log.e(TAG, "Error handling posted notification", err)
            }
        }
    }

    suspend fun sendReplyViaBestMethod(
        sbn: StatusBarNotification,
        reply: String,
        app: String,
        sender: String,
        msgKey: String = "",
        useAccessibilityFallback: Boolean = true
    ): ReplyDispatcher.SendOutcome {
        val notification = sbn.notification
        val replyAction = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
        return replyDispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = app,
            senderName = sender,
            replyText = reply,
            replyAction = replyAction,
            contentIntent = notification.contentIntent,
            useAccessibilityFallback = useAccessibilityFallback,
            directMethodLabel = "Webhook-DirectReply",
            accessibilityMethodLabel = "Webhook-Accessibility"
        )
    }

    private fun extractPhoneIfPresent(rawTitle: String): String {
        val cleaned = rawTitle.trim()
        return if (PHONE_REGEX.matches(cleaned)) cleaned else ""
    }

    companion object {
        private const val TAG = "AutoReplyNotifService"
        private val PHONE_REGEX = Regex("""^\+?\d[\d\s\-()]{6,}$""")
    }
}
