package com.example.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.data.local.entity.DedupKeyEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplyDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingReplyCoordinator: PendingReplyCoordinator,
    private val idempotencyGuard: IdempotencyGuard
) {
    sealed class SendOutcome {
        data class Sent(
            val deliveryMethod: String,
            val isHandoff: Boolean = false
        ) : SendOutcome()
        data class FailedConfirmed(val error: String) : SendOutcome()
        data class DeliveryUnknown(val error: String) : SendOutcome()
    }

    suspend fun dispatchReply(
        msgKey: String,
        targetPackage: String,
        senderName: String,
        replyText: String,
        replyAction: Notification.Action?,
        contentIntent: PendingIntent?,
        useAccessibilityFallback: Boolean,
        directMethodLabel: String = "DirectReply",
        accessibilityMethodLabel: String = "Accessibility",
        directSendInvoker: ((Notification.Action, String) -> Unit)? = null,
        contentSendInvoker: ((PendingIntent) -> Unit)? = null
    ): SendOutcome {
        if (msgKey.isNotBlank()) {
            val existing = idempotencyGuard.getEntry(msgKey)
            if (existing != null) {
                if (existing.sendState == DedupKeyEntity.SEND_DELIVERY_UNKNOWN ||
                    existing.state == DedupKeyEntity.STATE_DELIVERY_UNKNOWN
                ) {
                    pendingReplyCoordinator.removeByMsgKey(msgKey)
                    return SendOutcome.DeliveryUnknown("حظر إعادة الإرسال: حالة التسليم غير معروفة")
                }
                if (existing.sendState == DedupKeyEntity.SEND_SENT) {
                    return SendOutcome.Sent(directMethodLabel)
                }
            }
        }

        val hasDirectInput = replyAction?.remoteInputs?.isNotEmpty() == true
        var directFailureError: String? = null

        if (hasDirectInput) {
            if (msgKey.isNotBlank()) {
                idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_IN_FLIGHT)
            }

            val directResult = runCatching {
                if (directSendInvoker != null) {
                    directSendInvoker.invoke(replyAction, replyText)
                } else {
                    val intent = Intent()
                    val results = Bundle()
                    for (input in replyAction.remoteInputs) {
                        results.putCharSequence(input.resultKey, replyText)
                    }
                    RemoteInput.addResultsToIntent(replyAction.remoteInputs, intent, results)
                    replyAction.actionIntent.send(context, 0, intent)
                }
            }

            if (directResult.isSuccess) {
                if (msgKey.isNotBlank()) {
                    idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_SENT)
                }
                runCatching { pendingReplyCoordinator.markServiceActivity() }
                return SendOutcome.Sent(directMethodLabel, isHandoff = false)
            }

            val err = directResult.exceptionOrNull()!!
            if (isConfirmedSendFailure(err)) {
                directFailureError = err.message ?: err::class.java.simpleName
                if (msgKey.isNotBlank()) {
                    idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_FAILED_CONFIRMED)
                }
                Log.w(TAG, "DirectReply confirmed failure, fallback eligible once", err)
            } else {
                val unknownError = err.message ?: err::class.java.simpleName
                if (msgKey.isNotBlank()) {
                    idempotencyGuard.markDeliveryUnknown(msgKey)
                    pendingReplyCoordinator.removeByMsgKey(msgKey)
                }
                Log.e(TAG, "DirectReply ambiguous failure -> DELIVERY_UNKNOWN (blocking fallback)", err)
                return SendOutcome.DeliveryUnknown(unknownError)
            }
        }

        // Accessibility fallback is ONLY attempted when DirectReply was unavailable OR FAILED_CONFIRMED (at most once)
        if (!useAccessibilityFallback || contentIntent == null) {
            if (msgKey.isNotBlank()) {
                idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_FAILED_CONFIRMED)
            }
            return SendOutcome.FailedConfirmed(
                directFailureError ?: "لا توجد وسيلة إرسال متاحة"
            )
        }

        if (msgKey.isNotBlank()) {
            idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_IN_FLIGHT)
        }

        val pendingReply = PendingAccessibilityReply(
            msgKey = msgKey,
            targetPackage = targetPackage,
            senderName = senderName,
            replyText = replyText
        )
        pendingReplyCoordinator.enqueue(pendingReply)

        val fallbackResult = runCatching {
            if (contentSendInvoker != null) {
                contentSendInvoker.invoke(contentIntent)
            } else {
                contentIntent.send()
            }
        }

        if (fallbackResult.isSuccess) {
            if (msgKey.isNotBlank()) {
                idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_HANDOFF)
            }
            runCatching { pendingReplyCoordinator.markServiceActivity() }
            return SendOutcome.Sent(accessibilityMethodLabel, isHandoff = true)
        }

        if (msgKey.isNotBlank()) {
            pendingReplyCoordinator.removeByMsgKey(msgKey)
        }
        pendingReplyCoordinator.consumeAtomic(pendingReply.id)

        val fallbackErr = fallbackResult.exceptionOrNull()!!
        return if (isConfirmedSendFailure(fallbackErr)) {
            if (msgKey.isNotBlank()) {
                idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_FAILED_CONFIRMED)
            }
            SendOutcome.FailedConfirmed(
                fallbackErr.message ?: directFailureError ?: fallbackErr::class.java.simpleName
            )
        } else {
            if (msgKey.isNotBlank()) {
                idempotencyGuard.markDeliveryUnknown(msgKey)
            }
            SendOutcome.DeliveryUnknown(
                fallbackErr.message ?: fallbackErr::class.java.simpleName
            )
        }
    }

    companion object {
        private const val TAG = "ReplyDispatcher"

        fun isConfirmedSendFailure(error: Throwable): Boolean {
            return error is PendingIntent.CanceledException ||
                error is SecurityException ||
                error is IllegalArgumentException
        }
    }
}
