package com.example.engine

import com.example.data.preferences.AiSettingsPrefs
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
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
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val injectedUnifiedService: UnifiedSmartReplyService? = null
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
        unifiedSmartReplyService: UnifiedSmartReplyService
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
        injectedUnifiedService = unifiedSmartReplyService
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
        conversationMemoryManager: ConversationMemoryManager? = null,
        smartCollaborationCoordinator: SmartCollaborationCoordinator? = null,
        clockMs: () -> Long = { System.currentTimeMillis() }
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
        clockMs = clockMs,
        injectedUnifiedService = null
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
        clockMs = clockMs,
        injectedUnifiedService = null
    )

    private val unifiedService: UnifiedSmartReplyService by lazy {
        injectedUnifiedService ?: UnifiedSmartReplyService(
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
            clockMs = clockMs
        )
    }

    suspend fun evaluate(
        message: String,
        senderName: String = DEFAULT_TEST_SENDER,
        isGroup: Boolean = false,
        conversationKey: String = DEFAULT_TEST_CONVERSATION_KEY,
        phone: String = ""
    ): UnifiedTestResult {
        val now = clockMs()
        val decision = unifiedService.resolve(
            UnifiedSmartReplyService.Request(
                packageName = "com.whatsapp",
                senderName = senderName,
                rawTitle = senderName,
                phone = phone,
                groupName = if (isGroup) "مجموعة تجريبية" else null,
                isGroup = isGroup,
                messageText = message,
                msgKey = "unified-test-$now",
                conversationKeyOverride = conversationKey,
                isSandbox = true,
                checkPolicy = true,
                includeExternalStandalone = true,
                requireLocalActiveWhenCheckingPolicy = false,
                receivedAtMs = now
            )
        )

        return UnifiedTestResult(
            matched = decision.matched,
            replyText = decision.displayReplyText,
            sourceLabel = decision.sourceLabel,
            sourceCategory = decision.sourceCategory,
            imageUri = decision.imageUri,
            evaluatedPath = decision.evaluatedPath,
            latencyMs = decision.latencyMs,
            conversationKeyUsed = decision.conversationKey,
            matchedRuleKeyword = decision.matchedRule?.keyword.orEmpty(),
            usedNewApi = decision.usedNewApi,
            activeEngineLabel = decision.activeEngineLabel,
            switchedToQwen = decision.switchedToQwen,
            calledSmartAdvisor = decision.calledSmartAdvisor,
            calledWebhook = decision.calledWebhook,
            detectedIntent = decision.detectedIntent,
            saToolReason = decision.saToolReason,
            webhookToolReason = decision.webhookToolReason,
            webhookDataSnippet = decision.webhookDataSnippet,
            webhookStatusCode = decision.webhookStatusCode,
            invokedTools = decision.invokedTools,
            toolRoundsUsed = decision.toolRoundsUsed,
            verifiedStoreDataUsed = decision.verifiedStoreDataUsed,
            verifiedDataSnippet = decision.verifiedDataSnippet,
            memoryLoaded = decision.memoryLoaded,
            memoryUpdated = decision.memoryUpdated,
            needsHuman = decision.needsHuman,
            stageLatenciesMs = decision.stageLatenciesMs,
            redactedError = decision.redactedError
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

    companion object {
        const val DEFAULT_TEST_SENDER = "مستخدم الاختبار"
        const val DEFAULT_TEST_CONVERSATION_KEY = "test_unified_reply:default"
    }
}
