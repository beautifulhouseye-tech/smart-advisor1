package com.example.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.data.local.entity.DedupKeyEntity
import com.example.media.ProductImageCacheManager
import com.example.network.HttpTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplyDispatcher private constructor(
    @ApplicationContext private val context: Context,
    private val pendingReplyCoordinator: PendingReplyCoordinator,
    private val idempotencyGuard: IdempotencyGuard,
    private val productImageCacheManager: ProductImageCacheManager?,
    @Suppress("UNUSED_PARAMETER") unusedMarker: Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        pendingReplyCoordinator: PendingReplyCoordinator,
        idempotencyGuard: IdempotencyGuard,
        productImageCacheManager: ProductImageCacheManager
    ) : this(
        context = context,
        pendingReplyCoordinator = pendingReplyCoordinator,
        idempotencyGuard = idempotencyGuard,
        productImageCacheManager = productImageCacheManager,
        unusedMarker = true
    )

    constructor(
        context: Context,
        pendingReplyCoordinator: PendingReplyCoordinator,
        idempotencyGuard: IdempotencyGuard
    ) : this(
        context = context,
        pendingReplyCoordinator = pendingReplyCoordinator,
        idempotencyGuard = idempotencyGuard,
        productImageCacheManager = null,
        unusedMarker = true
    )

    sealed class SendOutcome {
        data class Sent(
            val deliveryMethod: String,
            val isHandoff: Boolean = false
        ) : SendOutcome()
        data class FailedConfirmed(val error: String) : SendOutcome()
        data class DeliveryUnknown(val error: String) : SendOutcome()
    }

    suspend fun dispatchProductReply(
        msgKey: String,
        targetPackage: String,
        senderName: String,
        responseType: ResponseType,
        productMessage: ProductMessage?,
        fallbackTextReply: String,
        replyAction: Notification.Action?,
        contentIntent: PendingIntent?,
        useAccessibilityFallback: Boolean,
        directMethodLabel: String = "DirectReply",
        accessibilityTextMethodLabel: String = "Accessibility",
        accessibilityImageMethodLabel: String = "Accessibility-ProductImage",
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
                    pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.DELIVERY_UNKNOWN)
                    return SendOutcome.DeliveryUnknown("حظر إعادة الإرسال: حالة التسليم غير معروفة")
                }
                if (existing.sendState == DedupKeyEntity.SEND_SENT) {
                    pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.SENT)
                    return SendOutcome.Sent(accessibilityImageMethodLabel)
                }
            }
        }

        val resolvedTextFallback = fallbackTextReply.trim().ifBlank {
            productMessage?.buildTextFallbackReply().orEmpty()
        }

        if (responseType != ResponseType.PRODUCT_IMAGE ||
            productMessage == null ||
            !productMessage.canSendAsImage() ||
            !useAccessibilityFallback ||
            contentIntent == null
        ) {
            return dispatchReply(
                msgKey = msgKey,
                targetPackage = targetPackage,
                senderName = senderName,
                replyText = resolvedTextFallback,
                replyAction = replyAction,
                contentIntent = contentIntent,
                useAccessibilityFallback = useAccessibilityFallback,
                directMethodLabel = directMethodLabel,
                accessibilityMethodLabel = accessibilityTextMethodLabel,
                directSendInvoker = directSendInvoker,
                contentSendInvoker = contentSendInvoker
            )
        }

        val cacheManager = productImageCacheManager
            ?: ProductImageCacheManager(context, HttpTransport.defaultClient())

        pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.QUEUED)
        pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.DOWNLOADING_IMAGE)

        val cacheResult = cacheManager.getOrDownload(productMessage.imageUrl)
        if (cacheResult !is ProductImageCacheManager.CacheResult.Ready) {
            pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.FAILED)
            Log.w(TAG, "Product image unavailable pre-send; falling back cleanly to text")
            return dispatchReply(
                msgKey = msgKey,
                targetPackage = targetPackage,
                senderName = senderName,
                replyText = resolvedTextFallback,
                replyAction = replyAction,
                contentIntent = contentIntent,
                useAccessibilityFallback = useAccessibilityFallback,
                directMethodLabel = "$directMethodLabel-TextFallback",
                accessibilityMethodLabel = "$accessibilityTextMethodLabel-TextFallback",
                directSendInvoker = directSendInvoker,
                contentSendInvoker = contentSendInvoker
            )
        }

        pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.IMAGE_READY)
        val stagedMediaUri = cacheManager.stageForGalleryPicker(cacheResult)
        val caption = productMessage.buildCaption().ifBlank { resolvedTextFallback }

        if (msgKey.isNotBlank()) {
            idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_IN_FLIGHT)
        }

        val pendingImageReply = PendingAccessibilityReply(
            msgKey = msgKey,
            targetPackage = targetPackage,
            senderName = senderName,
            replyText = caption,
            imageUri = cacheResult.fileProviderUri.toString(),
            mediaStoreUri = stagedMediaUri?.toString(),
            responseType = ResponseType.PRODUCT_IMAGE,
            deliveryState = ImageDeliveryState.IMAGE_READY,
            fallbackTextReply = resolvedTextFallback,
            requireCaptionBeforeSend = true,
            onPreSendImageFailure = {
                cacheManager.cleanupStagedMedia(stagedMediaUri?.toString())
                if (resolvedTextFallback.isNotBlank()) {
                    dispatchReply(
                        msgKey = msgKey,
                        targetPackage = targetPackage,
                        senderName = senderName,
                        replyText = resolvedTextFallback,
                        replyAction = replyAction,
                        contentIntent = contentIntent,
                        useAccessibilityFallback = useAccessibilityFallback,
                        directMethodLabel = "$directMethodLabel-TextFallback",
                        accessibilityMethodLabel = "$accessibilityTextMethodLabel-TextFallback",
                        directSendInvoker = directSendInvoker,
                        contentSendInvoker = contentSendInvoker
                    )
                }
            }
        )
        pendingReplyCoordinator.enqueue(pendingImageReply)

        val handoffResult = runCatching {
            if (contentSendInvoker != null) {
                contentSendInvoker.invoke(contentIntent)
            } else {
                contentIntent.send()
            }
        }

        if (handoffResult.isSuccess) {
            if (msgKey.isNotBlank()) {
                idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_HANDOFF)
            }
            runCatching { pendingReplyCoordinator.markServiceActivity() }
            return SendOutcome.Sent(accessibilityImageMethodLabel, isHandoff = true)
        }

        if (msgKey.isNotBlank()) {
            pendingReplyCoordinator.removeByMsgKey(msgKey)
        }
        pendingReplyCoordinator.consumeAtomic(pendingImageReply.id)
        cacheManager.cleanupStagedMedia(stagedMediaUri?.toString())

        val handoffErr = handoffResult.exceptionOrNull()!!
        return if (isConfirmedSendFailure(handoffErr)) {
            pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.FAILED)
            if (msgKey.isNotBlank()) {
                idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_FAILED_CONFIRMED)
            }
            dispatchReply(
                msgKey = msgKey,
                targetPackage = targetPackage,
                senderName = senderName,
                replyText = resolvedTextFallback,
                replyAction = replyAction,
                contentIntent = contentIntent,
                useAccessibilityFallback = false,
                directMethodLabel = "$directMethodLabel-TextFallback",
                accessibilityMethodLabel = "$accessibilityTextMethodLabel-TextFallback",
                directSendInvoker = directSendInvoker,
                contentSendInvoker = contentSendInvoker
            )
        } else {
            pendingReplyCoordinator.recordDeliveryState(msgKey, ImageDeliveryState.DELIVERY_UNKNOWN)
            if (msgKey.isNotBlank()) {
                idempotencyGuard.markDeliveryUnknown(msgKey)
            }
            SendOutcome.DeliveryUnknown(
                handoffErr.message ?: handoffErr::class.java.simpleName
            )
        }
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
