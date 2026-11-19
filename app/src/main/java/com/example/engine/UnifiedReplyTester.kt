package com.example.engine

import com.example.data.local.entity.GroupReplyMode
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.WebhookMode
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import javax.inject.Inject
import javax.inject.Singleton

enum class ReplySourceCategory(val labelAr: String) {
    DRIVING_MODE("وضع القيادة"),
    MENU_REPLY("القائمة التفاعلية"),
    LOCAL_RULE("قاعدة محلية"),
    AI_ENGINE("الذكاء الاصطناعي"),
    WILDCARD_RULE("قاعدة افتراضية (*)"),
    WEBHOOK("خادم Webhook"),
    SMART_ADVISOR("Smart Advisor"),
    POLICY_BLOCKED("محجوب بالسياسة"),
    NO_MATCH("لا يوجد تطابق"),
    ERROR("خطأ في الخادم")
}

data class UnifiedTestResult(
    val matched: Boolean,
    val replyText: String,
    val sourceLabel: String,
    val sourceCategory: ReplySourceCategory,
    val imageUri: String? = null,
    val evaluatedPath: List<String> = emptyList(),
    val latencyMs: Long = 0L,
    val conversationKeyUsed: String = "",
    val matchedRuleKeyword: String = "",
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
    val redactedError: String? = null
)

