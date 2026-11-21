package com.example.engine

import android.app.Notification
import android.app.PendingIntent
import android.net.Uri
import android.util.Log
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.ContactFilterMode
import com.example.data.repository.AutoMateRepository
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import com.example.service.AutoReplyAccessibilityService
import com.example.worker.FollowUpScheduler
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingNotificationMessage(
    val packageName: String,
    val senderName: String,
    val messageText: String,
    val isGroup: Boolean,
    val replyAction: Notification.Action?,
    val contentIntent: PendingIntent?,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val msgKey: String = "",
    val directSendInvoker: ((Notification.Action, String) -> Unit)? = null,
    val contentSendInvoker: ((PendingIntent) -> Unit)? = null,
    val rawTitle: String = senderName,
    val phone: String = "",
    val groupName: String? = null
)

sealed class LocalOutcome {
    data class Handled(
        val deliveryMethod: String,
        val replyText: String,
        val isHandoff: Boolean = false
    ) : LocalOutcome()
    data object NoMatch : LocalOutcome()
    data object BlockedDisabled : LocalOutcome()
    data object BlockedPolicy : LocalOutcome()
    data object BlockedCooldown : LocalOutcome()
    data object BlockedRate : LocalOutcome()
    data class SendFailedConfirmed(val error: String) : LocalOutcome()
    data class SendDeliveryUnknown(val error: String) : LocalOutcome()
    data class SuppressedUnunderstood(
        val reason: String = "",
        val statusLabel: String = "UNKNOWN_INTENT"
    ) : LocalOutcome()
}

