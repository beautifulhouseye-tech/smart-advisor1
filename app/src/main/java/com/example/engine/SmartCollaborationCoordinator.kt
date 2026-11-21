package com.example.engine

import com.example.data.local.entity.ServerConfigEntity
import com.example.data.local.entity.WebhookMode
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.UnunderstoodFallbackMode
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.network.smartadvisor.SmartAdvisorResponseParser
import com.example.worker.MemoryOutboxCoordinator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartCollaborationCoordinator(
    private val aiSettingsPrefs: AiSettingsPrefs,
    private val aiReplyEngine: AIReplyEngine,
    private val smartAdvisorClient: SmartAdvisorClient,
    private val smartAdvisorPrefs: SmartAdvisorPrefs,
    private val conversationMemoryManager: ConversationMemoryManager,
    private val memoryOutboxCoordinator: MemoryOutboxCoordinator?,
    private val secretVault: SecretVault,
    private val serverRepo: ServerConfigRepository? = null,
    private val webhookClient: WebhookClient? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val diagnosticLogger: SafeDiagnosticLogger = SafeDiagnosticLogger(secretVault, clockMs)
) {
    @Inject
    constructor(
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        smartAdvisorClient: SmartAdvisorClient,
        smartAdvisorPrefs: SmartAdvisorPrefs,
        conversationMemoryManager: ConversationMemoryManager,
        memoryOutboxCoordinator: MemoryOutboxCoordinator,
        secretVault: SecretVault,
        serverRepo: ServerConfigRepository,
        webhookClient: WebhookClient,
        diagnosticLogger: SafeDiagnosticLogger
    ) : this(
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        smartAdvisorClient = smartAdvisorClient,
        smartAdvisorPrefs = smartAdvisorPrefs,
        conversationMemoryManager = conversationMemoryManager,
        memoryOutboxCoordinator = memoryOutboxCoordinator,
        secretVault = secretVault,
        serverRepo = serverRepo,
        webhookClient = webhookClient,
        clockMs = { System.currentTimeMillis() },
        diagnosticLogger = diagnosticLogger
    )

    internal constructor(
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        smartAdvisorClient: SmartAdvisorClient,
        smartAdvisorPrefs: SmartAdvisorPrefs,
        conversationMemoryManager: ConversationMemoryManager,
        memoryOutboxCoordinator: MemoryOutboxCoordinator?,
        secretVault: SecretVault,
        clockMs: () -> Long
    ) : this(
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        smartAdvisorClient = smartAdvisorClient,
        smartAdvisorPrefs = smartAdvisorPrefs,
        conversationMemoryManager = conversationMemoryManager,
        memoryOutboxCoordinator = memoryOutboxCoordinator,
        secretVault = secretVault,
        serverRepo = null,
        webhookClient = null,
        clockMs = clockMs,
        diagnosticLogger = SafeDiagnosticLogger(secretVault, clockMs)
    )

    data class CollaborationInput(
        val packageName: String,
        val senderName: String,
        val rawTitle: String = senderName,
        val phone: String = "",
        val groupName: String? = null,
        val isGroup: Boolean = false,
        val messageText: String,
        val isSandbox: Boolean = false,
        val conversationKeyOverride: String? = null,
        val messageId: String = "",
        val keywordContextHint: String = ""
    )

    data class CollaborationOutcome(
        val replyText: String?,
        val conversationKey: String,
        val usedNewApi: Boolean = false,
        val usedPrimaryEngine: String = "",
        val activeEngine: String = "",
        val switchedToQwen: Boolean = false,
        val calledSmartAdvisor: Boolean = false,
        val calledWebhook: Boolean = false,
        val saIntent: String = "general",
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
        val consentRequiredForMemory: Boolean = false,
        val safeFallbackUsed: Boolean = false,
        val redactedError: String? = null,
        val stageLatenciesMs: Map<String, Long> = emptyMap(),
        val currentTopic: String = "",
        val responseType: ResponseType = ResponseType.TEXT_MESSAGE,
        val productMessage: ProductMessage? = null,
        val products: List<ProductMessage> = emptyList(),
        val storeName: String = "",
        val fallbackTextReply: String = "",
        val processingClassification: MessageProcessingClassification? = null,
        val suppressedByUnknownIntent: Boolean = false,
        val engineFailureCategory: EngineFailureCategory = EngineFailureCategory.NONE
    )

    private data class CollectedToolNote(
        val toolLabel: String,
        val trustLabel: String,
        val query: String,
        val reason: String,
        val content: String,
        val ok: Boolean
    )

    suspend fun collaborate(input: CollaborationInput): CollaborationOutcome {
        val aiSettings = aiSettingsPrefs.current()
        val conversationKey = input.conversationKeyOverride?.takeIf { it.isNotBlank() }
            ?: ConversationKey.build(
                packageName = input.packageName,
                senderName = input.senderName,
                phone = input.phone,
                groupName = input.groupName,
                isGroup = input.isGroup
            )

        val latencies = linkedMapOf<String, Long>()
        val memoryStart = clockMs()
        val contextEntity = conversationMemoryManager.recordIncomingUserMessage(
            conversationKey = conversationKey,
            packageName = input.packageName,
            isGroup = input.isGroup,
            displayName = input.senderName,
            phone = input.phone,
            messageText = input.messageText,
            contextWindowPairs = aiSettings.contextWindowPairs,
            isSandbox = input.isSandbox
        )
        latencies["context"] = (clockMs() - memoryStart).coerceAtLeast(0L)

        val saAvailable = smartAdvisorPrefs.enabled &&
            aiSettings.saCollaborationEnabled &&
            smartAdvisorClient.assistantBaseUrl().isNotBlank()

        val serverConfig = serverRepo?.get()
        val webhookToolAvailable = aiSettings.webhookToolEnabled &&
            webhookClient != null &&
            serverConfig != null &&
            serverConfig.isEnabled &&
            serverConfig.effectiveWebhookMode() == WebhookMode.AI_TOOL &&
            serverConfig.url.isNotBlank()

        var memoryLoaded = false
        var serverMemoryText = ""
        var capabilities: SmartAdvisorClient.SaCapabilities? = null

        if (saAvailable) {
            val capStart = clockMs()
            capabilities = smartAdvisorClient.fetchCapabilities()
            latencies["sa_capabilities"] = (clockMs() - capStart).coerceAtLeast(0L)

            if (aiSettings.useConversationContext &&
                aiSettings.syncCustomerMemory &&
                capabilities.memoryEnabled &&
                !input.isGroup &&
                contextEntity.customerKey.isNotBlank() &&
                (!capabilities.consentRequired || contextEntity.consentGranted)
            ) {
                val memLoadStart = clockMs()
                val memRes = smartAdvisorClient.getCustomerMemory(
                    customerKey = contextEntity.customerKey,
                    phone = input.phone
                )
                latencies["sa_memory_get"] = (clockMs() - memLoadStart).coerceAtLeast(0L)
                if (memRes.ok && memRes.memory != null) {
                    memoryLoaded = true
                    serverMemoryText = formatServerMemoryForPrompt(memRes.memory)
                    if (memRes.revision > contextEntity.lastMemoryRevision) {
                        conversationMemoryManager.updateMetadataAfterSync(
                            conversationKey = conversationKey,
                            revision = memRes.revision,
                            summary = memRes.memory.summary,
                            isSandbox = input.isSandbox
                        )
                    }
                }
            }
        }

        val recentTurns = if (aiSettings.useConversationContext) {
            conversationMemoryManager.buildTurnsForAi(
                context = contextEntity,
                excludeLastUserMessage = true
            )
        } else {
            emptyList()
        }
        val localContextBlock = if (aiSettings.useConversationContext) {
            conversationMemoryManager.buildContextSummaryBlock(contextEntity)
        } else {
            ""
        }
        val pass1SystemPrompt = buildPass1SystemInstruction(
            aiSettings = aiSettings,
            senderName = input.rawTitle.ifBlank { input.senderName },
            isGroup = input.isGroup,
            localContextBlock = localContextBlock,
            serverMemoryBlock = serverMemoryText,
            saAvailable = saAvailable,
            capabilities = capabilities,
            webhookToolAvailable = webhookToolAvailable,
            webhookConfig = serverConfig,
            keywordContextHint = input.keywordContextHint
        )

        val pass1Outcome = aiReplyEngine.runCollaborationTurn(
            settings = aiSettings,
            userMessage = input.messageText,
            systemInstruction = pass1SystemPrompt,
            recentTurns = recentTurns
        )
        latencies["ai_primary"] = pass1Outcome.primaryLatencyMs
        if (pass1Outcome.switchedToFallback && pass1Outcome.fallbackLatencyMs > 0L) {
            latencies["ai_fallback_qwen"] = pass1Outcome.fallbackLatencyMs
        }

        val decision1 = pass1Outcome.decision
        val usedNewApiFlag = aiSettings.provider == AIReplyEngine.Provider.NEW_API

        if (decision1 == null) {
            val failureCat = if (pass1Outcome.failureCategory != EngineFailureCategory.NONE) {
                pass1Outcome.failureCategory
            } else {
                EngineFailureCategory.SERVICE_ERROR
            }
            val techStatus = failureCat.toProcessingStatus()
            val errorMsg = pass1Outcome.redactedError ?: "تعذر الاتصال بمحركات الذكاء الاصطناعي"
            aiSettingsPrefs.updateHealthStatus(
                primaryStatus = "فشل أو متعذر",
                fallbackStatus = if (pass1Outcome.switchedToFallback) "فشل الاحتياطي" else aiSettings.lastFallbackStatus,
                lastErrorRedacted = errorMsg
            )
            diagnosticLogger.log(
                tag = if (failureCat == EngineFailureCategory.TIMEOUT) {
                    DiagnosticEventTag.TIMEOUT
                } else {
                    DiagnosticEventTag.SERVICE_ERROR
                },
                conversationId = conversationKey,
                messageId = input.messageId,
                detectedStatus = techStatus.name,
                decision = "TECHNICAL_ERROR_NO_UNKNOWN_INTENT_CONTROL",
                errorSource = pass1Outcome.activeEngineLabel,
                detail = errorMsg
            )
            return CollaborationOutcome(
                replyText = null,
                conversationKey = conversationKey,
                usedNewApi = usedNewApiFlag,
                usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
                activeEngine = pass1Outcome.activeEngineLabel,
                switchedToQwen = pass1Outcome.switchedToFallback,
                memoryLoaded = memoryLoaded,
                redactedError = pass1Outcome.redactedError,
                stageLatenciesMs = latencies,
                currentTopic = contextEntity.currentTopic,
                processingClassification = MessageProcessingClassification(
                    status = techStatus,
                    confidence = 0.0,
                    detectedIntent = "",
                    finalReply = null,
                    source = pass1Outcome.activeEngineLabel,
                    failureReason = errorMsg
                ),
                suppressedByUnknownIntent = false,
                engineFailureCategory = failureCat
            )
        }

        aiSettingsPrefs.updateHealthStatus(
            primaryStatus = if (!pass1Outcome.switchedToFallback) "متصل" else "متعطل (تم التحويل لـ Qwen)",
            fallbackStatus = if (pass1Outcome.switchedToFallback) "نشط (${aiSettings.effectiveOllamaModel()})" else aiSettings.lastFallbackStatus,
            lastErrorRedacted = pass1Outcome.redactedError ?: ""
        )

        val hasActiveContext = aiSettings.useConversationContext &&
            conversationMemoryManager.hasActiveContextualHistory(contextEntity, excludeLastUserMessage = true)
        val ununderstoodStatus = detectUnunderstoodStatus(
            decision = decision1,
            userMessage = input.messageText,
            hasActiveContext = hasActiveContext
        )
        if (ununderstoodStatus != null) {
            diagnosticLogger.log(
                tag = if (ununderstoodStatus == MessageProcessingStatus.LOW_CONFIDENCE) {
                    DiagnosticEventTag.LOW_CONFIDENCE
                } else {
                    DiagnosticEventTag.UNKNOWN_INTENT
                },
                conversationId = conversationKey,
                messageId = input.messageId,
                detectedStatus = ununderstoodStatus.name,
                decision = "EVALUATE_UNUNDERSTOOD_POLICY",
                errorSource = pass1Outcome.activeEngineLabel,
                detail = "intent=${decision1.intent} confidence=${decision1.confidence}"
            )
            val candidateMode = aiSettings.ununderstoodFallbackMode
            val suppressForLoop = !aiSettings.suppressReplyWhenUnunderstood &&
                conversationMemoryManager.shouldSuppressClarificationLoop(conversationKey, candidateMode)
            val (resolvedUnunderstoodReply, appliedMode) = UnunderstoodIntentPolicy.resolveUnunderstoodReply(
                aiSettings = aiSettings,
                status = ununderstoodStatus,
                suppressForLoop = suppressForLoop
            )
            if (resolvedUnunderstoodReply.isNullOrBlank()) {
                diagnosticLogger.log(
                    tag = DiagnosticEventTag.REPLY_SUPPRESSED,
                    conversationId = conversationKey,
                    messageId = input.messageId,
                    detectedStatus = ununderstoodStatus.name,
                    decision = if (suppressForLoop) "LOOP_PREVENTION_SUPPRESSED" else "SUPPRESS_SETTING_OR_NO_REPLY",
                    errorSource = pass1Outcome.activeEngineLabel
                )
                return CollaborationOutcome(
                    replyText = null,
                    conversationKey = conversationKey,
                    usedNewApi = usedNewApiFlag,
                    usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
                    activeEngine = pass1Outcome.activeEngineLabel,
                    switchedToQwen = pass1Outcome.switchedToFallback,
                    saIntent = decision1.intent,
                    memoryLoaded = memoryLoaded,
                    needsHuman = false,
                    redactedError = pass1Outcome.redactedError,
                    stageLatenciesMs = latencies,
                    currentTopic = contextEntity.currentTopic,
                    processingClassification = MessageProcessingClassification(
                        status = ununderstoodStatus,
                        confidence = decision1.confidence,
                        detectedIntent = decision1.intent,
                        finalReply = null,
                        source = pass1Outcome.activeEngineLabel,
                        failureReason = if (suppressForLoop) "clarification_loop_prevented" else "",
                        suppressedBySetting = true,
                        fallbackModeApplied = appliedMode
                    ),
                    suppressedByUnknownIntent = true,
                    engineFailureCategory = EngineFailureCategory.NONE
                )
            }

            if (appliedMode != null) {
                conversationMemoryManager.recordUnunderstoodFallbackSent(conversationKey, appliedMode)
            }
            diagnosticLogger.log(
                tag = DiagnosticEventTag.FALLBACK_USED,
                conversationId = conversationKey,
                messageId = input.messageId,
                detectedStatus = ununderstoodStatus.name,
                decision = appliedMode?.name ?: "UNUNDERSTOOD_FALLBACK",
                errorSource = pass1Outcome.activeEngineLabel
            )
            return CollaborationOutcome(
                replyText = resolvedUnunderstoodReply,
                conversationKey = conversationKey,
                usedNewApi = usedNewApiFlag,
                usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
                activeEngine = pass1Outcome.activeEngineLabel,
                switchedToQwen = pass1Outcome.switchedToFallback,
                saIntent = decision1.intent,
                memoryLoaded = memoryLoaded,
                needsHuman = decision1.needsHuman,
                safeFallbackUsed = true,
                redactedError = pass1Outcome.redactedError,
                stageLatenciesMs = latencies,
                currentTopic = contextEntity.currentTopic,
                fallbackTextReply = resolvedUnunderstoodReply,
                processingClassification = MessageProcessingClassification(
                    status = ununderstoodStatus,
                    confidence = decision1.confidence,
                    detectedIntent = decision1.intent,
                    finalReply = resolvedUnunderstoodReply,
                    source = pass1Outcome.activeEngineLabel,
                    suppressedBySetting = false,
                    fallbackModeApplied = appliedMode
                ),
                suppressedByUnknownIntent = false,
                engineFailureCategory = EngineFailureCategory.NONE
            )
        }

        var currentDecision: AIReplyEngine.AiStructuredDecision = decision1
        var activeEngineLabel = pass1Outcome.activeEngineLabel
        var switchedToQwen = pass1Outcome.switchedToFallback
        var calledSmartAdvisor = false
        var calledWebhook = false
        var verifiedStoreDataUsed = false
        var verifiedDataSnippet = ""
        var saToolReason = ""
        var webhookToolReason = ""
        var webhookDataSnippet = ""
        var webhookStatusCode: Int? = null
        var saIntent = SmartAdvisorClient.normalizeKnowledgeIntent(
            decision1.storeIntent.ifBlank { decision1.intent }
        )
        var finalRedactedError = pass1Outcome.redactedError
        var deliveryContainsEphemeralLink = false
        var lastVerifiedKnowledge: SmartAdvisorClient.SaKnowledgeResponse? = null
        var lastFailedKnowledge: SmartAdvisorClient.SaKnowledgeResponse? = null

        val invokedTools = linkedSetOf<String>()
        val executedSignatures = mutableSetOf<String>()
        val collectedToolNotes = mutableListOf<CollectedToolNote>()
        val turnMemoKey = input.messageId.trim().takeIf { it.isNotEmpty() && !input.isSandbox }
        val memoForMessage = turnMemoKey?.let { getOrCreateTurnMemo(it) }
        var roundsUsed = 0

        // Bounded multi-tool orchestration loop
        while (roundsUsed < MAX_TOOL_ROUNDS) {
            var newToolExecuted = false

            // 1. Account action branch (DM-only + explicit confirmation + idempotency)
            if (currentDecision.accountAction.isNotBlank()) {
                val cleanAction = currentDecision.accountAction.trim().lowercase()
                val actionSig = "account:$cleanAction"
                if (actionSig !in executedSignatures) {
                    if (input.isGroup) {
                        val groupPrivateOnlyReply =
                            "لحماية خصوصية حسابك، لا يمكن تنفيذ طلبات التسجيل أو روابط الدخول داخل المجموعات. يرجى مراسلتي في محادثة خاصة."
                        return CollaborationOutcome(
                            replyText = groupPrivateOnlyReply,
                            conversationKey = conversationKey,
                            usedNewApi = usedNewApiFlag,
                            usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
                            activeEngine = activeEngineLabel,
                            switchedToQwen = switchedToQwen,
                            calledSmartAdvisor = calledSmartAdvisor,
                            calledWebhook = calledWebhook,
                            saIntent = "account_action_blocked_group",
                            invokedTools = invokedTools.toList(),
                            toolRoundsUsed = roundsUsed,
                            memoryLoaded = memoryLoaded,
                            needsHuman = false,
                            stageLatenciesMs = latencies,
                            currentTopic = contextEntity.currentTopic
                        )
                    }

                    val confirmed = currentDecision.accountActionConfirmed ||
                        isExplicitAccountRequest(input.messageText, cleanAction)

                    if (!confirmed) {
                        executedSignatures += actionSig
                        currentDecision = currentDecision.copy(
                            reply = "لتأمين حسابك، هل تؤكد رغبتك في تنفيذ طلب الحساب الآن؟ يرجى الرد بـ «نعم أؤكد»."
                        )
                    } else if (saAvailable && capabilities?.accountActionsEnabled == true) {
                        executedSignatures += actionSig
                        val cachedActionRes = memoForMessage?.accountActions?.get(cleanAction)
                        val actionRes = if (cachedActionRes != null) {
                            calledSmartAdvisor = true
                            invokedTools += TOOL_SMART_ADVISOR
                            saToolReason = "account_action:$cleanAction"
                            cachedActionRes
                        } else {
                            calledSmartAdvisor = true
                            invokedTools += TOOL_SMART_ADVISOR
                            saToolReason = "account_action:$cleanAction"
                            val actionStart = clockMs()
                            smartAdvisorClient.performAccountAction(
                                action = cleanAction,
                                customerKey = contextEntity.customerKey,
                                phone = input.phone,
                                name = input.senderName,
                                idempotencyKey = if (input.isSandbox) null else input.messageId.ifBlank { null }
                            ).also { res ->
                                latencies["sa_account_action"] = (clockMs() - actionStart).coerceAtLeast(0L)
                                if (res.ok) {
                                    memoForMessage?.accountActions?.put(cleanAction, res)
                                }
                            }
                        }

                        if (actionRes.ok) {
                            verifiedStoreDataUsed = true
                            deliveryContainsEphemeralLink =
                                actionRes.linkCreated && actionRes.ephemeralLoginUrl.isNotBlank()
                            val snippet = SensitiveDataRedactor.scrub(
                                actionRes.message.ifBlank { actionRes.status },
                                secretVault
                            )
                            verifiedDataSnippet = snippet

                            val hasAdditionalTools = requestsStoreKnowledge(currentDecision) ||
                                requestsWebhookTool(currentDecision)
                            if (!hasAdditionalTools) {
                                val composedReply = composeAccountActionReply(
                                    actionRes = actionRes,
                                    settings = aiSettings,
                                    recentTurns = recentTurns,
                                    userMessage = input.messageText
                                )
                                if (composedReply != null) {
                                    currentDecision = currentDecision.copy(reply = composedReply)
                                }
                                break
                            } else {
                                newToolExecuted = true
                                collectedToolNotes += CollectedToolNote(
                                    toolLabel = TOOL_SMART_ADVISOR,
                                    trustLabel = "إجراء حساب مؤكد من المتجر",
                                    query = cleanAction,
                                    reason = "account_action",
                                    content = if (deliveryContainsEphemeralLink) {
                                        "تم تجهيز رابط الدخول السريع للحساب: ${actionRes.ephemeralLoginUrl}"
                                    } else {
                                        snippet
                                    },
                                    ok = true
                                )
                            }
                        } else {
                            finalRedactedError = actionRes.error ?: finalRedactedError
                            collectedToolNotes += CollectedToolNote(
                                toolLabel = TOOL_SMART_ADVISOR,
                                trustLabel = "تعذر إجراء الحساب",
                                query = cleanAction,
                                reason = "account_action",
                                content = actionRes.error ?: "فشل تنفيذ إجراء الحساب",
                                ok = false
                            )
                            newToolExecuted = true
                        }
                    }
                }
            }

            // 2. Smart Advisor Knowledge Tool
            val requestedSaTool = currentDecision.toolRequests.firstOrNull {
                it.tool == "smart_advisor" || it.tool == "smartadvisor" || it.tool == "sa"
            }
            if (currentDecision.needsStoreData || requestedSaTool != null) {
                val rawSaQuery = (requestedSaTool?.query?.takeIf { it.isNotBlank() }
                    ?: currentDecision.storeQuery).trim()
                val resolvedIntent = SmartAdvisorClient.normalizeKnowledgeIntent(
                    requestedSaTool?.intent?.takeIf { it.isNotBlank() }
                        ?: currentDecision.storeIntent.ifBlank { currentDecision.intent }
                )
                val activeEntityHint = if (aiSettings.useConversationContext) {
                    conversationMemoryManager.resolveActiveEntityHint(contextEntity).trim()
                } else {
                    ""
                }
                val queryText = resolveContextualToolQuery(
                    modelQuery = rawSaQuery,
                    userMessage = input.messageText,
                    resolvedIntent = resolvedIntent,
                    activeEntityHint = activeEntityHint,
                    lastProductDiscussed = contextEntity.lastProductDiscussed
                )
                saIntent = resolvedIntent
                val normalizedQueryKey = normalizeToolKey(queryText)
                val saSig = "sa:$normalizedQueryKey:$resolvedIntent"

                if (saSig !in executedSignatures) {
                    executedSignatures += saSig
                    if (saAvailable && (capabilities?.knowledgeEnabled != false)) {
                        calledSmartAdvisor = true
                        invokedTools += TOOL_SMART_ADVISOR
                        saToolReason = requestedSaTool?.reason?.ifBlank { queryText } ?: queryText
                        newToolExecuted = true

                        val cachedKnowledge = memoForMessage?.knowledge?.get(saSig)
                        val knowledge = if (cachedKnowledge != null) {
                            cachedKnowledge
                        } else {
                            val saStart = clockMs()
                            val shownIdsForConv = conversationMemoryManager.getShownProductIds(conversationKey)
                            smartAdvisorClient.queryKnowledge(
                                query = queryText,
                                intent = resolvedIntent,
                                customerKey = contextEntity.customerKey,
                                isGroup = input.isGroup,
                                excludeProductIds = shownIdsForConv
                            ).also { res ->
                                latencies["smart_advisor_knowledge"] =
                                    (latencies["smart_advisor_knowledge"] ?: 0L) +
                                        (clockMs() - saStart).coerceAtLeast(0L)
                                if (res.ok) {
                                    memoForMessage?.knowledge?.put(saSig, res)
                                }
                            }
                        }

                        if (knowledge.ok && knowledge.hasVerifiedData) {
                            lastVerifiedKnowledge = knowledge
                            verifiedStoreDataUsed = true
                            val capStoreName = capabilities?.storeName.orEmpty()
                            val effectiveKnowledge = if (knowledge.storeName.isBlank() && capStoreName.isNotBlank()) {
                                knowledge.copy(storeName = capStoreName)
                            } else {
                                knowledge
                            }
                            val parsedKnowledge = effectiveKnowledge.toParsedPayload()
                            val shownIds = parsedKnowledge.products.mapNotNull { it.productId }
                            if (shownIds.isNotEmpty()) {
                                conversationMemoryManager.recordShownProductIds(conversationKey, shownIds)
                            }
                            val snippet = buildVerifiedKnowledgeSnippet(effectiveKnowledge)
                            verifiedDataSnippet = listOf(verifiedDataSnippet, snippet)
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                            collectedToolNotes += CollectedToolNote(
                                toolLabel = TOOL_SMART_ADVISOR,
                                trustLabel = "بيانات متجر مؤكدة (WooCommerce)",
                                query = queryText,
                                reason = saToolReason,
                                content = snippet,
                                ok = true
                            )
                        } else {
                            lastFailedKnowledge = knowledge
                            verifiedStoreDataUsed = verifiedStoreDataUsed && false
                            val errMsg = knowledge.error ?: "لا توجد بيانات مؤكدة من المتجر"
                            if (verifiedDataSnippet.isBlank()) verifiedDataSnippet = errMsg
                            collectedToolNotes += CollectedToolNote(
                                toolLabel = TOOL_SMART_ADVISOR,
                                trustLabel = "فشل/لا توجد بيانات مؤكدة",
                                query = queryText,
                                reason = saToolReason,
                                content = errMsg,
                                ok = false
                            )
                            currentDecision = currentDecision.copy(needsHuman = true)
                        }
                    } else {
                        saToolReason = "Smart Advisor معطّل أو غير مهيأ"
                        collectedToolNotes += CollectedToolNote(
                            toolLabel = TOOL_SMART_ADVISOR,
                            trustLabel = "أداة غير متاحة",
                            query = queryText,
                            reason = saToolReason,
                            content = "بيانات المتجر غير متوفرة حالياً عبر Smart Advisor",
                            ok = false
                        )
                        if (currentDecision.reply.isBlank()) {
                            currentDecision = currentDecision.copy(needsHuman = true)
                        }
                    }
                }
            }

            // 3. External Webhook AI Tool
            val requestedWebhookTool = currentDecision.toolRequests.firstOrNull {
                it.tool == "webhook" || it.tool == "server" || it.tool == "external_webhook"
            }
            if (currentDecision.needsWebhookData || requestedWebhookTool != null) {
                val rawWhQuery = (requestedWebhookTool?.query?.takeIf { it.isNotBlank() }
                    ?: currentDecision.webhookQuery).trim()
                val activeEntityHint = if (aiSettings.useConversationContext) {
                    conversationMemoryManager.resolveActiveEntityHint(contextEntity).trim()
                } else {
                    ""
                }
                val queryText = resolveContextualToolQuery(
                    modelQuery = rawWhQuery,
                    userMessage = input.messageText,
                    resolvedIntent = currentDecision.intent,
                    activeEntityHint = activeEntityHint,
                    lastProductDiscussed = contextEntity.lastProductDiscussed
                )
                val reasonText = (requestedWebhookTool?.reason?.takeIf { it.isNotBlank() }
                    ?: currentDecision.webhookReason.ifBlank { queryText }).trim()
                val whSig = "webhook:${normalizeToolKey(queryText)}"

                if (whSig !in executedSignatures) {
                    executedSignatures += whSig
                    if (webhookToolAvailable && serverConfig != null && webhookClient != null) {
                        calledWebhook = true
                        invokedTools += TOOL_WEBHOOK
                        webhookToolReason = reasonText
                        newToolExecuted = true

                        val cachedWh = memoForMessage?.webhooks?.get(whSig)
                        val whRes = if (cachedWh != null) {
                            cachedWh
                        } else {
                            val whStart = clockMs()
                            val msgForWebhook = IncomingMessage(
                                app = input.packageName,
                                sender = input.senderName,
                                senderName = input.senderName,
                                message = input.messageText,
                                phone = input.phone,
                                groupName = input.groupName.orEmpty(),
                                isGroup = input.isGroup,
                                notificationId = input.messageId.ifBlank { "ai-tool-${clockMs()}" },
                                timestamp = clockMs(),
                                aiQuery = queryText
                            )
                            webhookClient.invokeAsAiTool(
                                config = serverConfig,
                                msg = msgForWebhook,
                                aiQuery = queryText,
                                idempotencyKey = if (input.isSandbox) null else input.messageId.ifBlank { null },
                                reason = reasonText
                            ).also { res ->
                                latencies["webhook_tool"] =
                                    (latencies["webhook_tool"] ?: 0L) +
                                        (clockMs() - whStart).coerceAtLeast(0L)
                                if (res.ok) {
                                    memoForMessage?.webhooks?.put(whSig, res)
                                }
                            }
                        }

                        webhookStatusCode = whRes.statusCode
                        val candidateSnippet = whRes.extractedText.ifBlank { whRes.rawExcerpt }
                        if (candidateSnippet.isNotBlank()) {
                            webhookDataSnippet = candidateSnippet
                        }

                        if (whRes.ok) {
                            collectedToolNotes += CollectedToolNote(
                                toolLabel = TOOL_WEBHOOK,
                                trustLabel = "مصدر خارجي غير متحقق منه (External Webhook)",
                                query = queryText,
                                reason = reasonText,
                                content = candidateSnippet,
                                ok = true
                            )
                        } else {
                            finalRedactedError = whRes.redactedError ?: finalRedactedError
                            collectedToolNotes += CollectedToolNote(
                                toolLabel = TOOL_WEBHOOK,
                                trustLabel = "تعذر الاستعلام من Webhook الخارجي",
                                query = queryText,
                                reason = reasonText,
                                content = whRes.redactedError ?: "فشل الاتصال بالخادم الخارجي",
                                ok = false
                            )
                        }
                    } else {
                        webhookToolReason = "Webhook غير مفعّل في وضع أداة AI"
                        collectedToolNotes += CollectedToolNote(
                            toolLabel = TOOL_WEBHOOK,
                            trustLabel = "أداة غير متاحة",
                            query = queryText,
                            reason = webhookToolReason,
                            content = "خادم Webhook غير مفعّل في وضع أداة AI حالياً",
                            ok = false
                        )
                    }
                }
            }

            if (!newToolExecuted) break

            roundsUsed++
            val allowMoreTools = roundsUsed < MAX_TOOL_ROUNDS
            val synthesisPrompt = buildToolSynthesisSystemInstruction(
                aiSettings = aiSettings,
                senderName = input.rawTitle.ifBlank { input.senderName },
                localContextBlock = localContextBlock,
                toolNotes = collectedToolNotes,
                allowMoreTools = allowMoreTools,
                saAvailable = saAvailable,
                webhookToolAvailable = webhookToolAvailable,
                webhookConfig = serverConfig
            )

            val synthesisOutcome = aiReplyEngine.runCollaborationTurn(
                settings = aiSettings,
                userMessage = input.messageText,
                systemInstruction = synthesisPrompt,
                recentTurns = recentTurns
            )
            latencies["ai_compose_final"] =
                (latencies["ai_compose_final"] ?: 0L) +
                    synthesisOutcome.primaryLatencyMs +
                    synthesisOutcome.fallbackLatencyMs
            switchedToQwen = switchedToQwen || synthesisOutcome.switchedToFallback
            if (synthesisOutcome.activeEngineLabel.isNotBlank()) {
                activeEngineLabel = synthesisOutcome.activeEngineLabel
            }

            val nextDecision = synthesisOutcome.decision
            if (nextDecision != null) {
                currentDecision = nextDecision.copy(
                    needsHuman = nextDecision.needsHuman || currentDecision.needsHuman,
                    memoryUpdate = nextDecision.memoryUpdate ?: currentDecision.memoryUpdate
                )
                // If AI synthesized a reply and requested no additional uncalled tools, we are done
                val hasMoreToolsRequested = allowMoreTools && (
                    requestsStoreKnowledge(currentDecision) || requestsWebhookTool(currentDecision)
                    )
                if (!hasMoreToolsRequested && currentDecision.reply.isNotBlank()) {
                    break
                }
                if (!allowMoreTools && currentDecision.reply.isBlank()) {
                    // Force safe text without fabricating if model omitted reply on final round
                    currentDecision = currentDecision.copy(
                        reply = buildSafeToolFallbackReply(
                            lastVerifiedKnowledge = lastVerifiedKnowledge,
                            lastFailedKnowledge = lastFailedKnowledge,
                            webhookDataSnippet = webhookDataSnippet,
                            fallbackDraft = ""
                        )
                    )
                    break
                }
            } else {
                // Synthesis call failed -> safe deterministic fallback only (never leak unverified Pass 1 guesses!)
                if (lastVerifiedKnowledge != null) {
                    val verifiedFallback = lastVerifiedKnowledge.summary.takeIf { it.isNotBlank() }
                        ?: lastVerifiedKnowledge.facts.joinToString("\n").takeIf { it.isNotBlank() }
                    if (!verifiedFallback.isNullOrBlank()) {
                        currentDecision = currentDecision.copy(reply = verifiedFallback)
                        break
                    }
                }
                if (lastFailedKnowledge != null) {
                    currentDecision = currentDecision.copy(
                        reply = buildNoVerifiedDataReply(lastFailedKnowledge, ""),
                        needsHuman = true
                    )
                    break
                }
                if (calledSmartAdvisor && !verifiedStoreDataUsed) {
                    val synFailureCat = if (synthesisOutcome.failureCategory != EngineFailureCategory.NONE) {
                        synthesisOutcome.failureCategory
                    } else {
                        EngineFailureCategory.SERVICE_ERROR
                    }
                    val synErr = synthesisOutcome.redactedError ?: "تعذر صياغة الرد النهائي"
                    diagnosticLogger.log(
                        tag = if (synFailureCat == EngineFailureCategory.TIMEOUT) DiagnosticEventTag.TIMEOUT else DiagnosticEventTag.SERVICE_ERROR,
                        conversationId = conversationKey,
                        messageId = input.messageId,
                        detectedStatus = synFailureCat.toProcessingStatus().name,
                        decision = "PASS2_SYNTHESIS_FAILED",
                        errorSource = activeEngineLabel,
                        detail = synErr
                    )
                    return CollaborationOutcome(
                        replyText = null,
                        conversationKey = conversationKey,
                        usedNewApi = usedNewApiFlag,
                        usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
                        activeEngine = activeEngineLabel,
                        switchedToQwen = switchedToQwen,
                        calledSmartAdvisor = calledSmartAdvisor,
                        calledWebhook = calledWebhook,
                        saIntent = saIntent,
                        saToolReason = saToolReason,
                        webhookToolReason = webhookToolReason,
                        webhookDataSnippet = SensitiveDataRedactor.scrub(webhookDataSnippet, secretVault),
                        webhookStatusCode = webhookStatusCode,
                        invokedTools = invokedTools.toList(),
                        toolRoundsUsed = roundsUsed,
                        verifiedStoreDataUsed = false,
                        memoryLoaded = memoryLoaded,
                        redactedError = synErr,
                        stageLatenciesMs = latencies,
                        currentTopic = contextEntity.currentTopic,
                        processingClassification = MessageProcessingClassification(
                            status = synFailureCat.toProcessingStatus(),
                            confidence = currentDecision.confidence,
                            detectedIntent = currentDecision.intent,
                            finalReply = null,
                            source = activeEngineLabel,
                            failureReason = synErr
                        ),
                        engineFailureCategory = synFailureCat
                    )
                }
                val safeFallback = buildSafeToolFallbackReply(
                    lastVerifiedKnowledge = lastVerifiedKnowledge,
                    lastFailedKnowledge = lastFailedKnowledge,
                    webhookDataSnippet = webhookDataSnippet,
                    fallbackDraft = ""
                )
                if (safeFallback.isBlank()) {
                    val synFailureCat = if (synthesisOutcome.failureCategory != EngineFailureCategory.NONE) {
                        synthesisOutcome.failureCategory
                    } else {
                        EngineFailureCategory.SERVICE_ERROR
                    }
                    val synErr = synthesisOutcome.redactedError ?: finalRedactedError ?: "تعذر صياغة الرد النهائي"
                    diagnosticLogger.log(
                        tag = if (synFailureCat == EngineFailureCategory.TIMEOUT) DiagnosticEventTag.TIMEOUT else DiagnosticEventTag.SERVICE_ERROR,
                        conversationId = conversationKey,
                        messageId = input.messageId,
                        detectedStatus = synFailureCat.toProcessingStatus().name,
                        decision = "PASS2_SYNTHESIS_FAILED",
                        errorSource = activeEngineLabel,
                        detail = synErr
                    )
                    return CollaborationOutcome(
                        replyText = null,
                        conversationKey = conversationKey,
                        usedNewApi = usedNewApiFlag,
                        usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
                        activeEngine = activeEngineLabel,
                        switchedToQwen = switchedToQwen,
                        calledSmartAdvisor = calledSmartAdvisor,
                        calledWebhook = calledWebhook,
                        saIntent = saIntent,
                        saToolReason = saToolReason,
                        webhookToolReason = webhookToolReason,
                        webhookDataSnippet = SensitiveDataRedactor.scrub(webhookDataSnippet, secretVault),
                        webhookStatusCode = webhookStatusCode,
                        invokedTools = invokedTools.toList(),
                        toolRoundsUsed = roundsUsed,
                        verifiedStoreDataUsed = verifiedStoreDataUsed,
                        memoryLoaded = memoryLoaded,
                        redactedError = synthesisOutcome.redactedError ?: finalRedactedError,
                        stageLatenciesMs = latencies,
                        currentTopic = contextEntity.currentTopic,
                        processingClassification = MessageProcessingClassification(
                            status = synFailureCat.toProcessingStatus(),
                            confidence = currentDecision.confidence,
                            detectedIntent = currentDecision.intent,
                            finalReply = null,
                            source = activeEngineLabel,
                            failureReason = synErr
                        ),
                        engineFailureCategory = synFailureCat
                    )
                }
                currentDecision = currentDecision.copy(reply = safeFallback)
                break
            }
        }

        // Guard against SA returning 403 / has_verified_data=false even if synthesis repeated a guessed price
        if (lastFailedKnowledge != null && !verifiedStoreDataUsed) {
            val sanitizedSafe = buildNoVerifiedDataReply(lastFailedKnowledge, currentDecision.reply)
            if (currentDecision.reply.isBlank() ||
                containsSpeculativePriceOrStock(currentDecision.reply)
            ) {
                currentDecision = currentDecision.copy(
                    reply = sanitizedSafe,
                    needsHuman = true
                )
            }
        } else if (requestsStoreKnowledge(currentDecision) && !saAvailable && currentDecision.reply.isBlank()) {
            currentDecision = currentDecision.copy(
                reply = "لا تتوفر لديّ بيانات المتجر المؤكدة حالياً، يمكنك تأكيد سؤالك وسيتابع معك فريق خدمة العملاء.",
                needsHuman = true
            )
        } else if (currentDecision.reply.isBlank()) {
            val safe = buildSafeToolFallbackReply(
                lastVerifiedKnowledge = lastVerifiedKnowledge,
                lastFailedKnowledge = lastFailedKnowledge,
                webhookDataSnippet = webhookDataSnippet,
                fallbackDraft = ""
            )
            if (safe.isNotBlank()) {
                currentDecision = currentDecision.copy(reply = safe)
            }
        }

        // Durable Memory Update via Encrypted Outbox (consent-gated, DM-only, never in sandbox)
        var memoryUpdated = false
        var consentRequiredFlag = false
        val memProposal = currentDecision.memoryUpdate
        if (!input.isSandbox &&
            !input.isGroup &&
            aiSettings.syncCustomerMemory &&
            saAvailable &&
            memProposal != null &&
            memoryOutboxCoordinator != null
        ) {
            val effectiveConsent = if (aiSettings.requireCustomerConsent || (capabilities?.consentRequired != false)) {
                contextEntity.consentGranted
            } else {
                true
            }
            if (!effectiveConsent) {
                consentRequiredFlag = true
            } else if (capabilities?.memoryEnabled == true) {
                val enq = memoryOutboxCoordinator.enqueueDurableMemoryUpdate(
                    conversationKey = conversationKey,
                    customerKey = contextEntity.customerKey,
                    phone = input.phone,
                    summary = memProposal.summary,
                    preferences = memProposal.preferences,
                    interests = memProposal.interests,
                    constraints = memProposal.constraints,
                    consentGranted = true,
                    scheduleWorker = true
                )
                memoryUpdated = enq.enqueued
            }
        }

        val cleanedReply = currentDecision.reply
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { raw ->
                val scrubbed = if (!input.isSandbox && deliveryContainsEphemeralLink) {
                    secretVault.redact(raw)
                } else {
                    SensitiveDataRedactor.scrub(raw, secretVault)
                }
                val redacted = secretVault.redact(scrubbed)
                AIReplyEngine.enforceToneAndLength(redacted, aiSettings)
            }

        val effectiveVerified = lastVerifiedKnowledge?.let { lk ->
            val capStoreName = capabilities?.storeName.orEmpty()
            if (lk.storeName.isBlank() && capStoreName.isNotBlank()) {
                lk.copy(storeName = capStoreName)
            } else {
                lk
            }
        }
        val parsedKnowledge = effectiveVerified?.toParsedPayload(
            selectedProductId = currentDecision.selectedProductId
        )
        val resolvedResponseType = parsedKnowledge?.responseType ?: ResponseType.TEXT_MESSAGE
        val resolvedProductMessage = if (resolvedResponseType == ResponseType.PRODUCT_IMAGE) {
            parsedKnowledge?.selectedProduct
        } else {
            null
        }
        val resolvedReplyText = if (resolvedResponseType == ResponseType.PRODUCT_IMAGE && resolvedProductMessage != null) {
            secretVault.redact(resolvedProductMessage.buildCaption())
                .ifBlank { cleanedReply.orEmpty() }
                .takeIf { it.isNotBlank() }
        } else {
            cleanedReply
        }
        val fallbackReply = when {
            resolvedProductMessage != null -> {
                cleanedReply?.takeIf { it.isNotBlank() } ?: resolvedProductMessage.buildTextFallbackReply()
            }
            else -> cleanedReply.orEmpty()
        }

        if (!resolvedReplyText.isNullOrBlank()) {
            conversationMemoryManager.resetClarificationState(conversationKey)
        }

        return CollaborationOutcome(
            replyText = resolvedReplyText,
            conversationKey = conversationKey,
            usedNewApi = usedNewApiFlag,
            usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
            activeEngine = activeEngineLabel,
            switchedToQwen = switchedToQwen,
            calledSmartAdvisor = calledSmartAdvisor,
            calledWebhook = calledWebhook,
            saIntent = saIntent,
            saToolReason = saToolReason,
            webhookToolReason = webhookToolReason,
            webhookDataSnippet = SensitiveDataRedactor.scrub(webhookDataSnippet, secretVault),
            webhookStatusCode = webhookStatusCode,
            invokedTools = invokedTools.toList(),
            toolRoundsUsed = roundsUsed,
            verifiedStoreDataUsed = verifiedStoreDataUsed,
            verifiedDataSnippet = SensitiveDataRedactor.scrub(verifiedDataSnippet, secretVault),
            memoryLoaded = memoryLoaded,
            memoryUpdated = memoryUpdated,
            needsHuman = currentDecision.needsHuman,
            consentRequiredForMemory = consentRequiredFlag,
            redactedError = finalRedactedError,
            stageLatenciesMs = latencies,
            currentTopic = contextEntity.currentTopic,
            responseType = resolvedResponseType,
            productMessage = resolvedProductMessage,
            products = parsedKnowledge?.products.orEmpty(),
            storeName = parsedKnowledge?.storeName.orEmpty(),
            fallbackTextReply = fallbackReply,
            processingClassification = MessageProcessingClassification(
                status = MessageProcessingStatus.UNDERSTOOD,
                confidence = currentDecision.confidence,
                detectedIntent = currentDecision.intent,
                finalReply = resolvedReplyText,
                source = activeEngineLabel
            )
        )
    }

    private fun detectUnunderstoodStatus(
        decision: AIReplyEngine.AiStructuredDecision,
        userMessage: String,
        hasActiveContext: Boolean
    ): MessageProcessingStatus? {
        val statusFromIntent = UnunderstoodIntentPolicy.classifyIntentToken(decision.intent)
        if (statusFromIntent != null) return statusFromIntent

        val isNoiseInput = UnunderstoodIntentPolicy.isMeaninglessNoiseInput(userMessage, hasActiveContext)
        val hasToolRequest = requestsStoreKnowledge(decision) ||
            requestsWebhookTool(decision) ||
            decision.accountAction.isNotBlank()

        if (isNoiseInput && !hasActiveContext) {
            return MessageProcessingStatus.UNKNOWN_INTENT
        }

        if (decision.isStructuredJson &&
            decision.confidence < UnunderstoodIntentPolicy.MIN_CONFIDENCE_THRESHOLD &&
            !hasToolRequest &&
            (!hasActiveContext || decision.reply.isBlank())
        ) {
            return MessageProcessingStatus.LOW_CONFIDENCE
        }

        if (decision.isStructuredJson &&
            decision.confidence < UnunderstoodIntentPolicy.MIN_CONFIDENCE_THRESHOLD &&
            isNoiseInput
        ) {
            return MessageProcessingStatus.LOW_CONFIDENCE
        }

        return null
    }

    private fun requestsStoreKnowledge(d: AIReplyEngine.AiStructuredDecision): Boolean =
        d.needsStoreData || d.toolRequests.any {
            it.tool == "smart_advisor" || it.tool == "smartadvisor" || it.tool == "sa"
        }

    private fun requestsWebhookTool(d: AIReplyEngine.AiStructuredDecision): Boolean =
        d.needsWebhookData || d.toolRequests.any {
            it.tool == "webhook" || it.tool == "server" || it.tool == "external_webhook"
        }

    private fun isExplicitAccountRequest(messageText: String, action: String): Boolean {
        val norm = messageText.trim().lowercase()
        return when (action) {
            "magic_login" -> norm.contains("رابط") || norm.contains("دخول") || norm.contains("تسجيل الدخول") || norm.contains("login")
            "register" -> norm.contains("سجل") || norm.contains("تسجيل") || norm.contains("حساب") || norm.contains("register")
            else -> false
        }
    }

    private fun containsSpeculativePriceOrStock(text: String): Boolean {
        return text.contains("ريال") ||
            text.contains("سعر") ||
            text.contains("متوفر") ||
            Regex("""\b\d{2,5}\b""").containsMatchIn(text)
    }

    private fun buildSafeToolFallbackReply(
        lastVerifiedKnowledge: SmartAdvisorClient.SaKnowledgeResponse?,
        lastFailedKnowledge: SmartAdvisorClient.SaKnowledgeResponse?,
        webhookDataSnippet: String,
        fallbackDraft: String
    ): String {
        if (lastVerifiedKnowledge != null) {
            val verified = lastVerifiedKnowledge.summary.takeIf { it.isNotBlank() }
                ?: lastVerifiedKnowledge.facts.joinToString("\n").takeIf { it.isNotBlank() }
            if (!verified.isNullOrBlank()) return verified
        }
        if (lastFailedKnowledge != null) {
            return buildNoVerifiedDataReply(lastFailedKnowledge, fallbackDraft)
        }
        if (webhookDataSnippet.isNotBlank()) {
            return "تم التحقق من النظام الخارجي: $webhookDataSnippet"
        }
        return fallbackDraft
    }

    private suspend fun composeAccountActionReply(
        actionRes: SmartAdvisorClient.SaAccountActionResponse,
        settings: AiSettingsState,
        recentTurns: List<AIReplyEngine.ChatTurn>,
        userMessage: String
    ): String? {
        if (actionRes.linkCreated && actionRes.ephemeralLoginUrl.isNotBlank()) {
            return "تم تجهيز رابط الدخول السريع لحسابك: ${actionRes.ephemeralLoginUrl}"
        }
        val fact = actionRes.message.ifBlank {
            if (actionRes.accountCreated) "تم إنشاء حسابك بنجاح." else "تم تنفيذ طلب الحساب."
        }
        val pass2 = aiReplyEngine.runCollaborationTurn(
            settings = settings,
            userMessage = userMessage,
            systemInstruction = buildString {
                append("أعد صياغة هذه النتيجة بأسلوب مهذب دون أي إضافات غير مؤكدة:\n")
                append(fact).append("\n")
                append("- النبرة: ").append(settings.effectiveToneInstruction()).append('\n')
                append("- الطول: ").append(settings.effectiveLengthInstruction()).append('\n')
                append("- اللغة: ").append(settings.effectiveLanguageInstruction())
            },
            recentTurns = recentTurns
        )
        val raw = pass2.decision?.reply?.takeIf { it.isNotBlank() } ?: fact
        return AIReplyEngine.enforceToneAndLength(raw, settings)
    }

    private fun buildNoVerifiedDataReply(
        knowledge: SmartAdvisorClient.SaKnowledgeResponse,
        fallbackFromPass1: String
    ): String {
        if (knowledge.skillDisabled) {
            return "عذراً، خدمة الاستعلام من المتجر معطلة حالياً. سيتواصل معك فريق المتجر لمساعدتك."
        }
        if (!knowledge.ok) {
            return "تعذر التحقق من بيانات المتجر حالياً، يرجى المحاولة لاحقاً أو انتظار رد خدمة العملاء."
        }
        if (fallbackFromPass1.isNotBlank() &&
            !containsSpeculativePriceOrStock(fallbackFromPass1)
        ) {
            return fallbackFromPass1
        }
        return "لم أجد معلومات مؤكدة حول هذا الطلب في بيانات المتجر حالياً، ويمكنني تحويل استفسارك لفريق الخدمة لمراجعته."
    }

    private fun buildVerifiedKnowledgeSnippet(k: SmartAdvisorClient.SaKnowledgeResponse): String {
        return buildString {
            if (k.summary.isNotBlank()) append(k.summary).append('\n')
            if (k.facts.isNotEmpty()) {
                k.facts.forEach { append("- ").append(it).append('\n') }
            }
            if (k.itemsJson != "[]" && k.itemsJson.isNotBlank()) {
                append("المنتجات المطابقة: ").append(k.itemsJson.take(1200))
            }
        }.trim()
    }

    private fun formatServerMemoryForPrompt(memory: SmartAdvisorClient.SaMemoryPayload): String {
        return buildString {
            if (memory.summary.isNotBlank()) append("الملخص: ").append(memory.summary).append('\n')
            if (memory.preferences.isNotEmpty()) append("التفضيلات: ").append(memory.preferences.joinToString("، ")).append('\n')
            if (memory.interests.isNotEmpty()) append("الاهتمامات: ").append(memory.interests.joinToString("، ")).append('\n')
            if (memory.constraints.isNotEmpty()) append("القيود: ").append(memory.constraints.joinToString("، "))
        }.trim()
    }

    private fun buildPass1SystemInstruction(
        aiSettings: AiSettingsState,
        senderName: String,
        isGroup: Boolean,
        localContextBlock: String,
        serverMemoryBlock: String,
        saAvailable: Boolean,
        capabilities: SmartAdvisorClient.SaCapabilities?,
        webhookToolAvailable: Boolean,
        webhookConfig: ServerConfigEntity?,
        keywordContextHint: String = ""
    ): String {
        return buildString {
            if (aiSettings.systemPrompt.isNotBlank()) {
                append(aiSettings.systemPrompt.trim()).append("\n\n")
            }
            append("أنت المنسّق الوحيد للمحادثة (Single Conversation Orchestrator) في تطبيق AutoMate AI. ")
            append("العميل: ").append(senderName).append(" (")
                .append(if (isGroup) "محادثة جماعية" else "محادثة خاصة").append(").\n")
            append("\nضوابط الأسلوب والطول واللغة:\n")
            append("- النبرة: ").append(aiSettings.effectiveToneInstruction()).append('\n')
            append("- الطول: ").append(aiSettings.effectiveLengthInstruction()).append('\n')
            append("- اللغة: ").append(aiSettings.effectiveLanguageInstruction()).append('\n')
            if (keywordContextHint.isNotBlank()) {
                append("\nملاحظة من قاعدة كلمات مفتاحية مطابقة: ").append(keywordContextHint.trim()).append('\n')
            }
            if (localContextBlock.isNotBlank()) {
                append("\nسياق المحادثة الحالي:\n").append(localContextBlock).append('\n')
            }
            if (serverMemoryBlock.isNotBlank()) {
                append("\nذاكرة العميل الدائمة المؤكدة:\n").append(serverMemoryBlock).append('\n')
            }
            append("\nالأدوات المتاحة لك عند الحاجة:\n")
            if (saAvailable) {
                append("1. أداة Smart Advisor (بيانات المتجر المؤكدة WooCommerce: المنتجات، الأسعار، المخزون، السياسات، وإجراءات الحساب في الخاص فقط).\n")
            } else {
                append("1. أداة Smart Advisor غير مفعلة حالياً.\n")
            }
            if (webhookToolAvailable && webhookConfig != null) {
                append("2. أداة Webhook الخارجي: ").append(webhookConfig.effectiveToolDescription()).append('\n')
            } else {
                append("2. أداة Webhook الخارجي غير مفعلة حالياً.\n")
            }
            append("\nالتعليمات الصارمة:\n")
            append("- أجب بصيغة JSON صالحة بهذه الحقول: intent, confidence, needs_store_data, store_query, store_intent, needs_webhook_data, webhook_query, webhook_reason, tool_requests, needs_human, reply, account_action, account_action_confirmed, memory_update.\n")
            append("- قيم store_intent المسموحة: product_search, product_details, offers, store_info, store_location, shipping_policy, returns_policy, general.\n")
            append("- تحقق من سياق المحادثة الحالي أولاً قبل الحكم بعدم الفهم: إذا أرسل العميل رداً قصيراً مرتبطاً بالسؤال أو المنتجات السابقة (مثل: نعم، لا، أكثر، الثاني، قارن، المنعش) فافهمه ضمن السياق وأجب بثقة واضحة.\n")
            append("- إذا كانت الرسالة غير مفهومة أو عشوائية أو غامضة بلا دليل كافٍ حتى مع السياق: لا تخمّن نية العميل، ولا تطلب منتجات أو تصنيفات غير مرتبطة (اجعل needs_store_data=false و needs_webhook_data=false)، ولا تعِد إجابة قديمة غير مرتبطة؛ بل أعد intent=\"UNKNOWN_INTENT\" و confidence أقل من 0.45 و reply=\"\".\n")
            append("- يمكنك استدعاء Smart Advisor فقط أو Webhook فقط أو كليهما معاً أو عدم استدعاء أي أداة إذا كانت الرسالة محادثة عامة.\n")
            append("- لا تخترع أسعاراً أو توفر منتجات أو حالات طلبات؛ إذا احتجت بيانات المتجر اجعل needs_store_data=true، وإذا احتجت النظام الخارجي اجعل needs_webhook_data=true.\n")
            if (!saAvailable || capabilities?.accountActionsEnabled != true) {
                append("- إجراءات الحساب غير متاحة حالياً.\n")
            }
        }.trim()
    }

    private fun buildToolSynthesisSystemInstruction(
        aiSettings: AiSettingsState,
        senderName: String,
        localContextBlock: String,
        toolNotes: List<CollectedToolNote>,
        allowMoreTools: Boolean,
        saAvailable: Boolean,
        webhookToolAvailable: Boolean,
        webhookConfig: ServerConfigEntity?
    ): String {
        return buildString {
            if (aiSettings.systemPrompt.isNotBlank()) {
                append(aiSettings.systemPrompt.trim()).append("\n\n")
            }
            append("أنت المنسّق الوحيد للمحادثة. العميل: ").append(senderName).append('\n')
            append("ضوابط الأسلوب والطول واللغة:\n")
            append("- النبرة: ").append(aiSettings.effectiveToneInstruction()).append('\n')
            append("- الطول: ").append(aiSettings.effectiveLengthInstruction()).append('\n')
            append("- اللغة: ").append(aiSettings.effectiveLanguageInstruction()).append('\n')
            if (localContextBlock.isNotBlank()) {
                append("سياق الجلسة:\n").append(localContextBlock).append('\n')
            }
            append("\nنتائج الأدوات المسترجعة (التزم بها حرفياً ولا تخترع أي معلومة إضافية):\n")
            toolNotes.forEachIndexed { idx, note ->
                append("${idx + 1}. [${note.toolLabel} | ${note.trustLabel}] (استعلام: ${note.query}):\n")
                append(note.content).append('\n')
            }
            append("\nتعليمات الصياغة النهائية:\n")
            append("- ميّز بوضوح بين بيانات Smart Advisor المؤكدة من المتجر وبيانات Webhook الخارجي (لا تنسب بيانات Webhook الخارجي كسياسة متجر مؤكدة إذا لم تُؤكد).\n")
            append("- إذا تعذر استرجاع معلومة مطلوبة أو فشلت أداة، صِغ رداً مهذباً واضحاً دون اختلاق أي رقم أو حالة أو سعر.\n")
            if (allowMoreTools && (saAvailable || webhookToolAvailable)) {
                append("- إذا كنت تحتاج لاستدعاء أداة أخرى مختلفة لم تُستدعَ بعد، يمكنك طلبها، وإلا اكتب الرد النهائي مباشرة.\n")
                if (webhookToolAvailable && webhookConfig != null) {
                    append("- وصف أداة Webhook المتاحة: ").append(webhookConfig.effectiveToolDescription()).append('\n')
                }
            } else {
                append("- اكتملت جولات الأدوات؛ أرسل الآن الرد النهائي الموحد للعميل.\n")
            }
            append("- أعد بصيغة JSON تحتوي الحقل reply وفق النبرة والطول واللغة المحددة أعلاه مع confidence و needs_human.")
        }.trim()
    }

    private data class TurnToolMemo(
        val createdAtMs: Long,
        val knowledge: MutableMap<String, SmartAdvisorClient.SaKnowledgeResponse> = mutableMapOf(),
        val accountActions: MutableMap<String, SmartAdvisorClient.SaAccountActionResponse> = mutableMapOf(),
        val webhooks: MutableMap<String, WebhookClient.WebhookToolResult> = mutableMapOf()
    )

    private val turnToolCache = java.util.concurrent.ConcurrentHashMap<String, TurnToolMemo>()

    private fun getOrCreateTurnMemo(messageId: String): TurnToolMemo {
        val now = clockMs()
        if (turnToolCache.size > 32) {
            turnToolCache.entries.removeIf { now - it.value.createdAtMs > 60_000L }
        }
        return turnToolCache.compute(messageId) { _, existing ->
            if (existing != null && (now - existing.createdAtMs) <= 60_000L) {
                existing
            } else {
                TurnToolMemo(createdAtMs = now)
            }
        }!!
    }

    private fun resolveContextualToolQuery(
        modelQuery: String,
        userMessage: String,
        resolvedIntent: String,
        activeEntityHint: String,
        lastProductDiscussed: String
    ): String {
        val cleanUser = userMessage.trim()
        val cleanModel = modelQuery.trim()
        val baseProduct = lastProductDiscussed.trim()
        val isPolicyOrOrderIntent = resolvedIntent.equals("policy", ignoreCase = true) ||
            resolvedIntent.equals("order_status", ignoreCase = true)

        if (cleanModel.isBlank()) {
            if (!isPolicyOrOrderIntent &&
                activeEntityHint.isNotBlank() &&
                baseProduct.isNotBlank() &&
                !cleanUser.contains(baseProduct, ignoreCase = true)
            ) {
                return "$activeEntityHint - $cleanUser".trim()
            }
            return cleanUser
        }

        if (!isPolicyOrOrderIntent &&
            activeEntityHint.isNotBlank() &&
            baseProduct.isNotBlank() &&
            !cleanModel.contains(baseProduct, ignoreCase = true) &&
            isVagueFollowUpQuery(cleanModel)
        ) {
            return "$activeEntityHint - $cleanModel".trim()
        }
        return cleanModel
    }

    private fun isVagueFollowUpQuery(query: String): Boolean {
        val clean = query.trim().lowercase()
        if (clean.length > 40) return false
        val tokens = listOf(
            "سعره", "كم سعره", "بكم", "لونه", "لون ", "مقاسه", "مقاس ",
            "متوفر", "موجود", "تفاصيله", "مواصفاته", "أبيض", "اسود", "أسود",
            "رجالي", "نسائي", "price", "color", "size", "available"
        )
        return tokens.any { clean.contains(it) }
    }

    private fun normalizeToolKey(raw: String): String =
        raw.trim().lowercase().replace(Regex("""[\s؟?!.,،]+"""), " ").trim()

    companion object {
        const val MAX_TOOL_ROUNDS = 2
        const val TOOL_SMART_ADVISOR = "Smart Advisor"
        const val TOOL_WEBHOOK = "Webhook"
    }
}