@Singleton
class UnifiedReplyTester internal constructor(
    private val repository: AutoMateRepository,
    private val menuReplyManager: MenuReplyManager,
    private val aiSettingsPrefs: AiSettingsPrefs,
    private val aiReplyEngine: AIReplyEngine,
    private val secretVault: SecretVault,
    private val serverRepo: ServerConfigRepository,
    private val webhookClient: WebhookClient,
    private val smartAdvisorClient: SmartAdvisorClient,
    private val smartAdvisorPrefs: SmartAdvisorPrefs,
    private val conversationMemoryManager: ConversationMemoryManager? = null,
    private val smartCollaborationCoordinator: SmartCollaborationCoordinator? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
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
        smartCollaborationCoordinator: SmartCollaborationCoordinator
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
        clockMs = { System.currentTimeMillis() }
    )

    internal constructor(
        repository: AutoMateRepository,
        menuReplyManager: MenuReplyManager,
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        secretVault: SecretVault,
        serverRepo: ServerConfigRepository,
        webhookClient: WebhookClient,
        smartAdvisorClient: SmartAdvisorClient,
        smartAdvisorPrefs: SmartAdvisorPrefs,
        clockMs: () -> Long
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
        conversationMemoryManager = null,
        smartCollaborationCoordinator = null,
        clockMs = clockMs
    )

    suspend fun evaluate(
        message: String,
        senderName: String = DEFAULT_TEST_SENDER,
        isGroup: Boolean = false,
        conversationKey: String = DEFAULT_TEST_CONVERSATION_KEY,
        phone: String = ""
    ): UnifiedTestResult {
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) {
            return UnifiedTestResult(
                matched = false,
                replyText = "أرسل رسالة غير فارغة لاختبار الرد التلقائي.",
                sourceLabel = ReplySourceCategory.NO_MATCH.labelAr,
                sourceCategory = ReplySourceCategory.NO_MATCH,
                conversationKeyUsed = conversationKey
            )
        }

        val startMs = clockMs()
        val evaluated = mutableListOf<String>()
        val settings = repository.getCurrentSettings()
        val serverConfig = serverRepo.get()
        val webhookEnabled = serverConfig?.isEnabled == true
        val saEnabled = smartAdvisorPrefs.enabled
        val aiSettings = aiSettingsPrefs.current()
        val smartCollabActive = aiSettings.enabled && aiSettings.saCollaborationEnabled

        val resolvedConvKey = if (conversationKey == DEFAULT_TEST_CONVERSATION_KEY && (isGroup || phone.isNotBlank())) {
            ConversationKey.build(
                packageName = "com.whatsapp",
                senderName = senderName,
                phone = phone,
                groupName = if (isGroup) "مجموعة تجريبية" else null,
                isGroup = isGroup
            )
        } else {
            conversationKey
        }

        val webhookStandaloneEligible = webhookEnabled && !aiSettings.enabled &&
            (serverConfig?.effectiveWebhookMode() == WebhookMode.STANDALONE_FALLBACK)
        val saStandaloneEligible = saEnabled && !aiSettings.enabled

        if (isGroup) {
            evaluated += "سياسة المجموعات"
            val groupMode = serverConfig?.let {
                runCatching { GroupReplyMode.valueOf(it.sendOnGroup) }.getOrDefault(GroupReplyMode.KEYWORD_ONLY)
            } ?: GroupReplyMode.KEYWORD_ONLY
            val localEnabled = settings.isAutoReplyEnabled || settings.isDrivingModeEnabled || aiSettings.enabled
            val groupBlocked = (localEnabled && !settings.replyInGroups) ||
                (!localEnabled && webhookStandaloneEligible && groupMode == GroupReplyMode.NEVER) ||
                (!localEnabled && !webhookStandaloneEligible && saStandaloneEligible && !settings.replyInGroups)
            if (groupBlocked) {
                return UnifiedTestResult(
                    matched = false,
                    replyText = "تم تخطي الرسالة: الرد التلقائي في المجموعات معطّل في الإعدادات.",
                    sourceLabel = ReplySourceCategory.POLICY_BLOCKED.labelAr,
                    sourceCategory = ReplySourceCategory.POLICY_BLOCKED,
                    evaluatedPath = evaluated,
                    latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                    conversationKeyUsed = resolvedConvKey
                )
            }
        }

        // 1. Driving Mode
        if (settings.isDrivingModeEnabled && settings.drivingModeReplyText.isNotBlank()) {
            evaluated += "وضع القيادة"
            return UnifiedTestResult(
                matched = true,
                replyText = settings.drivingModeReplyText,
                sourceLabel = ReplySourceCategory.DRIVING_MODE.labelAr,
                sourceCategory = ReplySourceCategory.DRIVING_MODE,
                evaluatedPath = evaluated,
                latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                conversationKeyUsed = resolvedConvKey
            )
        }

        // 2. Interactive Menu
        evaluated += "القوائم التفاعلية"
        val menuReply = menuReplyManager.processConversationMessage(resolvedConvKey, cleanMessage)
        if (!menuReply.isNullOrBlank()) {
            return UnifiedTestResult(
                matched = true,
                replyText = menuReply,
                sourceLabel = ReplySourceCategory.MENU_REPLY.labelAr,
                sourceCategory = ReplySourceCategory.MENU_REPLY,
                evaluatedPath = evaluated,
                latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                conversationKeyUsed = resolvedConvKey
            )
        }

        // 3. Specific Local Rules (excluding wildcard "*")
        evaluated += "القواعد المحلية"
        val enabledRules = repository.getEnabledRulesOnce()
        val specificRules = enabledRules.filter { it.keyword.trim() != "*" }
        val matchedSpecific = RuleMatcher.findBestMatch(cleanMessage, specificRules)
        if (matchedSpecific != null) {
            return ruleResult(
                rule = matchedSpecific,
                sourceLabel = "قاعدة: ${matchedSpecific.keyword}",
                category = ReplySourceCategory.LOCAL_RULE,
                evaluatedPath = evaluated,
                latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                conversationKey = resolvedConvKey,
                incomingSample = buildIncomingMessage(cleanMessage, senderName, isGroup, phone, startMs)
            )
        }

        // 4. Smart Collaboration / AI Engine (New API -> Ollama/Qwen -> Smart Advisor 6.5.0)
        var lastAiOutcome: SmartCollaborationCoordinator.CollaborationOutcome? = null
        if (aiSettings.enabled) {
            if (smartCollaborationCoordinator != null) {
                evaluated += if (aiSettings.primaryBaseUrl.isNotBlank() || aiSettings.provider == AIReplyEngine.Provider.NEW_API) {
                    "New API"
                } else {
                    "AI (${aiSettings.provider.labelAr})"
                }
                val collab = smartCollaborationCoordinator.collaborate(
                    SmartCollaborationCoordinator.CollaborationInput(
                        packageName = "com.whatsapp",
                        senderName = senderName,
                        rawTitle = senderName,
                        phone = phone,
                        groupName = if (isGroup) "مجموعة تجريبية" else null,
                        isGroup = isGroup,
                        messageText = cleanMessage,
                        isSandbox = true,
                        conversationKeyOverride = resolvedConvKey
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

                if (!collab.replyText.isNullOrBlank()) {
                    conversationMemoryManager?.commitAssistantTurnImmediately(
                        conversationKey = resolvedConvKey,
                        assistantReply = collab.replyText,
                        contextWindowPairs = aiSettings.contextWindowPairs,
                        isSandbox = true
                    )
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
                    return UnifiedTestResult(
                        matched = true,
                        replyText = collab.replyText,
                        sourceLabel = label,
                        sourceCategory = category,
                        evaluatedPath = evaluated,
                        latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                        conversationKeyUsed = collab.conversationKey,
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
                        redactedError = collab.redactedError
                    )
                }
            } else {
                evaluated += "AI (${aiSettings.provider.labelAr})"
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
                val aiReply = aiReplyEngine.generateReplyWithFallback(
                    message = cleanMessage,
                    primaryConfig = aiConfig,
                    context = "المرسل: $senderName"
                )
                if (!aiReply.isNullOrBlank()) {
                    return UnifiedTestResult(
                        matched = true,
                        replyText = aiReply,
                        sourceLabel = "الذكاء الاصطناعي (${aiSettings.provider.labelAr})",
                        sourceCategory = ReplySourceCategory.AI_ENGINE,
                        evaluatedPath = evaluated,
                        latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                        conversationKeyUsed = resolvedConvKey
                    )
                }
            }
        }

        // 5. Wildcard Rule "*" (strictly after AI/Collaboration)
        val wildcardMatch = RuleMatcher.findBestMatch(cleanMessage, enabledRules)
        if (wildcardMatch != null) {
            evaluated += "القاعدة الافتراضية (*)"
            return ruleResult(
                rule = wildcardMatch,
                sourceLabel = ReplySourceCategory.WILDCARD_RULE.labelAr,
                category = ReplySourceCategory.WILDCARD_RULE,
                evaluatedPath = evaluated,
                latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                conversationKey = resolvedConvKey,
                collabOutcome = lastAiOutcome,
                incomingSample = buildIncomingMessage(cleanMessage, senderName, isGroup, phone, startMs)
            )
        }

        // Explicit safe fallback reply when AI failed and no wildcard rule exists
        if (aiSettings.enabled && aiSettings.safeFallbackReply.isNotBlank()) {
            evaluated += "الرد الآمن البديل"
            return UnifiedTestResult(
                matched = true,
                replyText = aiSettings.safeFallbackReply,
                sourceLabel = "الرد الآمن البديل",
                sourceCategory = ReplySourceCategory.AI_ENGINE,
                evaluatedPath = evaluated,
                latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
                conversationKeyUsed = resolvedConvKey,
                usedNewApi = lastAiOutcome?.usedNewApi == true,
                switchedToQwen = lastAiOutcome?.switchedToQwen == true,
                redactedError = lastAiOutcome?.redactedError
            )
        }

        // 6. Standalone Webhook / Standalone Legacy Smart Advisor (STRICTLY disabled when AI Orchestrator is active!)
        if (webhookStandaloneEligible && serverConfig != null) {
            evaluated += "خادم Webhook"
            val msg = buildIncomingMessage(cleanMessage, senderName, isGroup, phone, startMs)
            val result = webhookClient.send(
                config = serverConfig,
                msg = msg,
                idempotencyKey = "test-preview-$startMs"
            )
            return mapExternalResult(
                result = result,
                replyKey = serverConfig.responseReplyKey,
                successLabel = ReplySourceCategory.WEBHOOK.labelAr,
                successCategory = ReplySourceCategory.WEBHOOK,
                evaluated = evaluated,
                elapsedMs = (clockMs() - startMs).coerceAtLeast(0L),
                conversationKey = resolvedConvKey
            )
        }

        if (saStandaloneEligible) {
            evaluated += "Smart Advisor"
            val msg = buildIncomingMessage(cleanMessage, senderName, isGroup, phone, startMs)
            val result = smartAdvisorClient.send(
                msg = msg,
                idempotencyKey = "test-preview-$startMs"
            )
            return mapExternalResult(
                result = result,
                replyKey = "reply",
                successLabel = ReplySourceCategory.SMART_ADVISOR.labelAr,
                successCategory = ReplySourceCategory.SMART_ADVISOR,
                evaluated = evaluated,
                elapsedMs = (clockMs() - startMs).coerceAtLeast(0L),
                conversationKey = resolvedConvKey
            )
        }

        val noMatchText = if (lastAiOutcome?.redactedError != null) {
            "تعذر توليد رد من الذكاء الاصطناعي (${lastAiOutcome.redactedError})."
        } else {
            "لم تطابق الرسالة أي قاعدة أو قائمة أو خدمة ذكية أو خادم نشط حالياً."
        }
        return UnifiedTestResult(
            matched = false,
            replyText = noMatchText,
            sourceLabel = if (lastAiOutcome?.redactedError != null) ReplySourceCategory.ERROR.labelAr else ReplySourceCategory.NO_MATCH.labelAr,
            sourceCategory = if (lastAiOutcome?.redactedError != null) ReplySourceCategory.ERROR else ReplySourceCategory.NO_MATCH,
            evaluatedPath = evaluated,
            latencyMs = (clockMs() - startMs).coerceAtLeast(0L),
            conversationKeyUsed = resolvedConvKey,
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
            redactedError = lastAiOutcome?.redactedError
        )
    }

    fun resetSession(conversationKey: String = DEFAULT_TEST_CONVERSATION_KEY) {
        menuReplyManager.clearActiveMenu(conversationKey)
        conversationMemoryManager?.clearSandboxConversation(conversationKey)
        conversationMemoryManager?.clearSandboxConversation(
            ConversationKey.build("com.whatsapp", DEFAULT_TEST_SENDER, isGroup = true, groupName = "مجموعة تجريبية")
        )
        conversationMemoryManager?.clearSandboxConversation(
            ConversationKey.build("com.whatsapp", DEFAULT_TEST_SENDER, isGroup = false)
        )
    }

    private suspend fun ruleResult(
        rule: ReplyRuleEntity,
        sourceLabel: String,
        category: ReplySourceCategory,
        evaluatedPath: List<String>,
        latencyMs: Long,
        conversationKey: String,
        collabOutcome: SmartCollaborationCoordinator.CollaborationOutcome? = null,
        incomingSample: IncomingMessage? = null
    ): UnifiedTestResult {
        val resolved = repository.resolveRuleContent(rule)
        val rawText = if (incomingSample != null) {
            VariableEngine.resolveTemplate(resolved.replyText, incomingSample)
        } else {
            resolved.replyText
        }
        val hasImage = resolved.replyType != ReplyRuleEntity.REPLY_TYPE_TEXT && !resolved.imageUri.isNullOrBlank()
        val displayReply = when (resolved.replyType) {
            ReplyRuleEntity.REPLY_TYPE_IMAGE -> "[صورة] $rawText".trim()
            ReplyRuleEntity.REPLY_TYPE_BOTH -> "$rawText\n[+ صورة مرفقة]".trim()
            else -> rawText
        }
        val finalLabel = if (!resolved.templateTitle.isNullOrBlank()) {
            "$sourceLabel • قالب: ${resolved.templateTitle}"
        } else {
            sourceLabel
        }
        return UnifiedTestResult(
            matched = true,
            replyText = displayReply,
            sourceLabel = finalLabel,
            sourceCategory = category,
            imageUri = if (hasImage) resolved.imageUri else null,
            evaluatedPath = evaluatedPath,
            latencyMs = latencyMs,
            conversationKeyUsed = conversationKey,
            matchedRuleKeyword = rule.keyword,
            usedNewApi = collabOutcome?.usedNewApi == true,
            switchedToQwen = collabOutcome?.switchedToQwen == true,
            redactedError = collabOutcome?.redactedError
        )
    }

    private fun buildIncomingMessage(
        cleanMessage: String,
        senderName: String,
        isGroup: Boolean,
        phone: String,
        timestamp: Long
    ): IncomingMessage {
        return IncomingMessage(
            app = "com.whatsapp",
            sender = senderName,
            senderName = senderName,
            message = cleanMessage,
            phone = phone,
            groupName = if (isGroup) "مجموعة تجريبية" else "",
            isGroup = isGroup,
            notificationId = "unified-test-$timestamp",
            timestamp = timestamp
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
    ): UnifiedTestResult {
        return when (result) {
            is HttpTransport.Result.Success -> {
                val reply = VariableEngine.extractReply(result.body, replyKey)
                if (!reply.isNullOrBlank()) {
                    UnifiedTestResult(
                        matched = true,
                        replyText = reply,
                        sourceLabel = successLabel,
                        sourceCategory = successCategory,
                        evaluatedPath = evaluated,
                        latencyMs = elapsedMs,
                        conversationKeyUsed = conversationKey
                    )
                } else {
                    UnifiedTestResult(
                        matched = false,
                        replyText = "استجاب الخادم بنجاح ولكن حقل الرد ($replyKey) فارغ.",
                        sourceLabel = ReplySourceCategory.NO_MATCH.labelAr,
                        sourceCategory = ReplySourceCategory.NO_MATCH,
                        evaluatedPath = evaluated,
                        latencyMs = elapsedMs,
                        conversationKeyUsed = conversationKey
                    )
                }
            }
            is HttpTransport.Result.Conflict -> UnifiedTestResult(
                matched = false,
                replyText = "تعارض من الخادم (HTTP 409): الطلب قيد المعالجة مسبقاً.",
                sourceLabel = ReplySourceCategory.ERROR.labelAr,
                sourceCategory = ReplySourceCategory.ERROR,
                evaluatedPath = evaluated,
                latencyMs = elapsedMs,
                conversationKeyUsed = conversationKey
            )
            is HttpTransport.Result.Failure -> UnifiedTestResult(
                matched = false,
                replyText = "تعذر الرد من الخادم: ${result.error}",
                sourceLabel = ReplySourceCategory.ERROR.labelAr,
                sourceCategory = ReplySourceCategory.ERROR,
                evaluatedPath = evaluated,
                latencyMs = elapsedMs,
                conversationKeyUsed = conversationKey,
                redactedError = result.error
            )
        }
    }

    companion object {
        const val DEFAULT_TEST_SENDER = "مستخدم الاختبار"
        const val DEFAULT_TEST_CONVERSATION_KEY = "test_unified_reply:default"
    }
}
