package com.example.ui.viewmodel

import android.app.PendingIntent
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.worker.MessageQueueWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LogFilter(val labelAr: String) {
    ALL("الكل"),
    SUCCESS("ناجحة"),
    FAILED("فاشلة"),
    SKIPPED("متخطاة"),
    WHATSAPP("WhatsApp"),
    TELEGRAM("Telegram")
}

data class RedactedLogItem(
    val id: Long,
    val senderName: String,
    val packageName: String,
    val incomingMessage: String,
    val sentReply: String,
    val isGroup: Boolean,
    val deliveryMethod: String,
    val timestamp: Long,
    val retryCount: Int,
    val msgKey: String = "",
    val isSuccess: Boolean,
    val isSkipped: Boolean = false,
    val isDeliveryUnknown: Boolean = false
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val replyLogDao: ReplyLogDao,
    private val secretVault: SecretVault,
    private val serverConfigRepository: ServerConfigRepository,
    private val webhookClient: WebhookClient,
    private val smartAdvisorClient: SmartAdvisorClient,
    private val smartAdvisorPrefs: SmartAdvisorPrefs,
    private val idempotencyGuard: IdempotencyGuard,
    private val replyDispatcher: ReplyDispatcher
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(LogFilter.ALL)
    val selectedFilter: StateFlow<LogFilter> = _selectedFilter.asStateFlow()

    private val _retryingLogIds = MutableStateFlow<Set<Long>>(emptySet())
    val retryingLogIds: StateFlow<Set<Long>> = _retryingLogIds.asStateFlow()

    val filteredLogs: StateFlow<List<RedactedLogItem>> = combine(
        replyLogDao.observeAllLogs(),
        _searchQuery,
        _selectedFilter
    ) { logs, query, filter ->
        val cleanQuery = query.trim()
        logs.asSequence()
            .filter { log -> matchesFilter(log, filter) }
            .filter { log ->
                cleanQuery.isEmpty() ||
                    log.senderName.contains(cleanQuery, ignoreCase = true) ||
                    log.incomingMessage.contains(cleanQuery, ignoreCase = true) ||
                    log.sentReply.contains(cleanQuery, ignoreCase = true)
            }
            .map { log ->
                val skipped = isSkippedMethod(log.deliveryMethod)
                val deliveryUnknown = isDeliveryUnknownMethod(log.deliveryMethod)
                val failed = isFailureMethod(log.deliveryMethod)
                RedactedLogItem(
                    id = log.id,
                    senderName = log.senderName,
                    packageName = log.packageName,
                    incomingMessage = SensitiveDataRedactor.scrub(log.incomingMessage, secretVault),
                    sentReply = SensitiveDataRedactor.scrub(log.sentReply, secretVault),
                    isGroup = log.isGroup,
                    deliveryMethod = log.deliveryMethod,
                    timestamp = log.timestamp,
                    retryCount = log.retryCount,
                    msgKey = log.msgKey,
                    isSuccess = !skipped && !deliveryUnknown && !failed,
                    isSkipped = skipped,
                    isDeliveryUnknown = deliveryUnknown
                )
            }
            .toList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectFilter(filter: LogFilter) {
        _selectedFilter.value = filter
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            replyLogDao.clearLogs()
        }
    }

    fun manualRetry(logId: Long) {
        if (logId in _retryingLogIds.value) return
        _retryingLogIds.value = _retryingLogIds.value + logId

        viewModelScope.launch {
            try {
                val log = replyLogDao.getById(logId) ?: return@launch
                val msgKey = log.msgKey.trim()
                if (msgKey.isNotBlank()) {
                    val entry = idempotencyGuard.getEntry(msgKey)
                    if (entry?.state == DedupKeyEntity.STATE_DELIVERY_UNKNOWN ||
                        entry?.sendState == DedupKeyEntity.SEND_DELIVERY_UNKNOWN
                    ) {
                        return@launch
                    }
                    if (!idempotencyGuard.tryAcquireReservation(msgKey)) {
                        return@launch
                    }
                }

                val serverConfig = serverConfigRepository.get()
                val saEnabled = smartAdvisorPrefs.enabled

                val incoming = IncomingMessage(
                    app = log.packageName,
                    sender = log.senderName,
                    senderName = log.senderName,
                    message = log.incomingMessage,
                    phone = "",
                    groupName = "",
                    isGroup = log.isGroup,
                    notificationId = "manual-retry-${log.id}"
                )

                var recoveredReply: String? = null
                var errorMessage: String? = null
                var deliveryUnknownFromNet = false

                if (serverConfig != null && serverConfig.isEnabled) {
                    val result = webhookClient.send(
                        config = serverConfig,
                        msg = incoming,
                        idempotencyKey = msgKey.ifEmpty { null }
                    )
                    when (result) {
                        is HttpTransport.Result.Success -> {
                            recoveredReply = VariableEngine.extractReply(
                                result.body,
                                serverConfig.responseReplyKey
                            )
                        }
                        is HttpTransport.Result.Conflict -> {
                            deliveryUnknownFromNet = true
                            errorMessage = "تعارض من الخادم (HTTP 409)"
                        }
                        is HttpTransport.Result.Failure -> {
                            deliveryUnknownFromNet = result.kind == HttpTransport.FailureKind.DELIVERY_UNKNOWN
                            errorMessage = result.error
                        }
                    }
                } else if (saEnabled) {
                    val result = smartAdvisorClient.send(
                        msg = incoming,
                        idempotencyKey = msgKey.ifEmpty { null }
                    )
                    when (result) {
                        is HttpTransport.Result.Success -> {
                            recoveredReply = VariableEngine.extractReply(result.body, "reply")
                        }
                        is HttpTransport.Result.Conflict -> {
                            deliveryUnknownFromNet = true
                            errorMessage = "تعارض من الخادم (HTTP 409)"
                        }
                        is HttpTransport.Result.Failure -> {
                            deliveryUnknownFromNet = result.kind == HttpTransport.FailureKind.DELIVERY_UNKNOWN
                            errorMessage = result.error
                        }
                    }
                } else {
                    if (msgKey.isNotBlank()) idempotencyGuard.releaseReservation(msgKey)
                    errorMessage = "لا يوجد خادم خارجي مفعّل لإعادة الإرسال"
                }

                if (!recoveredReply.isNullOrBlank()) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(log.packageName)
                    val contentIntent = launchIntent?.let {
                        PendingIntent.getActivity(
                            context,
                            log.id.toInt().and(0x7FFFFFFF),
                            it,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    }
                    val sendOutcome = replyDispatcher.dispatchReply(
                        msgKey = msgKey,
                        targetPackage = log.packageName,
                        senderName = log.senderName,
                        replyText = recoveredReply,
                        replyAction = null,
                        contentIntent = contentIntent,
                        useAccessibilityFallback = true,
                        directMethodLabel = MessageQueueWorker.STATUS_SUCCESS,
                        accessibilityMethodLabel = "Accessibility"
                    )
                    when (sendOutcome) {
                        is ReplyDispatcher.SendOutcome.Sent -> {
                            if (!sendOutcome.isHandoff && msgKey.isNotEmpty()) {
                                idempotencyGuard.markCompleted(msgKey)
                            }
                            replyLogDao.updateLog(
                                log.copy(
                                    deliveryMethod = sendOutcome.deliveryMethod,
                                    retryCount = log.retryCount + 1,
                                    sentReply = SensitiveDataRedactor.scrub(recoveredReply, secretVault)
                                )
                            )
                        }
                        is ReplyDispatcher.SendOutcome.FailedConfirmed -> {
                            if (msgKey.isNotEmpty()) idempotencyGuard.markFailedConfirmed(msgKey)
                            replyLogDao.updateLog(
                                log.copy(
                                    deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED,
                                    retryCount = log.retryCount + 1,
                                    sentReply = SensitiveDataRedactor.scrub(sendOutcome.error, secretVault)
                                )
                            )
                        }
                        is ReplyDispatcher.SendOutcome.DeliveryUnknown -> {
                            if (msgKey.isNotEmpty()) idempotencyGuard.markDeliveryUnknown(msgKey)
                            replyLogDao.updateLog(
                                log.copy(
                                    deliveryMethod = MessageQueueWorker.STATUS_DELIVERY_UNKNOWN,
                                    retryCount = log.retryCount + 1,
                                    sentReply = SensitiveDataRedactor.scrub(sendOutcome.error, secretVault)
                                )
                            )
                        }
                    }
                } else {
                    if (deliveryUnknownFromNet && msgKey.isNotBlank()) {
                        idempotencyGuard.markDeliveryUnknown(msgKey)
                    } else if (msgKey.isNotBlank()) {
                        idempotencyGuard.markFailedConfirmed(msgKey)
                    }
                    replyLogDao.updateLog(
                        log.copy(
                            deliveryMethod = if (deliveryUnknownFromNet) {
                                MessageQueueWorker.STATUS_DELIVERY_UNKNOWN
                            } else {
                                log.deliveryMethod
                            },
                            retryCount = log.retryCount + 1,
                            sentReply = SensitiveDataRedactor.scrub(errorMessage ?: log.sentReply, secretVault)
                        )
                    )
                }
            } finally {
                _retryingLogIds.value = _retryingLogIds.value - logId
            }
        }
    }

    private fun matchesFilter(log: ReplyLogEntity, filter: LogFilter): Boolean {
        return when (filter) {
            LogFilter.ALL -> true
            LogFilter.SUCCESS -> !isFailureMethod(log.deliveryMethod) &&
                !isDeliveryUnknownMethod(log.deliveryMethod) &&
                !isSkippedMethod(log.deliveryMethod)
            LogFilter.FAILED -> isFailureMethod(log.deliveryMethod) || isDeliveryUnknownMethod(log.deliveryMethod)
            LogFilter.SKIPPED -> isSkippedMethod(log.deliveryMethod)
            LogFilter.WHATSAPP -> log.packageName.contains("whatsapp", ignoreCase = true)
            LogFilter.TELEGRAM -> log.packageName.contains("telegram", ignoreCase = true)
        }
    }

    private fun isSkippedMethod(method: String): Boolean =
        method.startsWith("SKIPPED_")

    private fun isDeliveryUnknownMethod(method: String): Boolean =
        method == MessageQueueWorker.STATUS_DELIVERY_UNKNOWN ||
            method == MessageQueueWorker.STATUS_IN_FLIGHT

    private fun isFailureMethod(method: String): Boolean {
        return method == MessageQueueWorker.STATUS_FAILED ||
            method == MessageQueueWorker.STATUS_FAILED_CONFIRMED ||
            method == MessageQueueWorker.STATUS_PERMANENTLY_FAILED
    }
}
