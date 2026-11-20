package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.ReplyLanguage
import com.example.data.preferences.ResponseLength
import com.example.data.preferences.ResponseTone
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AISettingsUiState(
    val enabled: Boolean = false,
    val provider: AIReplyEngine.Provider = AIReplyEngine.Provider.OPENAI,
    val model: String = "gpt-3.5-turbo",
    val apiKey: String = "",
    val primaryBaseUrl: String = "",
    val primaryModel: String = "",
    val newApiKey: String = "",
    val fallbackEnabled: Boolean = true,
    val fallbackUseNewApiGateway: Boolean = false,
    val ollamaBaseUrl: String = "",
    val ollamaModel: String = AiSettingsState.DEFAULT_QWEN_MODEL,
    val ollamaApiKey: String = "",
    val contextWindowPairs: Int = AiSettingsState.DEFAULT_CONTEXT_WINDOW_PAIRS,
    val saCollaborationEnabled: Boolean = true,
    val syncCustomerMemory: Boolean = false,
    val requireCustomerConsent: Boolean = true,
    val safeFallbackReply: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 150,
    val responseTone: ResponseTone = ResponseTone.PROFESSIONAL,
    val customToneInstruction: String = "",
    val responseLength: ResponseLength = ResponseLength.SHORT,
    val maxReplyLengthChars: Int = AiSettingsState.DEFAULT_MAX_REPLY_LENGTH_CHARS,
    val replyLanguage: ReplyLanguage = ReplyLanguage.AUTO,
    val customReplyLanguage: String = "",
    val useConversationContext: Boolean = true,
    val useKeywordRules: Boolean = true,
    val webhookToolEnabled: Boolean = true,
    val isTesting: Boolean = false,
    val isTestingFallback: Boolean = false,
    val testResult: String? = null,
    val fallbackTestResult: String? = null,
    val lastPrimaryStatus: String = "غير مختبر",
    val lastFallbackStatus: String = "غير مختبر",
    val lastErrorRedacted: String = "",
    val statusMessage: String? = null
)

