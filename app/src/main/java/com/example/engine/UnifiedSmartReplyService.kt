package com.example.engine

import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.GroupReplyMode
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.WebhookMode
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.ContactFilterMode
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ResolvedRuleContent
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.network.smartadvisor.SmartAdvisorResponseParser
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedSmartReplyService internal constructor(
    private val repository: AutoMateRepository,
    private val menuReplyManager: MenuReplyManager,
    private val aiSettingsPrefs: AiSettingsPrefs,
    private val aiReplyEngine: AIReplyEngine,
    private val secretVault: SecretVault,
    private val serverRepo: ServerConfigRepository?,
    private val webhookClient: WebhookClient?,
    private val smartAdvisorClient: SmartAdvisorClient?,
    private val smartAdvisorPrefs: SmartAdvisorPrefs?,
    private val conversationMemoryManager: ConversationMemoryManager? = null,
    private val smartCollaborationCoordinator: SmartCollaborationCoordinator? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val diagnosticLogger: SafeDiagnosticLogger = SafeDiagnosticLogger(secretVault, clockMs)
) {
    @Inject
    constructor(
        repository: AutoMateRepository,
        menuReplyManager: MenuReplyManager,
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        secretVault: SecretVault,
        serverRepo: ServerConfigRepository,
        webhookClient: WebhookClient,
        smartAdvisorClient: SmartAdvisorClient,
        smartAdvisorPrefs: SmartAdvisorPrefs,
        conversationMemoryManager: ConversationMemoryManager,
        smartCollaborationCoordinator: SmartCollaborationCoordinator,
        diagnosticLogger: SafeDiagnosticLogger
    ) : this(
        repository = repository,
        menuReplyManager = menuReplyManager,
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        secretVault = secretVault,
        serverRepo = serverRepo,
        webhookClient = webhookClient,
        smartAdvisorClient = smartAdvisorClient,
        smartAdvisorPrefs = smartAdvisorPrefs,
        conversationMemoryManager = conversationMemoryManager,
        smartCollaborationCoordinator = smartCollaborationCoordinator,
        clockMs = { System.currentTimeMillis() },
        diagnosticLogger = diagnosticLogger
    )

    data class Request(
        val packageName: String = "com.whatsapp",
        val senderName: String,
        val rawTitle: String = senderName,
        val phone: String = "",
        val groupName: String? = null,
        val isGroup: Boolean = false,
        val messageText: String,
        val msgKey: String = "",
        val conversationKeyOverride: String? = null,
        val isSandbox: Boolean = false,
        val checkPolicy: Boolean = true,
        val includeExternalStandalone: Boolean = true,
        val requireLocalActiveWhenCheckingPolicy: Boolean = false,
        val receivedAtMs: Long = System.currentTimeMillis()
    )

    data class Decision(
        val matched: Boolean,
        val replyText: String = "",
        val displayReplyText: String = replyText,
        val sourceLabel: String = ReplySourceCategory.NO_MATCH.labelAr,
        val sourceCategory: ReplySourceCategory = ReplySourceCategory.NO_MATCH,
        val matchedRule: ReplyRuleEntity? = null,
        val resolvedRuleContent: ResolvedRuleContent? = null,
        val replyType: String = ReplyRuleEntity.REPLY_TYPE_TEXT,
        val imageUri: String? = null,
        val evaluatedPath: List<String> = emptyList(),
        val latencyMs: Long = 0L,
        val conversationKey: String = "",
        val blockedDisabled: Boolean = false,
        val blockedPolicy: Boolean = false,
        val usedSmartCollaboration: Boolean = false,
        val usedNewApi: Boolean = false,
        val activeEngineLabel: String = "",
        val switchedToQwen: Boolean = false,
        val calledSmartAdvisor: Boolean = false,
        val calledWebhook: Boolean = false,
        val detectedIntent: String = "",
        val saToolReason: String = "",
        val webhookToolReason: String = "",
        val webhookDataSnippet: String = "",
        val webhookStatusCode: Int? = null,
        val invokedTools: List<String> = emptyList(),
        val toolRoundsUsed: Int = 0,
        val verifiedStoreDataUsed: Boolean = false,
        val verifiedDataSnippet: String = "",
        val memoryLoaded: Boolean = false,
        val memoryUpdated: Boolean = false,
        val needsHuman: Boolean = false,
        val stageLatenciesMs: Map<String, Long> = emptyMap(),
        val redactedError: String? = null,
        val responseType: ResponseType = ResponseType.TEXT_MESSAGE,
        val productMessage: ProductMessage? = null,
        val products: List<ProductMessage> = emptyList(),
        val fallbackTextReply: String = "",
        val storeName: String = "",
        val suppressedByUnknownIntent: Boolean = false,
        val processingClassification: MessageProcessingClassification? = null
    )

    suspend fun resolve(request: Request): Decision {
        val startMs = clockMs()
        val evaluated = mutableListOf<String>()
        val cleanMessage = request.messageText.trim()
        val resolvedConvKey = resolveConversationKey(request)

        // 1. Validate message
        if (cleanMessage.isEmpty()) {
            evaluated += "التحقق من الرسالة (فارغة)"
            return Decision(
                matched = false,
                replyText = if (request.isSandbox) "أرسل رسالة غير فارغة لاختبار الرد التلقائي." else "",
                displayReplyText = if (request.isSandbox) "أرسل رسالة غير فارغة لاختبار الرد التلقائي." else "",
                sourceLabel = ReplySourceCategory.NO_MATCH.labelAr,
                sourceCategory = ReplySourceCategory.NO_MATCH,
                evaluatedPath = evaluated,
                conversationKey = resolvedConvKey
            )
        }

        val settings = repository.getCurrentSettings()
        val aiSettings = aiSettingsPrefs.current()
        val serverConfig = serverRepo?.get()
        val webhookEnabled = serverConfig?.isEnabled == true
        val saEnabled = smartAdvisorPrefs?.enabled == true
        val webhookStandaloneEligible = webhookEnabled && !aiSettings.enabled &&
            (serverConfig?.effectiveWebhookMode() == WebhookMode.STANDALONE_FALLBACK)
        val saStandaloneEligible = saEnabled && !aiSettings.enabled

        // 2. Policy & activation checks
        if (request.checkPolicy) {
            if (request.requireLocalActiveWhenCheckingPolicy) {
                val isLocalActive = (settings.isAutoReplyEnabled || settings.isDrivingModeEnabled || aiSettings.enabled) &&
                    (request.packageName in settings.enabledPackages)
                if (!isLocalActive) {
                    return Decision(
                        matched = false,
                        blockedDisabled = true,
                        evaluatedPath = evaluated,
                        conversationKey = resolvedConvKey
                    )
                }
                if (!isContactAllowed(request.senderName, settings.contactFilterMode)) {
                    return Decision(
                        matched = false,
                        blockedPolicy = true,
                        evaluatedPath = evaluated,
                        conversationKey = resolvedConvKey,
                        sourceCategory = ReplySourceCategory.POLICY_BLOCKED,
                        sourceLabel = ReplySourceCategory.POLICY_BLOCKED.labelAr
                    )
                }
            }

            if (request.isGroup) {
                evaluated += "سياسة المجموعات"
                val groupMode = serverConfig?.let {
                    runCatching { GroupReplyMode.valueOf(it.sendOnGroup) }.getOrDefault(GroupReplyMode.KEYWORD_ONLY)
                } ?: GroupReplyMode.KEYWORD_ONLY
                val localEnabled = settings.isAutoReplyEnabled || settings.isDrivingModeEnabled || aiSettings.enabled
                val groupBlocked = (localEnabled && !settings.replyInGroups) ||
                    (!localEnabled && webhookStandaloneEligible && groupMode == GroupReplyMode.NEVER) ||
                    (!localEnabled && !webhookStandaloneEligible && saStandaloneEligible && !settings.replyInGroups)
                if (groupBlocked) {
                    return Decision(
                        matched = false,
                        replyText = if (request.isSandbox) "تم تخطي الرسالة: الرد التلقائي في المجموعات معطّل في الإعدادات." else "",
                        displayReplyText = if (request.isSandbox) "تم تخطي الرسالة: الرد التلقائي في المجموعات معطّل في الإعدادات." else "",
                        sourceLabel = ReplySourceCategory.POLICY_BLOCKED.labelAr,
                        sourceCategory = ReplySourceCategory.POLICY_BLOCKED,
                        blockedPolicy = true,
                        evaluatedPath = evaluated,
                        latencyMs = elapsed(startMs),
                        conversationKey = resolvedConvKey
                    )
                }
            }
        }

        // 3. Driving Mode
        if (settings.isDrivingModeEnabled && settings.drivingModeReplyText.isNotBlank()) {
            evaluated += "وضع القيادة"
            val finalDriving = SensitiveDataRedactor.scrub(settings.drivingModeReplyText.trim(), secretVault)
            return Decision(
                matched = true,
                replyText = finalDriving,
                displayReplyText = finalDriving,
                sourceLabel = ReplySourceCategory.DRIVING_MODE.labelAr,
                sourceCategory = ReplySourceCategory.DRIVING_MODE,
                evaluatedPath = evaluated,
                latencyMs = elapsed(startMs),
                conversationKey = resolvedConvKey
            )
        }

        // 4. Interactive Menus (shared key consistency: senderKey for live if override blank, resolvedConvKey for sandbox)
        evaluated += "القوائم التفاعلية"
        val menuKey = if (request.isSandbox) {
            resolvedConvKey
        } else {
            "${request.packageName}:${request.senderName.trim().lowercase()}"
        }
        val menuReply = menuReplyManager.processConversationMessage(menuKey, cleanMessage)
        if (!menuReply.isNullOrBlank()) {
            return Decision(
                matched = true,
                replyText = menuReply,
                displayReplyText = menuReply,
                sourceLabel = ReplySourceCategory.MENU_REPLY.labelAr,
                sourceCategory = ReplySourceCategory.MENU_REPLY,
                evaluatedPath = evaluated,
                latencyMs = elapsed(startMs),
                conversationKey = resolvedConvKey
            )
        }

        // 5. User Keyword Rules (specific rules before AI, unless disabled in settings)
        val enabledRules = if (aiSettings.useKeywordRules) {
            repository.getEnabledRulesOnce()
        } else {
            evaluated += "تخطي قواعد الكلمات (معطلة)"
            emptyList()
        }
        var keywordContextHint = ""
        if (aiSettings.useKeywordRules) {
            evaluated += "القواعد المحلية"
            val specificRules = enabledRules.filter { it.keyword.trim() != "*" }
            val matchedSpecific = RuleMatcher.findBestMatch(cleanMessage, specificRules)
            if (matchedSpecific != null) {
                val resolvedRule = repository.resolveRuleContent(matchedSpecific)
                val nonConclusiveHint = extractNonConclusiveRuleHint(matchedSpecific, resolvedRule)
                if (nonConclusiveHint == null) {
                    val variableMsg = buildIncomingMessage(request, cleanMessage)
                    val resolvedText = VariableEngine.resolveTemplate(resolvedRule.replyText, variableMsg)
                    if (resolvedText.isNotBlank() || resolvedRule.imageUri?.isNotBlank() == true) {
                        val hasImage = resolvedRule.replyType != ReplyRuleEntity.REPLY_TYPE_TEXT &&
                            !resolvedRule.imageUri.isNullOrBlank()
                        val displayReply = when (resolvedRule.replyType) {
                            ReplyRuleEntity.REPLY_TYPE_IMAGE -> "[صورة] $resolvedText".trim()
                            ReplyRuleEntity.REPLY_TYPE_BOTH -> "$resolvedText\n[+ صورة مرفقة]".trim()
                            else -> resolvedText
                        }
                        val label = buildString {
                            append("قاعدة: ").append(matchedSpecific.keyword)
                            if (!resolvedRule.templateTitle.isNullOrBlank()) {
                                append(" • قالب: ").append(resolvedRule.templateTitle)
                            }
                        }
                        return Decision(
                            matched = true,
                            replyText = resolvedText,
                            displayReplyText = displayReply,
                            sourceLabel = label,
                            sourceCategory = ReplySourceCategory.LOCAL_RULE,
                            matchedRule = matchedSpecific,
                            resolvedRuleContent = resolvedRule,
                            replyType = resolvedRule.replyType,
                            imageUri = if (hasImage) resolvedRule.imageUri else null,
                            evaluatedPath = evaluated,
                            latencyMs = elapsed(startMs),
                            conversationKey = resolvedConvKey
                        )
                    }
                } else {
                    evaluated += "قاعدة غير حاسمة (${matchedSpecific.keyword}) ← انتقال للذكاء الاصطناعي"
                    keywordContextHint = nonConclusiveHint
                }
            }
        }

        // 6. AI Orchestrator (Single Conversation Orchestrator: Primary -> Fallback + Smart Advisor + Webhook tools)
        var lastAiOutcome: SmartCollaborationCoordinator.CollaborationOutcome? = null
        var usedSmartCollab = false
        var directAiError: String? = null

        if (aiSettings.enabled) {
            if (smartCollaborationCoordinator != null) {
                usedSmartCollab = true
                evaluated += if (aiSettings.provider == AIReplyEngine.Provider.NEW_API) {
                    "New API"
                } else {
                    "AI (${aiSettings.provider.labelAr})"
                }
                val collab = smartCollaborationCoordinator.collaborate(
                    SmartCollaborationCoordinator.CollaborationInput(
                        packageName = request.packageName,
                        senderName = request.senderName,
                        rawTitle = request.rawTitle,
                        phone = request.phone,
                        groupName = request.groupName,
                        isGroup = request.isGroup,
                        messageText = cleanMessage,
                        isSandbox = request.isSandbox,
                        conversationKeyOverride = resolvedConvKey,
                        messageId = request.msgKey,
                        keywordContextHint = keywordContextHint
                    )
                )
                lastAiOutcome = collab
                if (collab.switchedToQwen) {
                    evaluated += "الاحتياطي (${aiSettings.effectiveOllamaModel()})"
                }
                if (collab.calledSmartAdvisor) {
                    evaluated += "Smart Advisor 6.5.0"
                }
                if (collab.calledWebhook) {
                    evaluated += "أداة Webhook"
                }

                if (collab.suppressedByUnknownIntent) {
                    evaluated += "كتم الرد (رسالة غير مفهومة)"
                    val sandboxNotice = if (request.isSandbox) {
                        "تم كتم الرد تلقائياً لأن الرسالة غير مفهومة ومفتاح عدم الرد مفعّل."
                    } else {
                        ""
                    }
                    return Decision(
                        matched = false,
                        replyText = "",
                        displayReplyText = sandboxNotice,
                        sourceLabel = "كتم الرد (عدم فهم)",
                        sourceCategory = ReplySourceCategory.NO_MATCH,
                        evaluatedPath = evaluated,
                        latencyMs = elapsed(startMs),
                        conversationKey = collab.conversationKey,
                        usedSmartCollaboration = true,
                        usedNewApi = collab.usedNewApi,
                        activeEngineLabel = collab.activeEngine,
                        switchedToQwen = collab.switchedToQwen,
                        detectedIntent = collab.saIntent,
                        memoryLoaded = collab.memoryLoaded,
                        stageLatenciesMs = collab.stageLatenciesMs,
                        redactedError = collab.redactedError,
                        suppressedByUnknownIntent = true,
                        processingClassification = collab.processingClassification
                    )
                }

                if (!collab.replyText.isNullOrBlank()) {
                    if (request.isSandbox) {
                        conversationMemoryManager?.commitAssistantTurnImmediately(
                            conversationKey = resolvedConvKey,
                            assistantReply = collab.replyText,
                            contextWindowPairs = aiSettings.contextWindowPairs,
                            isSandbox = true
                        )
                    }
                    val category = if (collab.calledSmartAdvisor && !collab.calledWebhook) {
                        ReplySourceCategory.SMART_ADVISOR
                    } else {
                        ReplySourceCategory.AI_ENGINE
                    }
                    val label = when {
                        collab.calledSmartAdvisor && collab.calledWebhook ->
                            "${collab.activeEngine} + Smart Advisor + Webhook"
                        collab.calledSmartAdvisor ->
                            "${collab.activeEngine} + Smart Advisor"
                        collab.calledWebhook ->
                            "${collab.activeEngine} + Webhook"
                        else ->
                            collab.activeEngine.ifBlank { "الذكاء الاصطناعي" }
                    }
                    val isProductImage = collab.responseType == ResponseType.PRODUCT_IMAGE &&
                        collab.productMessage?.canSendAsImage() == true
                    val finalReplyText = if (isProductImage) {
                        collab.productMessage!!.buildCaption().ifBlank { collab.replyText }
                    } else {
                        collab.replyText
                    }
                    val displayText = if (isProductImage) {
                        "[صورة منتج]\n$finalReplyText"
                    } else {
                        finalReplyText
                    }
                    return Decision(
                        matched = true,
                        replyText = finalReplyText,
                        displayReplyText = displayText,
                        sourceLabel = label,
                        sourceCategory = category,
                        replyType = if (isProductImage) ReplyRuleEntity.REPLY_TYPE_BOTH else ReplyRuleEntity.REPLY_TYPE_TEXT,
                        imageUri = if (isProductImage) collab.productMessage?.imageUrl else null,
                        evaluatedPath = evaluated,
                        latencyMs = elapsed(startMs),
                        conversationKey = collab.conversationKey,
                        usedSmartCollaboration = true,
                        usedNewApi = collab.usedNewApi,
                        activeEngineLabel = collab.activeEngine,
                        switchedToQwen = collab.switchedToQwen,
                        calledSmartAdvisor = collab.calledSmartAdvisor,
                        calledWebhook = collab.calledWebhook,
                        detectedIntent = collab.saIntent,
                        saToolReason = collab.saToolReason,
                        webhookToolReason = collab.webhookToolReason,
                        webhookDataSnippet = collab.webhookDataSnippet,
                        webhookStatusCode = collab.webhookStatusCode,
                        invokedTools = collab.invokedTools,
                        toolRoundsUsed = collab.toolRoundsUsed,
                        verifiedStoreDataUsed = collab.verifiedStoreDataUsed,
                        verifiedDataSnippet = collab.verifiedDataSnippet,
                        memoryLoaded = collab.memoryLoaded,
                        memoryUpdated = collab.memoryUpdated,
                        needsHuman = collab.needsHuman,
                        stageLatenciesMs = collab.stageLatenciesMs,
                        redactedError = collab.redactedError,
                        responseType = collab.responseType,
                        productMessage = collab.productMessage,
                        products = collab.products,
                        fallbackTextReply = collab.fallbackTextReply.ifBlank { finalReplyText },
                        storeName = collab.storeName,
                        processingClassification = collab.processingClassification
                    )
                }
            } else {
                evaluated += "AI (${aiSettings.provider.labelAr})"
                val apiKey = secretVault.get(AiSettingsPrefs.apiKeyRefFor(aiSettings.provider))
                val constrainedPrompt = buildDirectAiSystemPrompt(aiSettings, keywordContextHint)
                val aiConfig = AIReplyEngine.AIConfig(
                    provider = aiSettings.provider,
                    apiKey = apiKey,
                    model = aiSettings.effectivePrimaryModel(),
                    systemPrompt = constrainedPrompt,
                    temperature = aiSettings.temperature,
                    maxTokens = aiSettings.maxTokens,
                    baseUrl = aiSettings.primaryBaseUrl
                )
                val rawAiReply = aiReplyEngine.generateReplyWithFallback(
                    message = cleanMessage,
                    primaryConfig = aiConfig,
                    context = "المرسل: ${request.senderName}"
                )
                val finalAiReply = rawAiReply?.let {
                    AIReplyEngine.enforceToneAndLength(
                        SensitiveDataRedactor.scrub(it, secretVault),
                        aiSettings
                    )
                }
                if (!finalAiReply.isNullOrBlank()) {
                    return Decision(
                        matched = true,
                        replyText = finalAiReply,
                        displayReplyText = finalAiReply,
                        sourceLabel = "الذكاء الاصطناعي (${aiSettings.provider.labelAr})",
                        sourceCategory = ReplySourceCategory.AI_ENGINE,
                        evaluatedPath = evaluated,
                        latencyMs = elapsed(startMs),
                        conversationKey = resolvedConvKey,
                        usedNewApi = aiSettings.provider == AIReplyEngine.Provider.NEW_API,
                        activeEngineLabel = "${aiSettings.provider.labelAr} (${aiSettings.effectivePrimaryModel()})"
                    )
                } else {
                    directAiError = "تعذر الحصول على استجابة من محرك الذكاء الاصطناعي"
                }
            }
        }

        // 7. Wildcard Rule "*" (strictly after AI/Collaboration)
        if (aiSettings.useKeywordRules) {
            val wildcardMatch = RuleMatcher.findBestMatch(cleanMessage, enabledRules)
            if (wildcardMatch != null) {
                val resolvedRule = repository.resolveRuleContent(wildcardMatch)
                val variableMsg = buildIncomingMessage(request, cleanMessage)
                val resolvedText = VariableEngine.resolveTemplate(resolvedRule.replyText, variableMsg)
                if (resolvedText.isNotBlank() || resolvedRule.imageUri?.isNotBlank() == true) {
                    evaluated += "القاعدة الافتراضية (*)"
                    val hasImage = resolvedRule.replyType != ReplyRuleEntity.REPLY_TYPE_TEXT &&
                        !resolvedRule.imageUri.isNullOrBlank()
                    val displayReply = when (resolvedRule.replyType) {
                        ReplyRuleEntity.REPLY_TYPE_IMAGE -> "[صورة] $resolvedText".trim()
                        ReplyRuleEntity.REPLY_TYPE_BOTH -> "$resolvedText\n[+ صورة مرفقة]".trim()
                        else -> resolvedText
                    }
                    val label = if (!resolvedRule.templateTitle.isNullOrBlank()) {
                        "${ReplySourceCategory.WILDCARD_RULE.labelAr} • قالب: ${resolvedRule.templateTitle}"
                    } else {
                        ReplySourceCategory.WILDCARD_RULE.labelAr
                    }
                    return Decision(
                        matched = true,
                        replyText = resolvedText,
                        displayReplyText = displayReply,
                        sourceLabel = label,
                        sourceCategory = ReplySourceCategory.WILDCARD_RULE,
                        matchedRule = wildcardMatch,
                        resolvedRuleContent = resolvedRule,
                        replyType = resolvedRule.replyType,
                        imageUri = if (hasImage) resolvedRule.imageUri else null,
                        evaluatedPath = evaluated,
                        latencyMs = elapsed(startMs),
                        conversationKey = resolvedConvKey,
                        usedSmartCollaboration = usedSmartCollab,
                        usedNewApi = lastAiOutcome?.usedNewApi == true,
                        switchedToQwen = lastAiOutcome?.switchedToQwen == true,
                        redactedError = lastAiOutcome?.redactedError ?: directAiError
                    )
                }
            }
        }

        // 8. Explicit Safe Fallback Reply when AI failed (technical failure only)
        if (aiSettings.enabled && aiSettings.safeFallbackReply.isNotBlank()) {
            evaluated += "الرد الآمن البديل"
            val safeReply = SensitiveDataRedactor.scrub(aiSettings.safeFallbackReply.trim(), secretVault)
            diagnosticLogger.log(
                tag = DiagnosticEventTag.FALLBACK_USED,
                conversationId = resolvedConvKey,
                messageId = request.msgKey,
                detectedStatus = (lastAiOutcome?.processingClassification?.status
                    ?: MessageProcessingStatus.SERVICE_ERROR).name,
                decision = "SAFE_TECHNICAL_FALLBACK_REPLY",
                errorSource = lastAiOutcome?.activeEngine ?: aiSettings.provider.name
            )
            return Decision(
                matched = true,
                replyText = safeReply,
                displayReplyText = safeReply,
                sourceLabel = "الرد الآمن البديل",
                sourceCategory = ReplySourceCategory.AI_ENGINE,
                evaluatedPath = evaluated,
                latencyMs = elapsed(startMs),
                conversationKey = resolvedConvKey,
                usedSmartCollaboration = usedSmartCollab,
                usedNewApi = lastAiOutcome?.usedNewApi == true,
                switchedToQwen = lastAiOutcome?.switchedToQwen == true,
                redactedError = lastAiOutcome?.redactedError ?: directAiError,
                processingClassification = lastAiOutcome?.processingClassification?.copy(finalReply = safeReply)
            )
        }

        // 9. Standalone Webhook / Standalone Smart Advisor (strictly ONLY when AI Orchestrator is disabled!)
        if (request.includeExternalStandalone) {
            if (webhookStandaloneEligible && serverConfig != null && webhookClient != null) {
                evaluated += "خادم Webhook"
                val msg = buildIncomingMessage(request, cleanMessage)
                val result = webhookClient.send(
                    config = serverConfig,
                    msg = msg,
                    idempotencyKey = if (request.isSandbox) "test-preview-$startMs" else request.msgKey
                )
                return mapExternalResult(
                    result = result,
                    replyKey = serverConfig.responseReplyKey,
                    successLabel = ReplySourceCategory.WEBHOOK.labelAr,
                    successCategory = ReplySourceCategory.WEBHOOK,
                    evaluated = evaluated,
                    elapsedMs = elapsed(startMs),
                    conversationKey = resolvedConvKey
                )
            }

            if (saStandaloneEligible && smartAdvisorClient != null) {
                evaluated += "Smart Advisor"
                val msg = buildIncomingMessage(request, cleanMessage)
                val shownIds = conversationMemoryManager?.getShownProductIds(resolvedConvKey).orEmpty()
                val result = smartAdvisorClient.send(
                    msg = msg,
                    idempotencyKey = if (request.isSandbox) "test-preview-$startMs" else request.msgKey,
                    excludeProductIds = shownIds
                )
                return mapExternalResult(
                    result = result,
                    replyKey = "reply",
                    successLabel = ReplySourceCategory.SMART_ADVISOR.labelAr,
                    successCategory = ReplySourceCategory.SMART_ADVISOR,
                    evaluated = evaluated,
                    elapsedMs = elapsed(startMs),
                    conversationKey = resolvedConvKey
                )
            }
        }

        val err = lastAiOutcome?.redactedError ?: directAiError
        val noMatchText = if (request.isSandbox) {
            if (err != null) "تعذر توليد رد من الذكاء الاصطناعي ($err)." else "لم تطابق الرسالة أي قاعدة أو قائمة أو خدمة ذكية أو خادم نشط حالياً."
        } else {
            ""
        }
        val finalClassification = lastAiOutcome?.processingClassification ?: err?.let {
            MessageProcessingClassification(
                status = if (lastAiOutcome?.engineFailureCategory == EngineFailureCategory.TIMEOUT) {
                    MessageProcessingStatus.TIMEOUT
                } else {
                    MessageProcessingStatus.SERVICE_ERROR
                },
                failureReason = it,
                source = lastAiOutcome?.activeEngine ?: aiSettings.provider.name
            )
        }
        return Decision(
            matched = false,
            replyText = noMatchText,
            displayReplyText = noMatchText,
            sourceLabel = if (err != null) ReplySourceCategory.ERROR.labelAr else ReplySourceCategory.NO_MATCH.labelAr,
            sourceCategory = if (err != null) ReplySourceCategory.ERROR else ReplySourceCategory.NO_MATCH,
            evaluatedPath = evaluated,
            latencyMs = elapsed(startMs),
            conversationKey = resolvedConvKey,
            usedSmartCollaboration = usedSmartCollab,
            usedNewApi = lastAiOutcome?.usedNewApi == true,
            switchedToQwen = lastAiOutcome?.switchedToQwen == true,
            calledSmartAdvisor = lastAiOutcome?.calledSmartAdvisor == true,
            calledWebhook = lastAiOutcome?.calledWebhook == true,
            saToolReason = lastAiOutcome?.saToolReason.orEmpty(),
            webhookToolReason = lastAiOutcome?.webhookToolReason.orEmpty(),
            webhookDataSnippet = lastAiOutcome?.webhookDataSnippet.orEmpty(),
            webhookStatusCode = lastAiOutcome?.webhookStatusCode,
            invokedTools = lastAiOutcome?.invokedTools.orEmpty(),
            toolRoundsUsed = lastAiOutcome?.toolRoundsUsed ?: 0,
            stageLatenciesMs = lastAiOutcome?.stageLatenciesMs.orEmpty(),
            redactedError = err,
            processingClassification = finalClassification
        )
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

    private fun resolveConversationKey(request: Request): String {
        request.conversationKeyOverride?.takeIf { it.isNotBlank() }?.let { override ->
            if (override != UnifiedReplyTester.DEFAULT_TEST_CONVERSATION_KEY) {
                return override
            }
            if (request.isGroup || request.phone.isNotBlank()) {
                return ConversationKey.build(
                    packageName = request.packageName,
                    senderName = request.senderName,
                    phone = request.phone,
                    groupName = if (request.isGroup) request.groupName ?: "مجموعة تجريبية" else request.groupName,
                    isGroup = request.isGroup
                )
            }
            return override
        }
        return ConversationKey.build(
            packageName = request.packageName,
            senderName = request.senderName,
            phone = request.phone,
            groupName = request.groupName,
            isGroup = request.isGroup
        )
    }

    private fun extractNonConclusiveRuleHint(
        rule: ReplyRuleEntity,
        resolved: ResolvedRuleContent
    ): String? {
        val rawText = resolved.replyText.trim()
        val hasImage = resolved.replyType != ReplyRuleEntity.REPLY_TYPE_TEXT && !resolved.imageUri.isNullOrBlank()
        if (rawText.isBlank() && !hasImage) {
            return "تمت مطابقة الكلمة المفتاحية (${rule.keyword}) بدون نص رد ثابت."
        }
        val marker = listOf("[AI]", "{{ai_context}}", "{{ai_assist}}")
            .firstOrNull { rawText.contains(it, ignoreCase = true) }
        if (marker != null) {
            val cleaned = rawText
                .replace("[AI]", "", ignoreCase = true)
                .replace("{{ai_context}}", "", ignoreCase = true)
                .replace("{{ai_assist}}", "", ignoreCase = true)
                .trim()
            return cleaned.ifBlank { "تمت مطابقة الكلمة المفتاحية (${rule.keyword}) للاستعانة بها في السياق." }
        }
        return null
    }

    private fun buildDirectAiSystemPrompt(
        aiSettings: AiSettingsState,
        keywordContextHint: String
    ): String {
        return buildString {
            if (aiSettings.systemPrompt.isNotBlank()) {
                append(aiSettings.systemPrompt.trim()).append("\n\n")
            }
            append("ضوابط الرد:\n")
            append("- النبرة: ").append(aiSettings.effectiveToneInstruction()).append('\n')
            append("- الطول: ").append(aiSettings.effectiveLengthInstruction()).append('\n')
            append("- اللغة: ").append(aiSettings.effectiveLanguageInstruction())
            if (keywordContextHint.isNotBlank()) {
                append("\n- تلميح قاعدة الكلمات المفتاحية: ").append(keywordContextHint)
            }
        }.trim()
    }

    private fun buildIncomingMessage(request: Request, cleanMessage: String): IncomingMessage {
        return IncomingMessage(
            app = request.packageName,
            sender = request.senderName,
            senderName = request.senderName,
            message = cleanMessage,
            phone = request.phone,
            groupName = request.groupName.orEmpty(),
            isGroup = request.isGroup,
            notificationId = request.msgKey.ifBlank { "unified-${request.receivedAtMs}" },
            timestamp = request.receivedAtMs
        )
    }

    private fun mapExternalResult(
        result: HttpTransport.Result,
        replyKey: String,
        successLabel: String,
        successCategory: ReplySourceCategory,
        evaluated: List<String>,
        elapsedMs: Long,
        conversationKey: String
    ): Decision {
        return when (result) {
            is HttpTransport.Result.Success -> {
                if (successCategory == ReplySourceCategory.SMART_ADVISOR) {
                    val parsed = SmartAdvisorResponseParser.parseReplyEndpoint(
                        body = result.body,
                        preferredReplyKey = replyKey
                    )
                    if (parsed != null) {
                        val shownIds = parsed.products.mapNotNull { it.productId }
                        if (shownIds.isNotEmpty()) {
                            conversationMemoryManager?.recordShownProductIds(conversationKey, shownIds)
                        }
                        if (parsed.responseType == ResponseType.PRODUCT_IMAGE && parsed.selectedProduct != null) {
                            val caption = SensitiveDataRedactor.scrub(
                                parsed.selectedProduct.buildCaption(),
                                secretVault
                            )
                            val fallbackText = SensitiveDataRedactor.scrub(
                                parsed.replyText.ifBlank { parsed.selectedProduct.buildTextFallbackReply() },
                                secretVault
                            )
                            return Decision(
                                matched = true,
                                replyText = caption,
                                displayReplyText = "[صورة منتج]\n$caption",
                                sourceLabel = successLabel,
                                sourceCategory = successCategory,
                                replyType = ReplyRuleEntity.REPLY_TYPE_BOTH,
                                imageUri = parsed.selectedProduct.imageUrl,
                                evaluatedPath = evaluated,
                                latencyMs = elapsedMs,
                                conversationKey = conversationKey,
                                calledSmartAdvisor = true,
                                detectedIntent = parsed.intent,
                                responseType = ResponseType.PRODUCT_IMAGE,
                                productMessage = parsed.selectedProduct,
                                products = parsed.products,
                                fallbackTextReply = fallbackText,
                                storeName = parsed.storeName
                            )
                        }
                        if (parsed.replyText.isNotBlank()) {
                            val scrubbed = SensitiveDataRedactor.scrub(parsed.replyText, secretVault)
                            return Decision(
                                matched = true,
                                replyText = scrubbed,
                                displayReplyText = scrubbed,
                                sourceLabel = successLabel,
                                sourceCategory = successCategory,
                                evaluatedPath = evaluated,
                                latencyMs = elapsedMs,
                                conversationKey = conversationKey,
                                calledSmartAdvisor = true,
                                detectedIntent = parsed.intent,
                                responseType = parsed.responseType,
                                products = parsed.products,
                                fallbackTextReply = scrubbed,
                                storeName = parsed.storeName
                            )
                        }
                    }
                }

                val reply = VariableEngine.extractReply(result.body, replyKey)
                if (!reply.isNullOrBlank()) {
                    val scrubbed = SensitiveDataRedactor.scrub(reply, secretVault)
                    Decision(
                        matched = true,
                        replyText = scrubbed,
                        displayReplyText = scrubbed,
                        sourceLabel = successLabel,
                        sourceCategory = successCategory,
                        evaluatedPath = evaluated,
                        latencyMs = elapsedMs,
                        conversationKey = conversationKey
                    )
                } else {
                    val msg = "استجاب الخادم بنجاح ولكن حقل الرد ($replyKey) فارغ."
                    Decision(
                        matched = false,
                        replyText = msg,
                        displayReplyText = msg,
                        sourceLabel = ReplySourceCategory.NO_MATCH.labelAr,
                        sourceCategory = ReplySourceCategory.NO_MATCH,
                        evaluatedPath = evaluated,
                        latencyMs = elapsedMs,
                        conversationKey = conversationKey,
                        processingClassification = MessageProcessingClassification(
                            status = MessageProcessingStatus.INVALID_RESPONSE,
                            source = successLabel,
                            failureReason = "empty_external_response"
                        )
                    )
                }
            }
            is HttpTransport.Result.Conflict -> {
                val msg = "تعارض من الخادم (HTTP 409): الطلب قيد المعالجة مسبقاً."
                Decision(
                    matched = false,
                    replyText = msg,
                    displayReplyText = msg,
                    sourceLabel = ReplySourceCategory.ERROR.labelAr,
                    sourceCategory = ReplySourceCategory.ERROR,
                    evaluatedPath = evaluated,
                    latencyMs = elapsedMs,
                    conversationKey = conversationKey,
                    processingClassification = MessageProcessingClassification(
                        status = MessageProcessingStatus.SERVICE_ERROR,
                        source = successLabel,
                        failureReason = "http_409_conflict"
                    )
                )
            }
            is HttpTransport.Result.Failure -> {
                val safeErr = SensitiveDataRedactor.scrub(result.error, secretVault)
                val msg = "تعذر الرد من الخادم: $safeErr"
                val isTimeout = result.code == 408 || result.code == 504 ||
                    safeErr.contains("timeout", ignoreCase = true) ||
                    safeErr.contains("timed out", ignoreCase = true) ||
                    safeErr.contains("مهلة", ignoreCase = true)
                Decision(
                    matched = false,
                    replyText = msg,
                    displayReplyText = msg,
                    sourceLabel = ReplySourceCategory.ERROR.labelAr,
                    sourceCategory = ReplySourceCategory.ERROR,
                    evaluatedPath = evaluated,
                    latencyMs = elapsedMs,
                    conversationKey = conversationKey,
                    redactedError = safeErr,
                    processingClassification = MessageProcessingClassification(
                        status = if (isTimeout) MessageProcessingStatus.TIMEOUT else MessageProcessingStatus.SERVICE_ERROR,
                        source = successLabel,
                        failureReason = safeErr
                    )
                )
            }
        }
    }

    private fun elapsed(startMs: Long): Long = (clockMs() - startMs).coerceAtLeast(0L)
}
