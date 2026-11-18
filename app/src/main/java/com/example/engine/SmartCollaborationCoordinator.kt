package com.example.engine

import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.security.SmartAdvisorPrefs
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import com.example.network.smartadvisor.SmartAdvisorClient
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
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(
        aiSettingsPrefs: AiSettingsPrefs,
        aiReplyEngine: AIReplyEngine,
        smartAdvisorClient: SmartAdvisorClient,
        smartAdvisorPrefs: SmartAdvisorPrefs,
        conversationMemoryManager: ConversationMemoryManager,
        memoryOutboxCoordinator: MemoryOutboxCoordinator,
        secretVault: SecretVault
    ) : this(
        aiSettingsPrefs = aiSettingsPrefs,
        aiReplyEngine = aiReplyEngine,
        smartAdvisorClient = smartAdvisorClient,
        smartAdvisorPrefs = smartAdvisorPrefs,
        conversationMemoryManager = conversationMemoryManager,
        memoryOutboxCoordinator = memoryOutboxCoordinator,
        secretVault = secretVault,
        clockMs = { System.currentTimeMillis() }
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
        val messageId: String = ""
    )

    data class CollaborationOutcome(
        val replyText: String?,
        val conversationKey: String,
        val usedNewApi: Boolean = false,
        val usedPrimaryEngine: String = "",
        val activeEngine: String = "",
        val switchedToQwen: Boolean = false,
        val calledSmartAdvisor: Boolean = false,
        val saIntent: String = "general",
        val verifiedStoreDataUsed: Boolean = false,
        val verifiedDataSnippet: String = "",
        val memoryLoaded: Boolean = false,
        val memoryUpdated: Boolean = false,
        val needsHuman: Boolean = false,
        val consentRequiredForMemory: Boolean = false,
        val safeFallbackUsed: Boolean = false,
        val redactedError: String? = null,
        val stageLatenciesMs: Map<String, Long> = emptyMap(),
        val currentTopic: String = ""
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

        var memoryLoaded = false
        var serverMemoryText = ""
        var capabilities: SmartAdvisorClient.SaCapabilities? = null

        if (saAvailable) {
            val capStart = clockMs()
            capabilities = smartAdvisorClient.fetchCapabilities()
            latencies["sa_capabilities"] = (clockMs() - capStart).coerceAtLeast(0L)

            if (aiSettings.syncCustomerMemory &&
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

        val recentTurns = conversationMemoryManager.buildTurnsForAi(
            context = contextEntity,
            excludeLastUserMessage = true
        )
        val localContextBlock = conversationMemoryManager.buildContextSummaryBlock(contextEntity)
        val pass1SystemPrompt = buildPass1SystemInstruction(
            aiSettings = aiSettings,
            senderName = input.rawTitle.ifBlank { input.senderName },
            isGroup = input.isGroup,
            localContextBlock = localContextBlock,
            serverMemoryBlock = serverMemoryText,
            saAvailable = saAvailable,
            capabilities = capabilities
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
        val usedNewApiFlag = aiSettings.provider == AIReplyEngine.Provider.NEW_API ||
            aiSettings.primaryBaseUrl.isNotBlank()

        if (decision1 == null) {
            aiSettingsPrefs.updateHealthStatus(
                primaryStatus = "فشل أو متعذر",
                fallbackStatus = if (pass1Outcome.switchedToFallback) "فشل الاحتياطي" else aiSettings.lastFallbackStatus,
                lastErrorRedacted = pass1Outcome.redactedError ?: "تعذر الاتصال بمحركات الذكاء الاصطناعي"
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
                currentTopic = contextEntity.currentTopic
            )
        }

        aiSettingsPrefs.updateHealthStatus(
            primaryStatus = if (!pass1Outcome.switchedToFallback) "متصل" else "متعطل (تم التحويل لـ Qwen)",
            fallbackStatus = if (pass1Outcome.switchedToFallback) "نشط (${aiSettings.effectiveOllamaModel()})" else aiSettings.lastFallbackStatus,
            lastErrorRedacted = pass1Outcome.redactedError ?: ""
        )

        var finalDecision = decision1
        var activeEngineLabel = pass1Outcome.activeEngineLabel
        var switchedToQwen = pass1Outcome.switchedToFallback
        var calledSmartAdvisor = false
        var verifiedStoreDataUsed = false
        var verifiedDataSnippet = ""
        var saIntent = SmartAdvisorClient.normalizeKnowledgeIntent(decision1.storeIntent.ifBlank { decision1.intent })
        var finalRedactedError = pass1Outcome.redactedError
        var deliveryContainsEphemeralLink = false

        // Account action branch (private chats only)
        if (decision1.accountAction.isNotBlank()) {
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
                    calledSmartAdvisor = false,
                    saIntent = "account_action_blocked_group",
                    memoryLoaded = memoryLoaded,
                    needsHuman = false,
                    stageLatenciesMs = latencies,
                    currentTopic = contextEntity.currentTopic
                )
            }

            if (saAvailable && capabilities?.accountActionsEnabled == true) {
                calledSmartAdvisor = true
                val actionStart = clockMs()
                val actionRes = smartAdvisorClient.performAccountAction(
                    action = decision1.accountAction,
                    customerKey = contextEntity.customerKey,
                    phone = input.phone,
                    name = input.senderName,
                    idempotencyKey = if (input.isSandbox) null else input.messageId.ifBlank { null }
                )
                latencies["sa_account_action"] = (clockMs() - actionStart).coerceAtLeast(0L)

                if (actionRes.ok) {
                    verifiedStoreDataUsed = true
                    deliveryContainsEphemeralLink = actionRes.linkCreated && actionRes.ephemeralLoginUrl.isNotBlank()
                    verifiedDataSnippet = SensitiveDataRedactor.scrub(
                        actionRes.message.ifBlank { actionRes.status },
                        secretVault
                    )
                    val composedReply = composeAccountActionReply(
                        actionRes = actionRes,
                        settings = aiSettings,
                        recentTurns = recentTurns,
                        userMessage = input.messageText
                    )
                    if (composedReply != null) {
                        finalDecision = finalDecision.copy(reply = composedReply)
                    }
                } else {
                    finalRedactedError = actionRes.error ?: finalRedactedError
                }
            }
        }

        // Smart Advisor Store Knowledge branch (ONLY when needs_store_data == true)
        if (decision1.needsStoreData) {
            if (saAvailable && (capabilities?.knowledgeEnabled != false)) {
                calledSmartAdvisor = true
                val queryText = decision1.storeQuery.ifBlank { input.messageText }
                val saStart = clockMs()
                val knowledge = smartAdvisorClient.queryKnowledge(
                    query = queryText,
                    intent = saIntent,
                    customerKey = contextEntity.customerKey,
                    isGroup = input.isGroup
                )
                latencies["smart_advisor_knowledge"] = (clockMs() - saStart).coerceAtLeast(0L)

                if (knowledge.ok && knowledge.hasVerifiedData) {
                    verifiedStoreDataUsed = true
                    verifiedDataSnippet = buildVerifiedKnowledgeSnippet(knowledge)
                    val pass2Prompt = buildPass2VerifiedSystemInstruction(
                        aiSettings = aiSettings,
                        senderName = input.rawTitle.ifBlank { input.senderName },
                        localContextBlock = localContextBlock,
                        verifiedSnippet = verifiedDataSnippet
                    )
                    val pass2Outcome = aiReplyEngine.runCollaborationTurn(
                        settings = aiSettings,
                        userMessage = input.messageText,
                        systemInstruction = pass2Prompt,
                        recentTurns = recentTurns
                    )
                    latencies["ai_compose_final"] = pass2Outcome.primaryLatencyMs + pass2Outcome.fallbackLatencyMs
                    switchedToQwen = switchedToQwen || pass2Outcome.switchedToFallback
                    activeEngineLabel = pass2Outcome.activeEngineLabel

                    if (pass2Outcome.decision?.reply?.isNotBlank() == true) {
                        finalDecision = pass2Outcome.decision
                    } else {
                        // Deterministic fallback from verified knowledge ONLY (no hallucination)
                        val verifiedFallback = knowledge.summary.takeIf { it.isNotBlank() }
                            ?: knowledge.facts.joinToString("\n").takeIf { it.isNotBlank() }
                        if (!verifiedFallback.isNullOrBlank()) {
                            finalDecision = finalDecision.copy(reply = verifiedFallback)
                        } else {
                            return CollaborationOutcome(
                                replyText = null,
                                conversationKey = conversationKey,
                                usedNewApi = usedNewApiFlag,
                                usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
                                activeEngine = activeEngineLabel,
                                switchedToQwen = switchedToQwen,
                                calledSmartAdvisor = true,
                                saIntent = saIntent,
                                verifiedStoreDataUsed = false,
                                memoryLoaded = memoryLoaded,
                                redactedError = pass2Outcome.redactedError ?: "تعذر صياغة الرد النهائي",
                                stageLatenciesMs = latencies,
                                currentTopic = contextEntity.currentTopic
                            )
                        }
                    }
                } else {
                    // HTTP 403 disabled OR has_verified_data == false -> refuse fabrication!
                    verifiedStoreDataUsed = false
                    verifiedDataSnippet = knowledge.error ?: "لا توجد بيانات مؤكدة من المتجر"
                    val noDataPass2 = buildNoVerifiedDataReply(
                        knowledge = knowledge,
                        fallbackFromPass1 = finalDecision.reply
                    )
                    finalDecision = finalDecision.copy(
                        reply = noDataPass2,
                        needsHuman = finalDecision.needsHuman || !knowledge.ok || !knowledge.hasVerifiedData
                    )
                }
            } else if (finalDecision.reply.isBlank()) {
                // Store data requested, Smart Advisor disabled/unavailable -> avoid hallucination
                finalDecision = finalDecision.copy(
                    reply = "لا تتوفر لديّ بيانات المتجر المؤكدة حالياً، يمكنك تأكيد سؤالك وسيتابع معك فريق خدمة العملاء.",
                    needsHuman = true
                )
            }
        }

        // Durable Memory Update via Encrypted Outbox (consent-gated, DM-only, never in sandbox)
        var memoryUpdated = false
        var consentRequiredFlag = false
        val memProposal = finalDecision.memoryUpdate
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

        val cleanedReply = finalDecision.reply
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { raw ->
                if (!input.isSandbox && deliveryContainsEphemeralLink) {
                    secretVault.redact(raw)
                } else {
                    SensitiveDataRedactor.scrub(raw, secretVault)
                }
            }

        return CollaborationOutcome(
            replyText = cleanedReply,
            conversationKey = conversationKey,
            usedNewApi = usedNewApiFlag,
            usedPrimaryEngine = pass1Outcome.primaryEngineLabel,
            activeEngine = activeEngineLabel,
            switchedToQwen = switchedToQwen,
            calledSmartAdvisor = calledSmartAdvisor,
            saIntent = saIntent,
            verifiedStoreDataUsed = verifiedStoreDataUsed,
            verifiedDataSnippet = SensitiveDataRedactor.scrub(verifiedDataSnippet, secretVault),
            memoryLoaded = memoryLoaded,
            memoryUpdated = memoryUpdated,
            needsHuman = finalDecision.needsHuman,
            consentRequiredForMemory = consentRequiredFlag,
            redactedError = finalRedactedError,
            stageLatenciesMs = latencies,
            currentTopic = contextEntity.currentTopic
        )
    }

    private suspend fun composeAccountActionReply(
        actionRes: SmartAdvisorClient.SaAccountActionResponse,
        settings: AiSettingsState,
        recentTurns: List<AIReplyEngine.ChatTurn>,
        userMessage: String
    ): String? {
        // Send ONLY ephemeral safe notice + pass link safely for direct send without storing token in DB!
        if (actionRes.linkCreated && actionRes.ephemeralLoginUrl.isNotBlank()) {
            return "تم تجهيز رابط الدخول السريع لحسابك: ${actionRes.ephemeralLoginUrl}"
        }
        val fact = actionRes.message.ifBlank {
            if (actionRes.accountCreated) "تم إنشاء حسابك بنجاح." else "تم تنفيذ طلب الحساب."
        }
        val pass2 = aiReplyEngine.runCollaborationTurn(
            settings = settings,
            userMessage = userMessage,
            systemInstruction = "أعد صياغة هذه النتيجة بأسلوب مهذب ومختصر جداً باللغة العربية دون أي إضافات غير مؤكدة:\n$fact",
            recentTurns = recentTurns
        )
        return pass2.decision?.reply?.takeIf { it.isNotBlank() } ?: fact
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
            !fallbackFromPass1.contains("سعر", ignoreCase = true) &&
            !fallbackFromPass1.contains("متوفر", ignoreCase = true)
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
        capabilities: SmartAdvisorClient.SaCapabilities?
    ): String {
        return buildString {
            if (aiSettings.systemPrompt.isNotBlank()) {
                append(aiSettings.systemPrompt.trim()).append("\n\n")
            }
            append("أنت مساعد المحادثة الذكي في تطبيق AutoMate AI. ")
            append("العميل: ").append(senderName).append(" (")
                .append(if (isGroup) "محادثة جماعية" else "محادثة خاصة").append(").\n")
            if (localContextBlock.isNotBlank()) {
                append("\nسياق المحادثة الحالي:\n").append(localContextBlock).append('\n')
            }
            if (serverMemoryBlock.isNotBlank()) {
                append("\nذاكرة العميل الدائمة المؤكدة:\n").append(serverMemoryBlock).append('\n')
            }
            append("\nالتعليمات الصارمة:\n")
            append("- أجب بصيغة JSON صالحة بهذه الحقول: intent, confidence, needs_store_data, store_query, store_intent, needs_human, reply, account_action, memory_update.\n")
            append("- قيم store_intent المسموحة: product_search, product_details, offers, store_info, store_location, shipping_policy, returns_policy, general.\n")
            append("- لا تخترع أسعاراً أو توفر منتجات أو سياسات متجر؛ إذا احتجت بيانات المتجر اجعل needs_store_data=true.\n")
            if (!saAvailable || capabilities?.accountActionsEnabled != true) {
                append("- إجراءات الحساب غير متاحة حالياً.\n")
            }
        }.trim()
    }

    private fun buildPass2VerifiedSystemInstruction(
        aiSettings: AiSettingsState,
        senderName: String,
        localContextBlock: String,
        verifiedSnippet: String
    ): String {
        return buildString {
            if (aiSettings.systemPrompt.isNotBlank()) {
                append(aiSettings.systemPrompt.trim()).append("\n\n")
            }
            append("العميل: ").append(senderName).append('\n')
            if (localContextBlock.isNotBlank()) {
                append("سياق الجلسة:\n").append(localContextBlock).append('\n')
            }
            append("\nبيانات المتجر المؤكدة (التزم بها حرفياً ولا تخترع أي معلومة إضافية):\n")
            append(verifiedSnippet).append('\n')
            append("\nأعد بصيغة JSON تحتوي الحقل reply باللغة العربية وبأسلوب مهذب ومختصر مع confidence و needs_human.")
        }.trim()
    }
}
