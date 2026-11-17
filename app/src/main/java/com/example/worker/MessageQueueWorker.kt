package com.example.worker

import android.app.PendingIntent
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.dao.DedupKeyDao
import com.example.data.local.dao.ReplyLogDao
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.IdempotencyGuard
import com.example.engine.IncomingMessage
import com.example.engine.ReplyDispatcher
import com.example.engine.VariableEngine
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.smartadvisor.SmartAdvisorClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MessageQueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val logDao: ReplyLogDao,
    private val dedupKeyDao: DedupKeyDao,
    private val idempotencyGuard: IdempotencyGuard,
    private val serverConfigRepository: ServerConfigRepository,
    private val webhookClient: WebhookClient,
    private val smartAdvisorClient: SmartAdvisorClient,
    private val smartAdvisorPrefs: SmartAdvisorPrefs,
    private val replyDispatcher: ReplyDispatcher
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        executeQueueRetryWithDispatcher(
            logDao = logDao,
            dedupKeyDao = dedupKeyDao,
            idempotencyGuard = idempotencyGuard,
            serverConfigRepository = serverConfigRepository,
            webhookClient = webhookClient,
            smartAdvisorClient = smartAdvisorClient,
            saEnabled = smartAdvisorPrefs.enabled,
            replyDispatcher = replyDispatcher,
            context = applicationContext
        )
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "automate_message_queue_retry"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_FAILED_CONFIRMED = "FAILED_CONFIRMED"
        const val STATUS_DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN"
        const val STATUS_IN_FLIGHT = "IN_FLIGHT"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_PERMANENTLY_FAILED = "PERMANENTLY_FAILED"
        const val STATUS_SKIPPED_DISABLED = "SKIPPED_DISABLED"
        const val STATUS_SKIPPED_SELF = "SKIPPED_SELF"
        const val STATUS_SKIPPED_POLICY = "SKIPPED_POLICY"
        const val STATUS_SKIPPED_COOLDOWN = "SKIPPED_COOLDOWN"
        const val STATUS_SKIPPED_DEDUP = "SKIPPED_DEDUP"
        const val STATUS_SKIPPED_RATE = "SKIPPED_RATE"
        const val MAX_RETRIES = 3

        internal suspend fun executeQueueRetryWithDispatcher(
            logDao: ReplyLogDao,
            dedupKeyDao: DedupKeyDao,
            idempotencyGuard: IdempotencyGuard,
            serverConfigRepository: ServerConfigRepository,
            webhookClient: WebhookClient,
            smartAdvisorClient: SmartAdvisorClient,
            saEnabled: Boolean,
            replyDispatcher: ReplyDispatcher,
            context: Context
        ) {
            executeQueueRetryInternal(
                logDao = logDao,
                serverConfigRepository = serverConfigRepository,
                webhookClient = webhookClient,
                smartAdvisorClient = smartAdvisorClient,
                saEnabled = saEnabled,
                dedupKeyDao = dedupKeyDao,
                idempotencyGuard = idempotencyGuard,
                dispatchRecovered = { log, reply ->
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(log.packageName)
                    val contentIntent = launchIntent?.let {
                        PendingIntent.getActivity(
                            context,
                            log.id.toInt().and(0x7FFFFFFF),
                            it,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    }
                    val outcome = replyDispatcher.dispatchReply(
                        msgKey = log.msgKey,
                        targetPackage = log.packageName,
                        senderName = log.senderName,
                        replyText = reply,
                        replyAction = null,
                        contentIntent = contentIntent,
                        useAccessibilityFallback = true,
                        directMethodLabel = STATUS_SUCCESS,
                        accessibilityMethodLabel = "Accessibility"
                    )
                    when (outcome) {
                        is ReplyDispatcher.SendOutcome.Sent -> {
                            if (!outcome.isHandoff && log.msgKey.isNotBlank()) {
                                idempotencyGuard.markCompleted(log.msgKey)
                            }
                            logDao.updateLog(
                                log.copy(
                                    deliveryMethod = outcome.deliveryMethod,
                                    retryCount = log.retryCount + 1,
                                    sentReply = reply
                                )
                            )
                        }
                        is ReplyDispatcher.SendOutcome.FailedConfirmed -> {
                            if (log.msgKey.isNotBlank()) {
                                idempotencyGuard.markFailedConfirmed(log.msgKey)
                            }
                            logDao.updateLog(
                                log.copy(
                                    deliveryMethod = STATUS_FAILED_CONFIRMED,
                                    retryCount = log.retryCount + 1,
                                    sentReply = outcome.error
                                )
                            )
                        }
                        is ReplyDispatcher.SendOutcome.DeliveryUnknown -> {
                            if (log.msgKey.isNotBlank()) {
                                idempotencyGuard.markDeliveryUnknown(log.msgKey)
                            }
                            logDao.updateLog(
                                log.copy(
                                    deliveryMethod = STATUS_DELIVERY_UNKNOWN,
                                    retryCount = log.retryCount + 1,
                                    sentReply = outcome.error
                                )
                            )
                        }
                    }
                }
            )
        }

        internal suspend fun executeQueueRetry(
            logDao: ReplyLogDao,
            serverConfigRepository: ServerConfigRepository,
            webhookClient: WebhookClient,
            smartAdvisorClient: SmartAdvisorClient,
            saEnabled: Boolean,
            dedupKeyDao: DedupKeyDao? = null,
            idempotencyGuard: IdempotencyGuard? = null,
            onReplyRecovered: (packageName: String, senderName: String, reply: String) -> Unit
        ) {
            executeQueueRetryInternal(
                logDao = logDao,
                serverConfigRepository = serverConfigRepository,
                webhookClient = webhookClient,
                smartAdvisorClient = smartAdvisorClient,
                saEnabled = saEnabled,
                dedupKeyDao = dedupKeyDao,
                idempotencyGuard = idempotencyGuard,
                dispatchRecovered = { log, recoveredReply ->
                    val msgKey = log.msgKey.trim()
                    if (msgKey.isNotEmpty()) {
                        idempotencyGuard?.markCompleted(msgKey)
                    }
                    logDao.updateLog(
                        log.copy(
                            deliveryMethod = STATUS_SUCCESS,
                            retryCount = log.retryCount + 1,
                            sentReply = recoveredReply
                        )
                    )
                    onReplyRecovered(log.packageName, log.senderName, recoveredReply)
                }
            )
        }

        private suspend fun executeQueueRetryInternal(
            logDao: ReplyLogDao,
            serverConfigRepository: ServerConfigRepository,
            webhookClient: WebhookClient,
            smartAdvisorClient: SmartAdvisorClient,
            saEnabled: Boolean,
            dedupKeyDao: DedupKeyDao?,
            idempotencyGuard: IdempotencyGuard?,
            dispatchRecovered: suspend (log: ReplyLogEntity, reply: String) -> Unit
        ) {
            val pendingFailedLogs = logDao.getFailedLogsForRetry(
                deliveryMethod = STATUS_FAILED,
                maxRetries = MAX_RETRIES
            )
            if (pendingFailedLogs.isEmpty()) return

            val serverConfig = serverConfigRepository.get()

            for (log in pendingFailedLogs) {
                val msgKey = log.msgKey.trim()

                // Guard via dedup_keys state before retrying: never retry COMPLETED, PROCESSING, or DELIVERY_UNKNOWN
                if (msgKey.isNotEmpty() && dedupKeyDao != null) {
                    val dedupEntry = dedupKeyDao.getByKey(msgKey)
                    if (dedupEntry != null) {
                        when (dedupEntry.state) {
                            DedupKeyEntity.STATE_COMPLETED -> {
                                logDao.updateLog(
                                    log.copy(
                                        deliveryMethod = STATUS_SUCCESS,
                                        sentReply = if (log.sentReply.isBlank()) "مكتمل مسبقاً" else log.sentReply
                                    )
                                )
                                continue
                            }
                            DedupKeyEntity.STATE_PROCESSING -> {
                                continue
                            }
                            DedupKeyEntity.STATE_DELIVERY_UNKNOWN -> {
                                logDao.updateLog(log.copy(deliveryMethod = STATUS_DELIVERY_UNKNOWN))
                                continue
                            }
                        }
                    }

                    if (idempotencyGuard != null && !idempotencyGuard.tryAcquireReservation(msgKey)) {
                        continue
                    }
                }

                val incoming = IncomingMessage(
                    app = log.packageName,
                    sender = log.senderName,
                    senderName = log.senderName,
                    message = log.incomingMessage,
                    phone = "",
                    groupName = "",
                    isGroup = log.isGroup,
                    notificationId = "queue-retry-${log.id}"
                )

                var recoveredReply: String? = null
                var outcomeKind: HttpTransport.FailureKind? = null
                var conflictDetected = false
                var errorMessage: String? = null

                if (serverConfig != null && serverConfig.isEnabled) {
                    val res = webhookClient.send(
                        config = serverConfig,
                        msg = incoming,
                        idempotencyKey = msgKey.ifEmpty { null }
                    )
                    when (res) {
                        is HttpTransport.Result.Success -> {
                            recoveredReply = VariableEngine.extractReply(
                                res.body,
                                serverConfig.responseReplyKey
                            )
                        }
                        is HttpTransport.Result.Conflict -> {
                            conflictDetected = true
                            errorMessage = "تعارض من الخادم (HTTP 409)"
                        }
                        is HttpTransport.Result.Failure -> {
                            outcomeKind = res.kind
                            errorMessage = res.error
                        }
                    }
                } else if (saEnabled) {
                    val saRes = smartAdvisorClient.send(
                        msg = incoming,
                        idempotencyKey = msgKey.ifEmpty { null }
                    )
                    when (saRes) {
                        is HttpTransport.Result.Success -> {
                            recoveredReply = VariableEngine.extractReply(saRes.body, "reply")
                        }
                        is HttpTransport.Result.Conflict -> {
                            conflictDetected = true
                            errorMessage = "تعارض من الخادم (HTTP 409)"
                        }
                        is HttpTransport.Result.Failure -> {
                            outcomeKind = saRes.kind
                            errorMessage = saRes.error
                        }
                    }
                }

                if (!recoveredReply.isNullOrBlank()) {
                    dispatchRecovered(log, recoveredReply)
                } else if (conflictDetected || outcomeKind == HttpTransport.FailureKind.DELIVERY_UNKNOWN) {
                    if (msgKey.isNotEmpty()) {
                        idempotencyGuard?.markDeliveryUnknown(msgKey)
                    }
                    logDao.updateLog(
                        log.copy(
                            deliveryMethod = STATUS_DELIVERY_UNKNOWN,
                            retryCount = log.retryCount + 1,
                            sentReply = errorMessage ?: log.sentReply
                        )
                    )
                } else if (outcomeKind == HttpTransport.FailureKind.NON_RETRYABLE) {
                    if (msgKey.isNotEmpty()) {
                        idempotencyGuard?.markFailedConfirmed(msgKey)
                    }
                    logDao.updateLog(
                        log.copy(
                            deliveryMethod = STATUS_PERMANENTLY_FAILED,
                            retryCount = log.retryCount + 1,
                            sentReply = errorMessage ?: log.sentReply
                        )
                    )
                } else {
                    val nextAttempt = log.retryCount + 1
                    val nextStatus = if (nextAttempt >= MAX_RETRIES) {
                        STATUS_PERMANENTLY_FAILED
                    } else {
                        if (log.deliveryMethod == STATUS_FAILED) STATUS_FAILED else STATUS_FAILED_CONFIRMED
                    }
                    if (msgKey.isNotEmpty()) {
                        idempotencyGuard?.markFailedConfirmed(msgKey)
                    }
                    logDao.updateLog(
                        log.copy(
                            deliveryMethod = nextStatus,
                            retryCount = nextAttempt,
                            sentReply = errorMessage ?: log.sentReply
                        )
                    )
                }
            }
        }
    }
}
