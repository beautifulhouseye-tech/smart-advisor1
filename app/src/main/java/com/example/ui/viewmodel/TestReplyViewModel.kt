package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.ReplySourceCategory
import com.example.engine.UnifiedReplyTester
import com.example.engine.UnifiedTestResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class TestChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val timestampMs: Long,
    val sourceLabel: String? = null,
    val sourceCategory: ReplySourceCategory? = null,
    val evaluatedPath: List<String> = emptyList(),
    val imageUri: String? = null,
    val latencyMs: Long = 0L,
    val isWelcome: Boolean = false,
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

data class TestReplyUiState(
    val messages: List<TestChatMessage> = emptyList(),
    val isEvaluating: Boolean = false,
    val isGroupMode: Boolean = false,
    val testSenderPhone: String = "",
    val activePipelineSummary: String = "القوائم • القواعد المحلية"
)

@HiltViewModel
class TestReplyViewModel internal constructor(
    private val unifiedReplyTester: UnifiedReplyTester,
    private val repository: AutoMateRepository,
    private val aiSettingsPrefs: AiSettingsPrefs,
    private val serverConfigRepository: ServerConfigRepository,
    private val smartAdvisorPrefs: SmartAdvisorPrefs,
    private val clockMs: () -> Long
) : ViewModel() {

    @Inject
    constructor(
        unifiedReplyTester: UnifiedReplyTester,
        repository: AutoMateRepository,
        aiSettingsPrefs: AiSettingsPrefs,
        serverConfigRepository: ServerConfigRepository,
        smartAdvisorPrefs: SmartAdvisorPrefs
    ) : this(
        unifiedReplyTester = unifiedReplyTester,
        repository = repository,
        aiSettingsPrefs = aiSettingsPrefs,
        serverConfigRepository = serverConfigRepository,
        smartAdvisorPrefs = smartAdvisorPrefs,
        clockMs = { System.currentTimeMillis() }
    )

    private val _uiState = MutableStateFlow(
        TestReplyUiState(
            messages = listOf(createWelcomeMessage(clockMs()))
        )
    )
    val uiState: StateFlow<TestReplyUiState> = _uiState.asStateFlow()

    init {
        refreshCapabilitiesSummary()
    }

    fun refreshCapabilitiesSummary() {
        viewModelScope.launch {
            val settings = repository.getCurrentSettings()
            val server = serverConfigRepository.get()
            val ai = aiSettingsPrefs.current()
            val parts = buildList {
                if (settings.isDrivingModeEnabled) add("القيادة")
                add("القوائم")
                if (ai.useKeywordRules) add("القواعد")
                if (ai.enabled) {
                    add(ai.provider.labelAr)
                    if (ai.fallbackEnabled) add(ai.effectiveOllamaModel())
                    add("${ai.responseTone.labelAr}/${ai.responseLength.labelAr}")
                }
                if (server?.isEnabled == true) {
                    if (ai.enabled && ai.webhookToolEnabled &&
                        server.effectiveWebhookMode() == com.example.data.local.entity.WebhookMode.AI_TOOL
                    ) {
                        add("أداة: Webhook")
                    } else if (!ai.enabled &&
                        server.effectiveWebhookMode() == com.example.data.local.entity.WebhookMode.STANDALONE_FALLBACK
                    ) {
                        add("Webhook")
                    }
                }
                if (smartAdvisorPrefs.enabled) {
                    if (ai.enabled && ai.saCollaborationEnabled) {
                        add("أداة: Smart Advisor")
                    } else if (!ai.enabled) {
                        add("Smart Advisor")
                    }
                }
            }
            _uiState.update {
                it.copy(activePipelineSummary = parts.joinToString(" • "))
            }
        }
    }

    fun toggleGroupMode(enabled: Boolean) {
        _uiState.update { it.copy(isGroupMode = enabled) }
    }

    fun updateSenderPhone(phone: String) {
        _uiState.update { it.copy(testSenderPhone = phone) }
    }

    fun sendTestMessage(rawText: String) {
        val cleanText = rawText.trim()
        if (cleanText.isEmpty() || _uiState.value.isEvaluating) return

        val sentAt = clockMs()
        val userMsg = TestChatMessage(
            text = cleanText,
            isFromUser = true,
            timestampMs = sentAt
        )
        val groupMode = _uiState.value.isGroupMode
        val phone = _uiState.value.testSenderPhone.trim()

        _uiState.update { current ->
            current.copy(
                messages = current.messages + userMsg,
                isEvaluating = true
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                unifiedReplyTester.evaluate(
                    message = cleanText,
                    isGroup = groupMode,
                    phone = phone
                )
            }.getOrElse { err ->
                UnifiedTestResult(
                    matched = false,
                    replyText = err.localizedMessage ?: "حدث خطأ غير متوقع أثناء الاختبار",
                    sourceLabel = ReplySourceCategory.ERROR.labelAr,
                    sourceCategory = ReplySourceCategory.ERROR,
                    redactedError = err.localizedMessage
                )
            }

            val botMsg = TestChatMessage(
                text = result.replyText,
                isFromUser = false,
                timestampMs = clockMs(),
                sourceLabel = result.sourceLabel,
                sourceCategory = result.sourceCategory,
                evaluatedPath = result.evaluatedPath,
                imageUri = result.imageUri,
                latencyMs = result.latencyMs,
                conversationKeyUsed = result.conversationKeyUsed,
                matchedRuleKeyword = result.matchedRuleKeyword,
                usedNewApi = result.usedNewApi,
                activeEngineLabel = result.activeEngineLabel,
                switchedToQwen = result.switchedToQwen,
                calledSmartAdvisor = result.calledSmartAdvisor,
                calledWebhook = result.calledWebhook,
                detectedIntent = result.detectedIntent,
                saToolReason = result.saToolReason,
                webhookToolReason = result.webhookToolReason,
                webhookDataSnippet = result.webhookDataSnippet,
                webhookStatusCode = result.webhookStatusCode,
                invokedTools = result.invokedTools,
                toolRoundsUsed = result.toolRoundsUsed,
                verifiedStoreDataUsed = result.verifiedStoreDataUsed,
                verifiedDataSnippet = result.verifiedDataSnippet,
                memoryLoaded = result.memoryLoaded,
                memoryUpdated = result.memoryUpdated,
                needsHuman = result.needsHuman,
                stageLatenciesMs = result.stageLatenciesMs,
                redactedError = result.redactedError
            )

            _uiState.update { current ->
                current.copy(
                    messages = current.messages + botMsg,
                    isEvaluating = false
                )
            }
        }
    }

    fun resetConversation() {
        unifiedReplyTester.resetSession()
        _uiState.update { current ->
            current.copy(
                messages = listOf(createWelcomeMessage(clockMs())),
                isEvaluating = false
            )
        }
    }

    companion object {
        const val WELCOME_TEXT =
            "مرحباً! يمكنك اختبار الرد التلقائي الخاص بك عن طريق إرسال رسالة إلى هذه المحادثة."

        private fun createWelcomeMessage(nowMs: Long): TestChatMessage {
            return TestChatMessage(
                id = "welcome_msg",
                text = WELCOME_TEXT,
                isFromUser = false,
                timestampMs = nowMs,
                isWelcome = true
            )
        }
    }
}