@Singleton
class AutoReplyEngine internal constructor(
    private val repository: AutoMateRepository,
    private val pendingReplyCoordinator: PendingReplyCoordinator,
    private val menuReplyManager: MenuReplyManager,
    private val followUpScheduler: FollowUpScheduler,
    private val aiSettingsPrefs: AiSettingsPrefs,
    private val aiReplyEngine: AIReplyEngine,
    private val secretVault: SecretVault,
    private val replyDispatcher: ReplyDispatcher,
    private val conversationMemoryManager: ConversationMemoryManager? = null,
    private val smartCollaborationCoordinator: SmartCollaborationCoordinator? = null,
    private val injectedUnifiedService: UnifiedSmartReplyService? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val diagnosticLogger: SafeDiagnosticLogger = SafeDiagnosticLogger(secretVault, clockMs)
) {
    @Inject
    constructor(
        repository: AutoMateRepository,
        pendingReplyCoordinator: PendingReplyCoordinator,
        menuReplyManager: MenuReplyManager,
        followUpScheduler: FollowUpScheduler,
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        secretVault: SecretVault,
        replyDispatcher: ReplyDispatcher,
        conversationMemoryManager: ConversationMemoryManager,
        smartCollaborationCoordinator: SmartCollaborationCoordinator,
        unifiedSmartReplyService: UnifiedSmartReplyService,
        diagnosticLogger: SafeDiagnosticLogger
    ) : this(
        repository = repository,
        pendingReplyCoordinator = pendingReplyCoordinator,
        menuReplyManager = menuReplyManager,
        followUpScheduler = followUpScheduler,
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        secretVault = secretVault,
        replyDispatcher = replyDispatcher,
        conversationMemoryManager = conversationMemoryManager,
        smartCollaborationCoordinator = smartCollaborationCoordinator,
        injectedUnifiedService = unifiedSmartReplyService,
        clockMs = { System.currentTimeMillis() },
        diagnosticLogger = diagnosticLogger
    )

    constructor(
        repository: AutoMateRepository,
        pendingReplyCoordinator: PendingReplyCoordinator,
        menuReplyManager: MenuReplyManager,
        followUpScheduler: FollowUpScheduler,
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        secretVault: SecretVault,
        replyDispatcher: ReplyDispatcher,
        conversationMemoryManager: ConversationMemoryManager,
        smartCollaborationCoordinator: SmartCollaborationCoordinator,
        unifiedSmartReplyService: UnifiedSmartReplyService
    ) : this(
        repository = repository,
        pendingReplyCoordinator = pendingReplyCoordinator,
        menuReplyManager = menuReplyManager,
        followUpScheduler = followUpScheduler,
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        secretVault = secretVault,
        replyDispatcher = replyDispatcher,
        conversationMemoryManager = conversationMemoryManager,
        smartCollaborationCoordinator = smartCollaborationCoordinator,
        injectedUnifiedService = unifiedSmartReplyService,
        clockMs = { System.currentTimeMillis() }
    )

    constructor(
        repository: AutoMateRepository,
        pendingReplyCoordinator: PendingReplyCoordinator,
        menuReplyManager: MenuReplyManager,
        followUpScheduler: FollowUpScheduler,
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        secretVault: SecretVault,
        replyDispatcher: ReplyDispatcher,
        conversationMemoryManager: ConversationMemoryManager? = null,
        smartCollaborationCoordinator: SmartCollaborationCoordinator? = null
    ) : this(
        repository = repository,
        pendingReplyCoordinator = pendingReplyCoordinator,
        menuReplyManager = menuReplyManager,
        followUpScheduler = followUpScheduler,
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        secretVault = secretVault,
        replyDispatcher = replyDispatcher,
        conversationMemoryManager = conversationMemoryManager,
        smartCollaborationCoordinator = smartCollaborationCoordinator,
        injectedUnifiedService = null
    )

    constructor(
        repository: AutoMateRepository,
        pendingReplyCoordinator: PendingReplyCoordinator,
        menuReplyManager: MenuReplyManager,
        followUpScheduler: FollowUpScheduler,
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        secretVault: SecretVault,
        replyDispatcher: ReplyDispatcher
    ) : this(
        repository = repository,
        pendingReplyCoordinator = pendingReplyCoordinator,
        menuReplyManager = menuReplyManager,
        followUpScheduler = followUpScheduler,
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        secretVault = secretVault,
        replyDispatcher = replyDispatcher,
        conversationMemoryManager = null,
        smartCollaborationCoordinator = null,
        injectedUnifiedService = null
    )

    private val unifiedService: UnifiedSmartReplyService by lazy {
        injectedUnifiedService ?: UnifiedSmartReplyService(
            repository = repository,
            menuReplyManager = menuReplyManager,
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiReplyEngine,
            secretVault = secretVault,
            serverRepo = null,
            webhookClient = null,
            smartAdvisorClient = null,
            smartAdvisorPrefs = null,
            conversationMemoryManager = conversationMemoryManager,
            smartCollaborationCoordinator = smartCollaborationCoordinator,
            clockMs = clockMs,
            diagnosticLogger = diagnosticLogger
        )
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastReplyTimestamps = ConcurrentHashMap<String, Long>()

    fun onNotificationReceived(incoming: IncomingNotificationMessage) {
        engineScope.launch {
            runCatching {
                processIncomingMessage(incoming)
            }.onFailure { err ->
                Log.e(TAG, "Failed processing incoming message", err)
            }
        }
    }

    suspend fun processIncomingMessage(
        incoming: IncomingNotificationMessage,
        skipPreChecks: Boolean = false
    ): LocalOutcome {
        val settings = repository.getCurrentSettings()
        val isLocalActive = (settings.isAutoReplyEnabled || settings.isDrivingModeEnabled) &&
            (incoming.packageName in settings.enabledPackages)
        val aiSettings = aiSettingsPrefs.current()

        if (!skipPreChecks) {
            if (!isLocalActive && !(aiSettings.enabled && incoming.packageName in settings.enabledPackages)) {
                return LocalOutcome.BlockedDisabled
            }
            if (incoming.isGroup && !settings.replyInGroups) return LocalOutcome.BlockedPolicy
            if (!isContactAllowed(incoming.senderName, settings.contactFilterMode)) {
                return LocalOutcome.BlockedPolicy
            }

            val senderKey = "${incoming.packageName}:${incoming.senderName.trim().lowercase()}"
            val now = System.currentTimeMillis()
            val cooldownMs = settings.cooldownMinutes * 60_000L
            val lastSent = lastReplyTimestamps[senderKey] ?: 0L
            if (cooldownMs > 0 && now - lastSent < cooldownMs) {
                return LocalOutcome.BlockedCooldown
            }
        } else if (!isLocalActive && !aiSettings.enabled) {
            // Local auto-reply and AI are off while an external webhook/SA path may still be active
            return LocalOutcome.NoMatch
        }

        val senderKey = "${incoming.packageName}:${incoming.senderName.trim().lowercase()}"
        val conversationKey = ConversationKey.build(
            packageName = incoming.packageName,
            senderName = incoming.senderName,
            phone = incoming.phone,
            groupName = incoming.groupName,
            isGroup = incoming.isGroup
        )
        val now = System.currentTimeMillis()

        val decision = unifiedService.resolve(
            UnifiedSmartReplyService.Request(
                packageName = incoming.packageName,
                senderName = incoming.senderName,
                rawTitle = incoming.rawTitle,
                phone = incoming.phone,
                groupName = incoming.groupName,
                isGroup = incoming.isGroup,
                messageText = incoming.messageText,
                msgKey = incoming.msgKey,
                conversationKeyOverride = conversationKey,
                isSandbox = false,
                checkPolicy = !skipPreChecks,
                includeExternalStandalone = false,
                requireLocalActiveWhenCheckingPolicy = false,
                receivedAtMs = incoming.receivedAtMs
            )
        )

        if (decision.blockedDisabled) return LocalOutcome.BlockedDisabled
        if (decision.blockedPolicy) return LocalOutcome.BlockedPolicy
        if (decision.suppressedByUnknownIntent) {
            val statusName = decision.processingClassification?.status?.name
                ?: MessageProcessingStatus.UNKNOWN_INTENT.name
            val safeReason = SensitiveDataRedactor.scrub(
                decision.processingClassification?.failureReason?.takeIf { it.isNotBlank() }
                    ?: "تم كتم الرد تلقائياً لعدم فهم الرسالة ($statusName)",
                secretVault
            )
            repository.recordReplyLog(
                ReplyLogEntity(
                    senderName = incoming.senderName,
                    packageName = incoming.packageName,
                    incomingMessage = SensitiveDataRedactor.scrub(incoming.messageText, secretVault),
                    sentReply = safeReason,
                    isGroup = incoming.isGroup,
                    deliveryMethod = DELIVERY_SKIPPED_UNKNOWN_INTENT,
                    msgKey = incoming.msgKey,
                    conversationKey = conversationKey,
                    sourceCategory = statusName,
                    matchedRuleKeyword = ""
                )
            )
            return LocalOutcome.SuppressedUnunderstood(
                reason = safeReason,
                statusLabel = statusName
            )
        }

        val matchedRule = decision.matchedRule
        val resolvedRuleContent = decision.resolvedRuleContent
        val replyToSend = decision.replyText.takeIf { it.isNotBlank() }
        val sourceCategoryLabel = decision.sourceCategory.name
        val usedSmartCollab = decision.usedSmartCollaboration
        val ruleReplyType = decision.replyType
        val ruleImageUri = decision.imageUri?.takeIf { it.isNotBlank() }

        if (!decision.matched || (replyToSend.isNullOrBlank() && ruleImageUri == null)) {
            return LocalOutcome.NoMatch
        }

        if (!skipPreChecks) {
            lastReplyTimestamps[senderKey] = now
        }

        // Record incoming turn locally for non-AI rule replies only after confirming we have a response
        if (!usedSmartCollab && conversationMemoryManager != null && !settings.isDrivingModeEnabled) {
            conversationMemoryManager.recordIncomingUserMessage(
                conversationKey = conversationKey,
                packageName = incoming.packageName,
                isGroup = incoming.isGroup,
                displayName = incoming.senderName,
                phone = incoming.phone,
                messageText = incoming.messageText,
                contextWindowPairs = aiSettings.contextWindowPairs,
                isSandbox = false
            )
        }

        // Smart delay
        if (settings.replyDelaySeconds > 0) {
            delay(settings.replyDelaySeconds * 1000L)
            val latestSettings = repository.getCurrentSettings()
            if (!latestSettings.isAutoReplyEnabled &&
                !latestSettings.isDrivingModeEnabled &&
                !aiSettingsPrefs.current().enabled
            ) {
                return LocalOutcome.BlockedDisabled
            }
        }

        val textContent = replyToSend.orEmpty()
        var finalOutcome: ReplyDispatcher.SendOutcome? = null

        if (decision.responseType == ResponseType.PRODUCT_IMAGE &&
            decision.productMessage?.canSendAsImage() == true
        ) {
            finalOutcome = replyDispatcher.dispatchProductReply(
                msgKey = incoming.msgKey,
                targetPackage = incoming.packageName,
                senderName = incoming.senderName,
                responseType = ResponseType.PRODUCT_IMAGE,
                productMessage = decision.productMessage,
                fallbackTextReply = decision.fallbackTextReply.ifBlank { textContent },
                replyAction = incoming.replyAction,
                contentIntent = incoming.contentIntent,
                useAccessibilityFallback = settings.useAccessibilityFallback,
                directMethodLabel = "DirectReply",
                accessibilityTextMethodLabel = "Accessibility",
                accessibilityImageMethodLabel = "Accessibility-ProductImage",
                directSendInvoker = incoming.directSendInvoker,
                contentSendInvoker = incoming.contentSendInvoker
            )
        } else if (ruleReplyType == ReplyRuleEntity.REPLY_TYPE_IMAGE && ruleImageUri != null) {
            val imageMethod = dispatchImageViaAccessibility(incoming, ruleImageUri, "")
            finalOutcome = if (imageMethod != null) {
                ReplyDispatcher.SendOutcome.Sent(imageMethod)
            } else {
                ReplyDispatcher.SendOutcome.FailedConfirmed("فشل إرسال الصورة عبر إمكانية الوصول")
            }
        } else {
            if (textContent.isNotBlank()) {
                finalOutcome = replyDispatcher.dispatchReply(
                    msgKey = incoming.msgKey,
                    targetPackage = incoming.packageName,
                    senderName = incoming.senderName,
                    replyText = textContent,
                    replyAction = incoming.replyAction,
                    contentIntent = incoming.contentIntent,
                    useAccessibilityFallback = settings.useAccessibilityFallback,
                    directMethodLabel = "DirectReply",
                    accessibilityMethodLabel = "Accessibility",
                    directSendInvoker = incoming.directSendInvoker,
                    contentSendInvoker = incoming.contentSendInvoker
                )
            }

            if (ruleReplyType == ReplyRuleEntity.REPLY_TYPE_BOTH && ruleImageUri != null) {
                val imageMethod = dispatchImageViaAccessibility(incoming, ruleImageUri, "")
                if (finalOutcome == null && imageMethod != null) {
                    finalOutcome = ReplyDispatcher.SendOutcome.Sent(imageMethod)
                }
            }
        }

        return when (val outcome = finalOutcome ?: ReplyDispatcher.SendOutcome.FailedConfirmed("لم يتم الإرسال")) {
            is ReplyDispatcher.SendOutcome.Sent -> {
                matchedRule?.let { rule ->
                    repository.incrementRuleHit(rule.id)
                    resolvedRuleContent?.templateId?.let { tid ->
                        repository.incrementTemplateUsage(tid)
                    }
                    followUpScheduler.scheduleFollowUp(
                        ruleId = rule.id,
                        recipient = incoming.senderName,
                        app = incoming.packageName
                    )
                }
                val rawReply = when {
                    decision.responseType == ResponseType.PRODUCT_IMAGE ->
                        decision.displayReplyText.ifBlank { "[صورة منتج]\n$textContent".trim() }
                    ruleReplyType == ReplyRuleEntity.REPLY_TYPE_IMAGE ->
                        "[صورة] $textContent".trim()
                    else -> textContent
                }
                val safeLoggedReply = SensitiveDataRedactor.scrub(rawReply, secretVault)

                // Commit assistant turn ONLY upon confirmed send; defer handoff until Accessibility confirms Send click!
                if (conversationMemoryManager != null && !settings.isDrivingModeEnabled) {
                    if (outcome.isHandoff) {
                        conversationMemoryManager.stagePendingAssistantTurn(
                            msgKey = incoming.msgKey,
                            conversationKey = conversationKey,
                            assistantReply = safeLoggedReply,
                            isSandbox = false
                        )
                    } else {
                        conversationMemoryManager.commitAssistantTurnImmediately(
                            conversationKey = conversationKey,
                            assistantReply = safeLoggedReply,
                            contextWindowPairs = aiSettings.contextWindowPairs,
                            isSandbox = false
                        )
                    }
                }

                repository.recordReplyLog(
                    ReplyLogEntity(
                        senderName = incoming.senderName,
                        packageName = incoming.packageName,
                        incomingMessage = SensitiveDataRedactor.scrub(incoming.messageText, secretVault),
                        sentReply = safeLoggedReply,
                        isGroup = incoming.isGroup,
                        deliveryMethod = outcome.deliveryMethod,
                        msgKey = incoming.msgKey,
                        conversationKey = conversationKey,
                        sourceCategory = sourceCategoryLabel,
                        matchedRuleKeyword = matchedRule?.keyword.orEmpty()
                    )
                )
                LocalOutcome.Handled(
                    deliveryMethod = outcome.deliveryMethod,
                    replyText = rawReply,
                    isHandoff = outcome.isHandoff
                )
            }
            is ReplyDispatcher.SendOutcome.FailedConfirmed -> {
                conversationMemoryManager?.discardPendingHandoffTurn(incoming.msgKey)
                repository.recordReplyLog(
                    ReplyLogEntity(
                        senderName = incoming.senderName,
                        packageName = incoming.packageName,
                        incomingMessage = SensitiveDataRedactor.scrub(incoming.messageText, secretVault),
                        sentReply = SensitiveDataRedactor.scrub(outcome.error, secretVault),
                        isGroup = incoming.isGroup,
                        deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED,
                        msgKey = incoming.msgKey,
                        conversationKey = conversationKey,
                        sourceCategory = sourceCategoryLabel,
                        matchedRuleKeyword = matchedRule?.keyword.orEmpty()
                    )
                )
                LocalOutcome.SendFailedConfirmed(outcome.error)
            }
            is ReplyDispatcher.SendOutcome.DeliveryUnknown -> {
                conversationMemoryManager?.discardPendingHandoffTurn(incoming.msgKey)
                repository.recordReplyLog(
                    ReplyLogEntity(
                        senderName = incoming.senderName,
                        packageName = incoming.packageName,
                        incomingMessage = SensitiveDataRedactor.scrub(incoming.messageText, secretVault),
                        sentReply = SensitiveDataRedactor.scrub(outcome.error, secretVault),
                        isGroup = incoming.isGroup,
                        deliveryMethod = MessageQueueWorker.STATUS_DELIVERY_UNKNOWN,
                        msgKey = incoming.msgKey,
                        conversationKey = conversationKey,
                        sourceCategory = sourceCategoryLabel,
                        matchedRuleKeyword = matchedRule?.keyword.orEmpty()
                    )
                )
                LocalOutcome.SendDeliveryUnknown(outcome.error)
            }
        }
    }

    suspend fun isContactAllowed(senderName: String, mode: ContactFilterMode): Boolean {
        val normalizedSender = senderName.trim().lowercase()
        return when (mode) {
            ContactFilterMode.ALL -> true
            ContactFilterMode.INCLUDE_ONLY -> {
                val allowed = repository.getContactFiltersByType(ContactFilterListType.INCLUDE)
                allowed.any {
                    val target = it.contactName.trim().lowercase()
                    normalizedSender == target || normalizedSender.contains(target)
                }
            }
            ContactFilterMode.EXCLUDE_LIST -> {
                val excluded = repository.getContactFiltersByType(ContactFilterListType.EXCLUDE)
                excluded.none {
                    val target = it.contactName.trim().lowercase()
                    normalizedSender == target || normalizedSender.contains(target)
                }
            }
        }
    }

    private fun dispatchImageViaAccessibility(
        incoming: IncomingNotificationMessage,
        imageUriString: String,
        caption: String
    ): String? {
        val parsedUri = runCatching { Uri.parse(imageUriString) }.getOrNull() ?: return null
        val service = AutoReplyAccessibilityService.activeInstance
        if (service != null) {
            service.sendImage(incoming.packageName, parsedUri, caption)
            return "Accessibility-Image"
        }
        val pendingImageReply = PendingAccessibilityReply(
            msgKey = incoming.msgKey,
            targetPackage = incoming.packageName,
            senderName = incoming.senderName,
            replyText = caption,
            imageUri = imageUriString
        )
        pendingReplyCoordinator.enqueue(pendingImageReply)
        val outcome = runCatching {
            val intent = incoming.contentIntent ?: return@runCatching null
            if (incoming.contentSendInvoker != null) {
                incoming.contentSendInvoker.invoke(intent)
            } else {
                intent.send()
            }
            "Accessibility-Image"
        }.getOrNull()
        if (outcome == null) {
            if (incoming.msgKey.isNotBlank()) {
                pendingReplyCoordinator.removeByMsgKey(incoming.msgKey)
            }
            pendingReplyCoordinator.consumeAtomic(pendingImageReply.id)
        }
        return outcome
    }

    companion object {
        private const val TAG = "AutoReplyEngine"
        const val DELIVERY_SKIPPED_UNKNOWN_INTENT = "SKIPPED_UNKNOWN_INTENT"
    }
}