@HiltViewModel
class AISettingsViewModel @Inject constructor(
    private val aiSettingsPrefs: AiSettingsPrefs,
    private val vault: SecretVault,
    private val aiReplyEngine: AIReplyEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadInitial())
    val uiState: StateFlow<AISettingsUiState> = _uiState.asStateFlow()

    private fun loadInitial(): AISettingsUiState {
        val current = aiSettingsPrefs.current()
        val key = vault.get(AiSettingsPrefs.apiKeyRefFor(current.provider))
        val newApiKey = vault.get(AiSettingsPrefs.KEY_REF_NEW_API)
        val ollamaKey = vault.get(AiSettingsPrefs.KEY_REF_OLLAMA)
        return AISettingsUiState(
            enabled = current.enabled,
            provider = current.provider,
            model = current.model,
            apiKey = key,
            primaryBaseUrl = current.primaryBaseUrl,
            primaryModel = current.primaryModel,
            newApiKey = newApiKey,
            fallbackEnabled = current.fallbackEnabled,
            fallbackUseNewApiGateway = current.fallbackUseNewApiGateway,
            ollamaBaseUrl = current.ollamaBaseUrl,
            ollamaModel = current.ollamaModel,
            ollamaApiKey = ollamaKey,
            contextWindowPairs = current.contextWindowPairs,
            saCollaborationEnabled = current.saCollaborationEnabled,
            syncCustomerMemory = current.syncCustomerMemory,
            requireCustomerConsent = current.requireCustomerConsent,
            safeFallbackReply = current.safeFallbackReply,
            systemPrompt = current.systemPrompt,
            temperature = current.temperature,
            maxTokens = current.maxTokens,
            responseTone = current.responseTone,
            customToneInstruction = current.customToneInstruction,
            responseLength = current.responseLength,
            maxReplyLengthChars = current.maxReplyLengthChars,
            replyLanguage = current.replyLanguage,
            customReplyLanguage = current.customReplyLanguage,
            useConversationContext = current.useConversationContext,
            useKeywordRules = current.useKeywordRules,
            webhookToolEnabled = current.webhookToolEnabled,
            lastPrimaryStatus = current.lastPrimaryStatus,
            lastFallbackStatus = current.lastFallbackStatus,
            lastErrorRedacted = current.lastErrorRedacted
        )
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
    }

    fun selectProvider(provider: AIReplyEngine.Provider) {
        val savedKey = vault.get(AiSettingsPrefs.apiKeyRefFor(provider))
        val defaultModel = provider.defaultModels.first()
        _uiState.update {
            it.copy(
                provider = provider,
                model = defaultModel,
                apiKey = savedKey,
                testResult = null
            )
        }
    }

    fun selectModel(model: String) {
        _uiState.update { it.copy(model = model) }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }

    fun updatePrimaryBaseUrl(url: String) {
        _uiState.update { it.copy(primaryBaseUrl = url) }
    }

    fun updatePrimaryModel(model: String) {
        _uiState.update { it.copy(primaryModel = model) }
    }

    fun updateNewApiKey(key: String) {
        _uiState.update { it.copy(newApiKey = key) }
    }

    fun setFallbackEnabled(enabled: Boolean) {
        _uiState.update { it.copy(fallbackEnabled = enabled) }
    }

    fun setFallbackUseNewApiGateway(enabled: Boolean) {
        _uiState.update { it.copy(fallbackUseNewApiGateway = enabled) }
    }

    fun updateOllamaBaseUrl(url: String) {
        _uiState.update { it.copy(ollamaBaseUrl = url) }
    }

    fun updateOllamaModel(model: String) {
        _uiState.update { it.copy(ollamaModel = model) }
    }

    fun updateOllamaApiKey(key: String) {
        _uiState.update { it.copy(ollamaApiKey = key) }
    }

    fun updateContextWindowPairs(pairs: Int) {
        _uiState.update {
            it.copy(
                contextWindowPairs = pairs.coerceIn(
                    AiSettingsState.MIN_CONTEXT_WINDOW_PAIRS,
                    AiSettingsState.MAX_CONTEXT_WINDOW_PAIRS
                )
            )
        }
    }

    fun setSaCollaborationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(saCollaborationEnabled = enabled) }
    }

    fun setSyncCustomerMemory(enabled: Boolean) {
        _uiState.update { it.copy(syncCustomerMemory = enabled) }
    }

    fun setRequireCustomerConsent(required: Boolean) {
        _uiState.update { it.copy(requireCustomerConsent = required) }
    }

    fun updateSafeFallbackReply(text: String) {
        _uiState.update { it.copy(safeFallbackReply = text) }
    }

    fun updateTemperature(temp: Float) {
        _uiState.update { it.copy(temperature = temp.coerceIn(0f, 1f)) }
    }

    fun updateMaxTokens(tokensText: String) {
        val parsed = tokensText.filter { it.isDigit() }.toIntOrNull()
        if (parsed != null) {
            _uiState.update { it.copy(maxTokens = parsed.coerceIn(50, 1200)) }
        }
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.update { it.copy(systemPrompt = prompt) }
    }

    fun selectResponseTone(tone: ResponseTone) {
        _uiState.update { it.copy(responseTone = tone) }
    }

    fun updateCustomToneInstruction(instruction: String) {
        _uiState.update { it.copy(customToneInstruction = instruction) }
    }

    fun selectResponseLength(length: ResponseLength) {
        _uiState.update {
            it.copy(
                responseLength = length,
                maxReplyLengthChars = length.defaultMaxChars
            )
        }
    }

    fun updateMaxReplyLengthChars(charsText: String) {
        val digits = charsText.filter { it.isDigit() }
        if (digits.isEmpty()) {
            _uiState.update { it.copy(maxReplyLengthChars = 0) }
            return
        }
        val parsed = digits.toIntOrNull() ?: return
        _uiState.update {
            it.copy(maxReplyLengthChars = parsed.coerceAtMost(AiSettingsState.MAX_REPLY_LENGTH_CHARS))
        }
    }

    fun selectReplyLanguage(language: ReplyLanguage) {
        _uiState.update { it.copy(replyLanguage = language) }
    }

    fun updateCustomReplyLanguage(language: String) {
        _uiState.update { it.copy(customReplyLanguage = language) }
    }

    fun setUseConversationContext(enabled: Boolean) {
        _uiState.update { it.copy(useConversationContext = enabled) }
    }

    fun setUseKeywordRules(enabled: Boolean) {
        _uiState.update { it.copy(useKeywordRules = enabled) }
    }

    fun setWebhookToolEnabled(enabled: Boolean) {
        _uiState.update { it.copy(webhookToolEnabled = enabled) }
    }

    fun saveSettings() {
        persistSettings(showStatus = true, statusText = "تم حفظ إعدادات الذكاء الاصطناعي")
    }

    fun saveMemoryAndTools() {
        persistSettings(showStatus = true, statusText = "تم حفظ إعدادات الذاكرة والأدوات")
    }

    fun persistMemoryAndToolsSilently() {
        persistSettings(showStatus = false)
    }

    private fun persistSettings(
        showStatus: Boolean,
        statusText: String = "تم حفظ إعدادات الذكاء الاصطناعي والتعاون الذكي"
    ) {
        val state = _uiState.value
        val keyRef = AiSettingsPrefs.apiKeyRefFor(state.provider)
        if (state.apiKey.isNotBlank()) {
            vault.put(keyRef, state.apiKey.trim())
        } else {
            vault.remove(keyRef)
        }

        if (state.newApiKey.isNotBlank()) {
            vault.put(AiSettingsPrefs.KEY_REF_NEW_API, state.newApiKey.trim())
        } else if (state.provider == AIReplyEngine.Provider.NEW_API && state.apiKey.isNotBlank()) {
            vault.put(AiSettingsPrefs.KEY_REF_NEW_API, state.apiKey.trim())
        } else if (state.newApiKey.isBlank() && state.provider != AIReplyEngine.Provider.NEW_API) {
            vault.remove(AiSettingsPrefs.KEY_REF_NEW_API)
        }

        if (state.ollamaApiKey.isNotBlank()) {
            vault.put(AiSettingsPrefs.KEY_REF_OLLAMA, state.ollamaApiKey.trim())
        } else {
            vault.remove(AiSettingsPrefs.KEY_REF_OLLAMA)
        }

        val settingsState = buildSettingsState(state)
        aiSettingsPrefs.save(settingsState)
        _uiState.update {
            it.copy(
                maxReplyLengthChars = settingsState.maxReplyLengthChars,
                statusMessage = if (showStatus) statusText else it.statusMessage
            )
        }
    }

    fun testAiReply() {
        val state = _uiState.value
        saveSettings()
        val settingsState = aiSettingsPrefs.current()

        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            val outcome = aiReplyEngine.testPrimaryConnection(settingsState)
            val statusStr = if (outcome.success) {
                "متصل (${outcome.latencyMs}ms)"
            } else {
                "فشل (${outcome.statusCode})"
            }
            aiSettingsPrefs.updateHealthStatus(
                primaryStatus = statusStr,
                lastErrorRedacted = outcome.redactedError.orEmpty()
            )
            _uiState.update {
                it.copy(
                    isTesting = false,
                    lastPrimaryStatus = statusStr,
                    lastErrorRedacted = outcome.redactedError.orEmpty(),
                    testResult = if (outcome.success) {
                        "${outcome.engineLabel} (${outcome.latencyMs}ms): ${outcome.replyPreview}"
                    } else {
                        outcome.redactedError ?: "فشل اختبار المحرك الرئيسي"
                    }
                )
            }
        }
    }

    fun testFallbackQwen() {
        saveSettings()
        val settingsState = aiSettingsPrefs.current()
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingFallback = true, fallbackTestResult = null) }
            val outcome = aiReplyEngine.testFallbackConnection(settingsState)
            val statusStr = if (outcome.success) {
                "متصل (${outcome.latencyMs}ms)"
            } else {
                "غير متصل (${outcome.statusCode})"
            }
            aiSettingsPrefs.updateHealthStatus(
                fallbackStatus = statusStr,
                lastErrorRedacted = outcome.redactedError.orEmpty()
            )
            _uiState.update {
                it.copy(
                    isTestingFallback = false,
                    lastFallbackStatus = statusStr,
                    lastErrorRedacted = outcome.redactedError.orEmpty(),
                    fallbackTestResult = if (outcome.success) {
                        "${outcome.engineLabel} (${outcome.latencyMs}ms): ${outcome.replyPreview}"
                    } else {
                        outcome.redactedError ?: "فشل اختبار المحرك الاحتياطي Ollama / Qwen"
                    }
                )
            }
        }
    }

    private fun buildSettingsState(state: AISettingsUiState): AiSettingsState {
        val normalizedChars = if (state.maxReplyLengthChars <= 0) {
            state.responseLength.defaultMaxChars
        } else {
            state.maxReplyLengthChars.coerceIn(
                AiSettingsState.MIN_REPLY_LENGTH_CHARS,
                AiSettingsState.MAX_REPLY_LENGTH_CHARS
            )
        }
        return AiSettingsState(
            enabled = state.enabled,
            provider = state.provider,
            model = state.model,
            systemPrompt = state.systemPrompt,
            temperature = state.temperature,
            maxTokens = state.maxTokens,
            primaryBaseUrl = state.primaryBaseUrl,
            primaryModel = state.primaryModel,
            fallbackEnabled = state.fallbackEnabled,
            fallbackUseNewApiGateway = state.fallbackUseNewApiGateway,
            ollamaBaseUrl = state.ollamaBaseUrl,
            ollamaModel = state.ollamaModel,
            contextWindowPairs = state.contextWindowPairs,
            syncCustomerMemory = state.syncCustomerMemory,
            requireCustomerConsent = state.requireCustomerConsent,
            saCollaborationEnabled = state.saCollaborationEnabled,
            safeFallbackReply = state.safeFallbackReply,
            lastPrimaryStatus = state.lastPrimaryStatus,
            lastFallbackStatus = state.lastFallbackStatus,
            lastErrorRedacted = state.lastErrorRedacted,
            responseTone = state.responseTone,
            customToneInstruction = state.customToneInstruction,
            responseLength = state.responseLength,
            maxReplyLengthChars = normalizedChars,
            replyLanguage = state.replyLanguage,
            customReplyLanguage = state.customReplyLanguage,
            useConversationContext = state.useConversationContext,
            useKeywordRules = state.useKeywordRules,
            webhookToolEnabled = state.webhookToolEnabled
        )
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
