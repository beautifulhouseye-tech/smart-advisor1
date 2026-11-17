package com.example.worker

import android.app.PendingIntent
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.engine.IdempotencyGuard
import com.example.engine.ReplyDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FollowUpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val replyDispatcher: ReplyDispatcher,
    private val idempotencyGuard: IdempotencyGuard
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recipient = inputData.getString(KEY_RECIPIENT) ?: return Result.failure()
        val app = inputData.getString(KEY_APP) ?: return Result.failure()
        val message = inputData.getString(KEY_MESSAGE) ?: return Result.failure()

        val msgKey = IdempotencyGuard.deriveMsgKey(
            packageName = app,
            sbnKey = "followup|$recipient|${id.toString()}",
            text = message
        )
        if (!idempotencyGuard.tryAcquireReservation(msgKey)) {
            return Result.success()
        }

        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(app)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                msgKey.hashCode(),
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val outcome = replyDispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = app,
            senderName = recipient,
            replyText = message,
            replyAction = null,
            contentIntent = contentIntent,
            useAccessibilityFallback = true,
            directMethodLabel = "FollowUp-DirectReply",
            accessibilityMethodLabel = "FollowUp-Accessibility"
        )
        when (outcome) {
            is ReplyDispatcher.SendOutcome.Sent -> {
                if (!outcome.isHandoff) {
                    idempotencyGuard.markCompleted(msgKey)
                }
            }
            is ReplyDispatcher.SendOutcome.FailedConfirmed -> {
                idempotencyGuard.markFailedConfirmed(msgKey)
            }
            is ReplyDispatcher.SendOutcome.DeliveryUnknown -> {
                idempotencyGuard.markDeliveryUnknown(msgKey)
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_RECIPIENT = "recipient"
        const val KEY_APP = "app"
        const val KEY_MESSAGE = "message"
    }
}
