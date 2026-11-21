package com.example.engine

import android.app.Notification
import android.app.PendingIntent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.local.entity.GroupReplyMode
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.WebhookMode
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.network.smartadvisor.SmartAdvisorResponseParser
import com.example.worker.MessageQueueWorker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationPayload(
    val packageName: String,
    val sbnKey: String,
    val notificationId: String,
    val senderName: String,
    val rawTitle: String,
    val messageText: String,
    val phone: String = "",
    val groupName: String? = null,
    val isGroup: Boolean = false,
    val replyAction: Notification.Action? = null,
    val contentIntent: PendingIntent? = null,
    val directSendInvoker: ((Notification.Action, String) -> Unit)? = null,
    val contentSendInvoker: ((PendingIntent) -> Unit)? = null
)

data class CoordinatorResult(
    val msgKey: String,
    val deliveryMethod: String,
    val replyText: String = "",
    val isHandled: Boolean = false
)

@Singleton
class NotificationCoordinator internal constructor(
    private val autoReplyEngine: AutoReplyEngine,
    private val replyDispatcher: ReplyDispatcher,
    private val repository: AutoMateRepository,
    private val serverRepo: ServerConfigRepository,
    private val webhookClient: WebhookClient,
    private val smartAdvisorClient: SmartAdvisorClient,
    private val smartAdvisorPrefs: SmartAdvisorPrefs,
    private val idempotencyGuard: IdempotencyGuard,
    private val rateLimiter: TokenBucketRateLimiter,
    private val workManager: WorkManager,
    private val aiSettingsPrefs: AiSettingsPrefs? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val queueScheduler: ((delayMs: Long) -> Unit)? = null,
    private val conversationMemoryManager: ConversationMemoryManager? = null,
    private val diagnosticLogger: SafeDiagnosticLogger? = null
) {
    @Inject
    constructor(
        autoReplyEngine: AutoReplyEngine,
        replyDispatcher: ReplyDispatcher,
        repository: AutoMateRepository,
        serverRepo: ServerConfigRepository,
        webhookClient: WebhookClient,
        smartAdvisorClient: SmartAdvisorClient,
        smartAdvisorPrefs: SmartAdvisorPrefs,
        idempotencyGuard: IdempotencyGuard,
        rateLimiter: TokenBucketRateLimiter,
        workManager: WorkManager,
        aiSettingsPrefs: AiSettingsPrefs,
        conversationMemoryManager: ConversationMemoryManager,
        diagnosticLogger: SafeDiagnosticLogger
    ) : this(
        autoReplyEngine = autoReplyEngine,
        replyDispatcher = replyDispatcher,
        repository = repository,
        serverRepo = serverRepo,
        webhookClient = webhookClient,
        smartAdvisorClient = smartAdvisorClient,
        smartAdvisorPrefs = smartAdvisorPrefs,
        idempotencyGuard = idempotencyGuard,
        rateLimiter = rateLimiter,
        workManager = workManager,
        aiSettingsPrefs = aiSettingsPrefs,
        clockMs = { System.currentTimeMillis() },
        queueScheduler = null,
        conversationMemoryManager = conversationMemoryManager,
        diagnosticLogger = diagnosticLogger
    )

    internal constructor(
        autoReplyEngine: AutoReplyEngine,
        replyDispatcher: ReplyDispatcher,
        repository: AutoMateRepository,
        serverRepo: ServerConfigRepository,
        webhookClient: WebhookClient,
        smartAdvisorClient: SmartAdvisorClient,
        smartAdvisorPrefs: SmartAdvisorPrefs,
        idempotencyGuard: IdempotencyGuard,
        rateLimiter: TokenBucketRateLimiter,
        workManager: WorkManager,
        clockMs: () -> Long,
        queueScheduler: ((delayMs: Long) -> Unit)?
    ) : this(
        autoReplyEngine = autoReplyEngine,
        replyDispatcher = replyDispatcher,
        repository = repository,
        serverRepo = serverRepo,
        webhookClient = webhookClient,
        smartAdvisorClient = smartAdvisorClient,
        smartAdvisorPrefs = smartAdvisorPrefs,
        idempotencyGuard = idempotencyGuard,
        rateLimiter = rateLimiter,
        workManager = workManager,
        aiSettingsPrefs = null,
        clockMs = clockMs,
        queueScheduler = queueScheduler,
        conversationMemoryManager = null
    )

    private data class SentEcho(
        val normalizedText: String,
        val timestampMs: Long
    )

    private val lastSentReplies = ConcurrentHashMap<String, SentEcho>()
    private val lastReplyTimestamps = ConcurrentHashMap<String, Long>()

    fun recordOutgoingReply(packageName: String, senderName: String, replyText: String) {
        val convKey = conversationKey(packageName, senderName)
        recordOutgoingReplyByKey(convKey, replyText)
    }

    private fun recordOutgoingReplyByKey(convKey: String, replyText: String) {
        val now = clockMs()
        lastReplyTimestamps[convKey] = now
        val clean = normalizeForComparison(replyText)
        if (clean.isNotEmpty()) {
            lastSentReplies[convKey] = SentEcho(clean, now)
        }
    }

    suspend fun process(payload: NotificationPayload): CoordinatorResult {
        val cleanSbnKey = payload.sbnKey.trim().ifEmpty {
            "${payload.packageName}|${payload.senderName}"
        }
        val msgKey = IdempotencyGuard.deriveMsgKey(
            packageName = payload.packageName,
            sbnKey = cleanSbnKey,
            text = payload.messageText
        )
        val now = clockMs()
        val convKey = ConversationKey.build(
            packageName = payload.packageName,
            senderName = payload.senderName,
            phone = payload.phone,
            groupName = payload.groupName,
            isGroup = payload.isGroup
        )
        val settings = repository.getCurrentSettings()
        val serverConfig = serverRepo.get()
        val webhookEnabled = serverConfig?.isEnabled == true
        val saEnabled = smartAdvisorPrefs.enabled
        val aiSettings = aiSettingsPrefs?.current()
        val aiEnabled = aiSettings?.enabled == true
        val webhookStandaloneEligible = webhookEnabled && !aiEnabled &&
            (serverConfig?.effectiveWebhookMode() == WebhookMode.STANDALONE_FALLBACK)
        val saStandaloneEligible = saEnabled && !aiEnabled

        // 1. Any path enabled? (No reservation, no token)
        val localActive = (settings.isAutoReplyEnabled || settings.isDrivingModeEnabled || aiEnabled) &&
            (payload.packageName in settings.enabledPackages)
        val externalEligible = (webhookStandaloneEligible || saStandaloneEligible) &&
            (payload.packageName in settings.enabledPackages || payload.replyAction != null)

        if (!localActive && !externalEligible) {
            recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_DISABLED, "التطبيق أو الرد التلقائي معطل", now)
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_DISABLED)
        }

        // 2. Echo & Summary check (No reservation, no token)
        val normalizedIncoming = normalizeForComparison(payload.messageText)
        if (isSummaryText(normalizedIncoming)) {
            recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_SELF, "تجاهل إشعار ملخص متعدد", now)
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_SELF)
        }
        val recentEcho = lastSentReplies[convKey]
        if (recentEcho != null &&
            (now - recentEcho.timestampMs) <= ECHO_WINDOW_MS &&
            recentEcho.normalizedText == normalizedIncoming
        ) {
            recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_SELF, "تجاهل صدى رد سابق", now)
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_SELF)
        }

        // 3. Policy check: Contacts / Group / Private (No reservation, no token)
        if (!autoReplyEngine.isContactAllowed(payload.senderName, settings.contactFilterMode)) {
            recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_POLICY, "تم التخطي حسب فلتر جهات الاتصال", now)
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_POLICY)
        }

        val groupMode = serverConfig?.let {
            runCatching { GroupReplyMode.valueOf(it.sendOnGroup) }.getOrDefault(GroupReplyMode.KEYWORD_ONLY)
        } ?: GroupReplyMode.KEYWORD_ONLY

        val groupBlocked = payload.isGroup && (
            (localActive && !settings.replyInGroups) ||
                (!localActive && webhookStandaloneEligible && groupMode == GroupReplyMode.NEVER) ||
                (!localActive && !webhookStandaloneEligible && saStandaloneEligible && !settings.replyInGroups)
            )
        val privateBlocked = !payload.isGroup && !localActive && webhookStandaloneEligible &&
            serverConfig?.sendOnPrivate == false && !saStandaloneEligible

        if (groupBlocked || privateBlocked) {
            recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_POLICY, "تم التخطي حسب سياسة المجموعات والخاص", now)
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_POLICY)
        }

        // 4. Conversation Cooldown check (No reservation, no token)
        val cooldownMs = settings.cooldownMinutes * 60_000L
        val lastSentAt = lastReplyTimestamps[convKey]
        if (cooldownMs > 0L && lastSentAt != null && (now - lastSentAt) < cooldownMs) {
            recordSkip(
                payload,
                msgKey,
                convKey,
                MessageQueueWorker.STATUS_SKIPPED_COOLDOWN,
                "ضمن فترة التهدئة للمحادثة (${settings.cooldownMinutes} دقيقة)",
                now
            )
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_COOLDOWN)
        }

        // 5. Atomic Reservation (msgKey)
        val effectiveAttempts = when {
            webhookEnabled && serverConfig != null -> serverConfig.retryCount
            saEnabled -> smartAdvisorPrefs.maxAttempts
            else -> HttpTransport.DEFAULT_MAX_ATTEMPTS
        }
        val effectiveTimeoutSeconds = when {
            webhookEnabled && serverConfig != null -> serverConfig.timeoutSeconds
            saEnabled -> smartAdvisorPrefs.timeoutSeconds
            else -> HttpTransport.DEFAULT_TIMEOUT_SECONDS
        }
        val leaseMs = computeLeaseDurationMs(
            maxAttempts = effectiveAttempts,
            timeoutSeconds = effectiveTimeoutSeconds
        )
        if (!idempotencyGuard.tryAcquireReservation(msgKey, leaseDurationMs = leaseMs)) {
            diagnosticLogger?.log(
                tag = DiagnosticEventTag.DUPLICATE_BLOCKED,
                conversationId = convKey,
                messageId = msgKey,
                detectedStatus = MessageProcessingStatus.DUPLICATE_MESSAGE.name,
                decision = "IDEMPOTENCY_RESERVATION_DENIED",
                errorSource = "IdempotencyGuard"
            )
            recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_DEDUP, "رسالة مكررة أو قيد المعالجة", now)
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_DEDUP)
        }

        // 6. Shared TokenBucketRateLimiter -> Release lease as EXPIRED on failure
        if (!rateLimiter.tryAcquire()) {
            idempotencyGuard.releaseReservation(msgKey)
            recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_RATE, "تجاوز الحد الأقصى للمعدل (10/دقيقة)", now)
            return CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_RATE)
        }

        // 7. Unified Engine path -> External webhook/standalone SA ONLY on NoMatch
        val incomingForEngine = IncomingNotificationMessage(
            packageName = payload.packageName,
            senderName = payload.senderName,
            messageText = payload.messageText,
            isGroup = payload.isGroup,
            replyAction = payload.replyAction,
            contentIntent = payload.contentIntent,
            receivedAtMs = now,
            msgKey = msgKey,
            directSendInvoker = payload.directSendInvoker,
            contentSendInvoker = payload.contentSendInvoker,
            rawTitle = payload.rawTitle,
            phone = payload.phone,
            groupName = payload.groupName
        )

        return when (val outcome = autoReplyEngine.processIncomingMessage(incomingForEngine, skipPreChecks = true)) {
            is LocalOutcome.Handled -> {
                recordOutgoingReplyByKey(convKey, outcome.replyText)
                if (!outcome.isHandoff) {
                    idempotencyGuard.markCompleted(msgKey)
                }
                CoordinatorResult(msgKey, outcome.deliveryMethod, outcome.replyText, isHandled = true)
            }
            LocalOutcome.BlockedDisabled -> {
                idempotencyGuard.releaseReservation(msgKey)
                recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_DISABLED, "تم تعطيل الرد أثناء المهلة", now)
                CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_DISABLED)
            }
            LocalOutcome.BlockedPolicy -> {
                idempotencyGuard.releaseReservation(msgKey)
                recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_POLICY, "محجوب بالسياسة", now)
                CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_POLICY)
            }
            LocalOutcome.BlockedCooldown -> {
                idempotencyGuard.releaseReservation(msgKey)
                recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_COOLDOWN, "محجوب بالتهدئة", now)
                CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_COOLDOWN)
            }
            LocalOutcome.BlockedRate -> {
                idempotencyGuard.releaseReservation(msgKey)
                recordSkip(payload, msgKey, convKey, MessageQueueWorker.STATUS_SKIPPED_RATE, "محجوب بحد المعدل", now)
                CoordinatorResult(msgKey, MessageQueueWorker.STATUS_SKIPPED_RATE)
            }
            is LocalOutcome.SendFailedConfirmed -> {
                idempotencyGuard.markFailedConfirmed(msgKey)
                CoordinatorResult(msgKey, MessageQueueWorker.STATUS_FAILED_CONFIRMED)
            }
            is LocalOutcome.SendDeliveryUnknown -> {
                idempotencyGuard.markDeliveryUnknown(msgKey)
                CoordinatorResult(msgKey, MessageQueueWorker.STATUS_DELIVERY_UNKNOWN)
            }
            is LocalOutcome.SuppressedUnunderstood -> {
                idempotencyGuard.markCompleted(msgKey)
                diagnosticLogger?.log(
                    tag = DiagnosticEventTag.REPLY_SUPPRESSED,
                    conversationId = convKey,
                    messageId = msgKey,
                    detectedStatus = outcome.statusLabel,
                    decision = "NOTIFICATION_SUPPRESSED_COMPLETED",
                    errorSource = "NotificationCoordinator"
                )
                CoordinatorResult(msgKey, "REPLY_SUPPRESSED", replyText = "", isHandled = false)
            }
            LocalOutcome.NoMatch -> {
                handleExternalFallback(
                    payload = payload,
                    msgKey = msgKey,
                    convKey = convKey,
                    serverConfig = serverConfig,
                    webhookEnabled = webhookStandaloneEligible,
                    saEnabled = saStandaloneEligible,
                    useAccessibilityFallback = settings.useAccessibilityFallback,
                    now = now
                )
            }
        }
    }

    private suspend fun handleExternalFallback(
        payload: NotificationPayload,
        msgKey: String,
        convKey: String,
        serverConfig: com.example.data.local.entity.ServerConfigEntity?,
        webhookEnabled: Boolean,
        saEnabled: Boolean,
        useAccessibilityFallback: Boolean,
        now: Long
    ): CoordinatorResult {
        if (!webhookEnabled && !saEnabled) {
            idempotencyGuard.releaseReservation(msgKey)
            return CoordinatorResult(msgKey, "NO_MATCH")
        }

        val msg = IncomingMessage(
            app = payload.packageName,
            sender = payload.senderName,
            senderName = payload.senderName,
            message = payload.messageText,
            phone = payload.phone,
            groupName = payload.groupName.orEmpty(),
            isGroup = payload.isGroup,
            notificationId = payload.notificationId,
            timestamp = now
        )

        val result = when {
            webhookEnabled && serverConfig != null -> webhookClient.send(
                config = serverConfig,
                msg = msg,
                idempotencyKey = msgKey
            )
            saEnabled -> {
                val excludeIds = conversationMemoryManager?.getShownProductIds(convKey).orEmpty()
                smartAdvisorClient.send(
                    msg = msg,
                    idempotencyKey = msgKey,
                    excludeProductIds = excludeIds
                )
            }
            else -> {
                idempotencyGuard.releaseReservation(msgKey)
                return CoordinatorResult(msgKey, "NO_MATCH")
            }
        }

        return when (result) {
            is HttpTransport.Result.Success -> {
                val replyKey = if (webhookEnabled && serverConfig != null) {
                    serverConfig.responseReplyKey
                } else {
                    "reply"
                }
                val parsedSa = if (!webhookEnabled && saEnabled) {
                    SmartAdvisorResponseParser.parseReplyEndpoint(
                        body = result.body,
                        preferredReplyKey = replyKey
                    )
                } else {
                    null
                }
                if (parsedSa != null) {
                    val shownIds = parsedSa.products.mapNotNull { it.productId }
                    if (shownIds.isNotEmpty()) {
                        conversationMemoryManager?.recordShownProductIds(convKey, shownIds)
                    }
                }

                val isProductImage = parsedSa?.responseType == ResponseType.PRODUCT_IMAGE &&
                    parsedSa.selectedProduct?.canSendAsImage() == true
                val selectedProduct = if (isProductImage) parsedSa?.selectedProduct else null
                val reply = if (isProductImage && selectedProduct != null) {
                    selectedProduct.buildCaption()
                } else {
                    parsedSa?.replyText?.takeIf { it.isNotBlank() }
                        ?: VariableEngine.extractReply(result.body, replyKey)
                }

                if (reply.isNullOrBlank() && selectedProduct == null) {
                    idempotencyGuard.markCompleted(msgKey)
                    CoordinatorResult(msgKey, "WEBHOOK_NO_REPLY")
                } else {
                    val textFallback = if (selectedProduct != null) {
                        parsedSa?.replyText?.takeIf { it.isNotBlank() }
                            ?: selectedProduct.buildTextFallbackReply()
                    } else {
                        reply.orEmpty()
                    }
                    val loggedReply = if (selectedProduct != null) {
                        "[صورة منتج]\n${selectedProduct.buildCaption()}"
                    } else {
                        reply.orEmpty()
                    }

                    val sendOutcome = if (selectedProduct != null) {
                        replyDispatcher.dispatchProductReply(
                            msgKey = msgKey,
                            targetPackage = payload.packageName,
                            senderName = payload.senderName,
                            responseType = ResponseType.PRODUCT_IMAGE,
                            productMessage = selectedProduct,
                            fallbackTextReply = textFallback,
                            replyAction = payload.replyAction,
                            contentIntent = payload.contentIntent,
                            useAccessibilityFallback = useAccessibilityFallback,
                            directMethodLabel = "SmartAdvisor-DirectReply",
                            accessibilityTextMethodLabel = "SmartAdvisor-Accessibility",
                            accessibilityImageMethodLabel = "SmartAdvisor-ProductImage",
                            directSendInvoker = payload.directSendInvoker,
                            contentSendInvoker = payload.contentSendInvoker
                        )
                    } else {
                        replyDispatcher.dispatchReply(
                            msgKey = msgKey,
                            targetPackage = payload.packageName,
                            senderName = payload.senderName,
                            replyText = textFallback,
                            replyAction = payload.replyAction,
                            contentIntent = payload.contentIntent,
                            useAccessibilityFallback = useAccessibilityFallback,
                            directMethodLabel = "Webhook-DirectReply",
                            accessibilityMethodLabel = "Webhook-Accessibility",
                            directSendInvoker = payload.directSendInvoker,
                            contentSendInvoker = payload.contentSendInvoker
                        )
                    }
                    when (sendOutcome) {
                        is ReplyDispatcher.SendOutcome.Sent -> {
                            recordOutgoingReplyByKey(convKey, loggedReply)
                            if (!sendOutcome.isHandoff) {
                                idempotencyGuard.markCompleted(msgKey)
                            }
                            repository.recordReplyLog(
                                ReplyLogEntity(
                                    senderName = payload.senderName,
                                    packageName = payload.packageName,
                                    incomingMessage = payload.messageText,
                                    sentReply = loggedReply,
                                    isGroup = payload.isGroup,
                                    deliveryMethod = sendOutcome.deliveryMethod,
                                    timestamp = now,
                                    msgKey = msgKey,
                                    conversationKey = convKey,
                                    sourceCategory = if (webhookEnabled) {
                                        ReplySourceCategory.WEBHOOK.name
                                    } else {
                                        ReplySourceCategory.SMART_ADVISOR.name
                                    }
                                )
                            )
                            CoordinatorResult(msgKey, sendOutcome.deliveryMethod, loggedReply, isHandled = true)
                        }
                        is ReplyDispatcher.SendOutcome.FailedConfirmed -> {
                            idempotencyGuard.markFailedConfirmed(msgKey)
                            repository.recordReplyLog(
                                ReplyLogEntity(
                                    senderName = payload.senderName,
                                    packageName = payload.packageName,
                                    incomingMessage = payload.messageText,
                                    sentReply = sendOutcome.error,
                                    isGroup = payload.isGroup,
                                    deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED,
                                    timestamp = now,
                                    msgKey = msgKey,
                                    conversationKey = convKey
                                )
                            )
                            CoordinatorResult(msgKey, MessageQueueWorker.STATUS_FAILED_CONFIRMED)
                        }
                        is ReplyDispatcher.SendOutcome.DeliveryUnknown -> {
                            idempotencyGuard.markDeliveryUnknown(msgKey)
                            repository.recordReplyLog(
                                ReplyLogEntity(
                                    senderName = payload.senderName,
                                    packageName = payload.packageName,
                                    incomingMessage = payload.messageText,
                                    sentReply = sendOutcome.error,
                                    isGroup = payload.isGroup,
                                    deliveryMethod = MessageQueueWorker.STATUS_DELIVERY_UNKNOWN,
                                    timestamp = now,
                                    msgKey = msgKey,
                                    conversationKey = convKey
                                )
                            )
                            CoordinatorResult(msgKey, MessageQueueWorker.STATUS_DELIVERY_UNKNOWN)
                        }
                    }
                }
            }
            is HttpTransport.Result.Conflict -> {
                idempotencyGuard.markDeliveryUnknown(msgKey)
                repository.recordReplyLog(
                    ReplyLogEntity(
                        senderName = payload.senderName,
                        packageName = payload.packageName,
                        incomingMessage = payload.messageText,
                        sentReply = "تعارض من الخادم (HTTP 409): الطلب قيد التنفيذ",
                        isGroup = payload.isGroup,
                        deliveryMethod = MessageQueueWorker.STATUS_DELIVERY_UNKNOWN,
                        timestamp = now,
                        msgKey = msgKey,
                        conversationKey = convKey
                    )
                )
                CoordinatorResult(msgKey, MessageQueueWorker.STATUS_DELIVERY_UNKNOWN)
            }
            is HttpTransport.Result.Failure -> {
                when (result.kind) {
                    HttpTransport.FailureKind.DELIVERY_UNKNOWN -> {
                        idempotencyGuard.markDeliveryUnknown(msgKey)
                        repository.recordReplyLog(
                            ReplyLogEntity(
                                senderName = payload.senderName,
                                packageName = payload.packageName,
                                incomingMessage = payload.messageText,
                                sentReply = result.error,
                                isGroup = payload.isGroup,
                                deliveryMethod = MessageQueueWorker.STATUS_DELIVERY_UNKNOWN,
                                timestamp = now,
                                msgKey = msgKey,
                                conversationKey = convKey
                            )
                        )
                        CoordinatorResult(msgKey, MessageQueueWorker.STATUS_DELIVERY_UNKNOWN)
                    }
                    HttpTransport.FailureKind.NON_RETRYABLE -> {
                        idempotencyGuard.markFailedConfirmed(msgKey)
                        repository.recordReplyLog(
                            ReplyLogEntity(
                                senderName = payload.senderName,
                                packageName = payload.packageName,
                                incomingMessage = payload.messageText,
                                sentReply = result.error,
                                isGroup = payload.isGroup,
                                deliveryMethod = MessageQueueWorker.STATUS_PERMANENTLY_FAILED,
                                timestamp = now,
                                msgKey = msgKey,
                                conversationKey = convKey
                            )
                        )
                        CoordinatorResult(msgKey, MessageQueueWorker.STATUS_PERMANENTLY_FAILED)
                    }
                    HttpTransport.FailureKind.FAILED_CONFIRMED,
                    HttpTransport.FailureKind.RATE_LIMITED -> {
                        idempotencyGuard.markFailedConfirmed(msgKey)
                        repository.recordReplyLog(
                            ReplyLogEntity(
                                senderName = payload.senderName,
                                packageName = payload.packageName,
                                incomingMessage = payload.messageText,
                                sentReply = result.error,
                                isGroup = payload.isGroup,
                                deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED,
                                timestamp = now,
                                retryCount = 0,
                                msgKey = msgKey,
                                conversationKey = convKey
                            )
                        )
                        scheduleQueueRetry(result.retryAfterMs ?: DEFAULT_QUEUE_DELAY_MS)
                        CoordinatorResult(msgKey, MessageQueueWorker.STATUS_FAILED_CONFIRMED)
                    }
                }
            }
        }
    }

    private suspend fun recordSkip(
        payload: NotificationPayload,
        msgKey: String,
        convKey: String,
        deliveryMethod: String,
        reason: String,
        now: Long
    ) {
        repository.recordReplyLog(
            ReplyLogEntity(
                senderName = payload.senderName,
                packageName = payload.packageName,
                incomingMessage = payload.messageText,
                sentReply = reason,
                isGroup = payload.isGroup,
                deliveryMethod = deliveryMethod,
                timestamp = now,
                msgKey = msgKey,
                conversationKey = convKey
            )
        )
    }

    private fun scheduleQueueRetry(delayMs: Long) {
        val customScheduler = queueScheduler
        if (customScheduler != null) {
            customScheduler.invoke(delayMs)
            return
        }
        val request = OneTimeWorkRequestBuilder<MessageQueueWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            MessageQueueWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun computeLeaseDurationMs(maxAttempts: Int, timeoutSeconds: Int): Long {
        val safeAttempts = maxAttempts.coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS)
        val safeTimeoutSeconds = timeoutSeconds.coerceIn(
            HttpTransport.MIN_TIMEOUT_SECONDS,
            HttpTransport.MAX_TIMEOUT_SECONDS
        )
        val safeTimeoutMs = safeTimeoutSeconds * 1_000L
        val backoffSum = HttpTransport.totalBackoffMs(safeAttempts)
        val leaseMs = maxOf(
            IdempotencyGuard.DEFAULT_LEASE_MS,
            safeAttempts.toLong() * safeTimeoutMs + backoffSum + 10_000L
        )
        IdempotencyGuard.validateLeaseDuration(
            leaseDurationMs = leaseMs,
            maxAttempts = safeAttempts,
            callTimeoutMs = safeTimeoutMs,
            totalBackoffMs = backoffSum
        )
        return leaseMs
    }

    companion object {
        const val ECHO_WINDOW_MS = 2L * 60_000L
        const val DEFAULT_QUEUE_DELAY_MS = 5L * 60_000L

        private val BIDI_CONTROL_REGEX = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
        private val SUMMARY_REGEX = Regex(
            """^(?:لديك\s+)?\d+\s+(?:رسائل|رسالة)\s+جديدة$|^\d+\s+new\s+messages?$|^\d+\s+unread\s+messages?$""",
            RegexOption.IGNORE_CASE
        )

        fun conversationKey(packageName: String, senderName: String): String =
            "${packageName.trim()}:${senderName.trim().lowercase()}"

        fun normalizeForComparison(text: String): String {
            val cleaned = text.replace(BIDI_CONTROL_REGEX, "")
            return IdempotencyGuard.normalizeText(cleaned)
        }

        fun isSummaryText(normalizedText: String): Boolean {
            return normalizedText.isNotEmpty() && SUMMARY_REGEX.matches(normalizedText)
        }
    }
}
