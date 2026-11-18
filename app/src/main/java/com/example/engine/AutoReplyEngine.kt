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
}

@Singleton
class AutoReplyEngine @Inject constructor(
    private val repository: AutoMateRepository,
    private val pendingReplyCoordinator: PendingReplyCoordinator,
    private val menuReplyManager: MenuReplyManager,
    private val followUpScheduler: FollowUpScheduler,
    private val aiSettingsPrefs: AiSettingsPrefs,
    private val aiReplyEngine: AIReplyEngine,
    private val secretVault: SecretVault,
    private val replyDispatcher: ReplyDispatcher,
    private val conversationMemoryManager: ConversationMemoryManager? = null,
    private val smartCollaborationCoordinator: SmartCollaborationCoordinator? = null
) {
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
        smartCollaborationCoordinator = null
    )

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
            if (!isLocalActive) return LocalOutcome.BlockedDisabled
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

        // Ordered decision chain:
        // 1. Driving Mode
        // 2. Interactive Menus
        // 3. Explicit Local Rules (excluding wildcard "*")
        // 4. Smart Collaboration (New API -> Ollama/Qwen -> Smart Advisor 6.5.0)
        // 5. Wildcard Rule "*" (local fallback only after AI)
        // 6. Explicit Safe Fallback Reply (if AI configured one)
        var matchedRule: ReplyRuleEntity? = null
        var replyToSend: String? = null
        var sourceCategoryLabel = ""
        var usedSmartCollab = false

        if (settings.isDrivingModeEnabled) {
            replyToSend = settings.drivingModeReplyText.takeIf { it.isNotBlank() }
            sourceCategoryLabel = ReplySourceCategory.DRIVING_MODE.name
        } else {
            val menuReply = menuReplyManager.processConversationMessage(senderKey, incoming.messageText)
            if (!menuReply.isNullOrBlank()) {
                replyToSend = menuReply
                sourceCategoryLabel = ReplySourceCategory.MENU_REPLY.name
            } else {
                val rules = repository.getEnabledRulesOnce()
                val specificRules = rules.filter { it.keyword.trim() != "*" }
                val matchedSpecific = RuleMatcher.findBestMatch(incoming.messageText, specificRules)
                if (matchedSpecific != null) {
                    matchedRule = matchedSpecific
                    sourceCategoryLabel = ReplySourceCategory.LOCAL_RULE.name
                } else {
                    if (aiSettings.enabled) {
                        if (smartCollaborationCoordinator != null) {
                            usedSmartCollab = true
                            val collab = smartCollaborationCoordinator.collaborate(
                                SmartCollaborationCoordinator.CollaborationInput(
                                    packageName = incoming.packageName,
                                    senderName = incoming.senderName,
                                    rawTitle = incoming.rawTitle,
                                    phone = incoming.phone,
                                    groupName = incoming.groupName,
                                    isGroup = incoming.isGroup,
                                    messageText = incoming.messageText,
                                    isSandbox = false,
                                    conversationKeyOverride = conversationKey,
                                    messageId = incoming.msgKey
                                )
                            )
                            replyToSend = collab.replyText
                            sourceCategoryLabel = if (collab.calledSmartAdvisor) {
                                ReplySourceCategory.SMART_ADVISOR.name
                            } else {
                                ReplySourceCategory.AI_ENGINE.name
                            }
                        } else {
                            val apiKey = secretVault.get(AiSettingsPrefs.apiKeyRefFor(aiSettings.provider))
                            val aiConfig = AIReplyEngine.AIConfig(
                                provider = aiSettings.provider,
                                apiKey = apiKey,
                                model = aiSettings.effectivePrimaryModel(),
                                systemPrompt = aiSettings.systemPrompt,
                                temperature = aiSettings.temperature,
                                maxTokens = aiSettings.maxTokens,
                                baseUrl = aiSettings.primaryBaseUrl
                            )
                            replyToSend = aiReplyEngine.generateReplyWithFallback(
                                message = incoming.messageText,
                                primaryConfig = aiConfig,
                                context = "المرسل: ${incoming.senderName}"
                            )
                            sourceCategoryLabel = ReplySourceCategory.AI_ENGINE.name
                        }
                    }

                    if (replyToSend.isNullOrBlank()) {
                        val fallbackRule = RuleMatcher.findBestMatch(incoming.messageText, rules)
                        if (fallbackRule != null) {
                            matchedRule = fallbackRule
                            sourceCategoryLabel = ReplySourceCategory.WILDCARD_RULE.name
                        }
                    }

                    if (matchedRule == null && replyToSend.isNullOrBlank() && aiSettings.enabled && aiSettings.safeFallbackReply.isNotBlank()) {
                        replyToSend = aiSettings.safeFallbackReply
                        sourceCategoryLabel = ReplySourceCategory.AI_ENGINE.name
                    }
                }
            }
        }

        val resolvedRuleContent = matchedRule?.let { repository.resolveRuleContent(it) }
        if (resolvedRuleContent != null) {
            val variableMsg = IncomingMessage(
                app = incoming.packageName,
                sender = incoming.senderName,
                senderName = incoming.senderName,
                message = incoming.messageText,
                phone = incoming.phone,
                groupName = incoming.groupName.orEmpty(),
                isGroup = incoming.isGroup,
                notificationId = incoming.msgKey,
                timestamp = incoming.receivedAtMs
            )
            replyToSend = VariableEngine.resolveTemplate(resolvedRuleContent.replyText, variableMsg)
        }

        val ruleReplyType = resolvedRuleContent?.replyType ?: ReplyRuleEntity.REPLY_TYPE_TEXT
        val ruleImageUri = resolvedRuleContent?.imageUri?.takeIf { it.isNotBlank() }

        if (replyToSend.isNullOrBlank() && ruleImageUri == null) {
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

        if (ruleReplyType == ReplyRuleEntity.REPLY_TYPE_IMAGE && ruleImageUri != null) {
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
                val rawReply = if (ruleReplyType == ReplyRuleEntity.REPLY_TYPE_IMAGE) {
                    "[صورة] $textContent".trim()
                } else {
                    textContent
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
    }
}
