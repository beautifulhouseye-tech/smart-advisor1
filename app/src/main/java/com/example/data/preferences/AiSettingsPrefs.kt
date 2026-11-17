package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SensitiveDataRedactor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AiSettingsState(
    val enabled: Boolean = false,
    val provider: AIReplyEngine.Provider = AIReplyEngine.Provider.OPENAI,
    val model: String = "gpt-3.5-turbo",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 150,
    val primaryBaseUrl: String = "",
    val primaryModel: String = "",
    val fallbackEnabled: Boolean = true,
    val fallbackUseNewApiGateway: Boolean = false,
    val ollamaBaseUrl: String = "",
    val ollamaModel: String = DEFAULT_QWEN_MODEL,
    val contextWindowPairs: Int = DEFAULT_CONTEXT_WINDOW_PAIRS,
    val syncCustomerMemory: Boolean = false,
    val requireCustomerConsent: Boolean = true,
    val saCollaborationEnabled: Boolean = true,
    val safeFallbackReply: String = "",
    val lastPrimaryStatus: String = "غير مختبر",
    val lastFallbackStatus: String = "غير مختبر",
    val lastErrorRedacted: String = ""
) {
    fun effectivePrimaryModel(): String =
        primaryModel.trim().ifBlank { model.trim().ifBlank { provider.defaultModels.first() } }

    fun effectiveOllamaModel(): String =
        ollamaModel.trim().ifBlank { DEFAULT_QWEN_MODEL }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "أنت مساعد ذكي للرد التلقائي على رسائل العملاء بأسلوب مهذب ومختصر باللغة العربية."
        const val DEFAULT_QWEN_MODEL = "qwen2.5:1.5b"
        const val DEFAULT_CONTEXT_WINDOW_PAIRS = 6
        const val MIN_CONTEXT_WINDOW_PAIRS = 6
        const val MAX_CONTEXT_WINDOW_PAIRS = 8
    }
}

@Singleton
class AiSettingsPrefs internal constructor(
    context: Context,
    private val overridePrefs: SharedPreferences?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    private val prefs: SharedPreferences by lazy {
        overridePrefs ?: run {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AiSettingsState> = _state.asStateFlow()

    fun current(): AiSettingsState = read()

    fun save(settings: AiSettingsState) {
        val cleanMaxTokens = settings.maxTokens.coerceIn(50, 1200)
        val cleanContextPairs = settings.contextWindowPairs.coerceIn(
            AiSettingsState.MIN_CONTEXT_WINDOW_PAIRS,
            AiSettingsState.MAX_CONTEXT_WINDOW_PAIRS
        )
        prefs.edit(commit = true) {
            putBoolean(KEY_ENABLED, settings.enabled)
            putString(KEY_PROVIDER, settings.provider.name)
            putString(KEY_MODEL, settings.model.trim())
            putString(KEY_SYSTEM_PROMPT, settings.systemPrompt.trim())
            putFloat(KEY_TEMPERATURE, settings.temperature.coerceIn(0f, 1f))
            putInt(KEY_MAX_TOKENS, cleanMaxTokens)
            putString(KEY_PRIMARY_BASE_URL, settings.primaryBaseUrl.trim().trimEnd('/'))
            putString(KEY_PRIMARY_MODEL, settings.primaryModel.trim())
            putBoolean(KEY_FALLBACK_ENABLED, settings.fallbackEnabled)
            putBoolean(KEY_FALLBACK_USE_GATEWAY, settings.fallbackUseNewApiGateway)
            putString(KEY_OLLAMA_BASE_URL, settings.ollamaBaseUrl.trim().trimEnd('/'))
            putString(
                KEY_OLLAMA_MODEL,
                settings.ollamaModel.trim().ifBlank { AiSettingsState.DEFAULT_QWEN_MODEL }
            )
            putInt(KEY_CONTEXT_WINDOW_PAIRS, cleanContextPairs)
            putBoolean(KEY_SYNC_CUSTOMER_MEMORY, settings.syncCustomerMemory)
            putBoolean(KEY_REQUIRE_CUSTOMER_CONSENT, settings.requireCustomerConsent)
            putBoolean(KEY_SA_COLLABORATION_ENABLED, settings.saCollaborationEnabled)
            putString(KEY_SAFE_FALLBACK_REPLY, settings.safeFallbackReply.trim())
            putString(KEY_LAST_PRIMARY_STATUS, settings.lastPrimaryStatus.trim())
            putString(KEY_LAST_FALLBACK_STATUS, settings.lastFallbackStatus.trim())
            putString(KEY_LAST_ERROR_REDACTED, SensitiveDataRedactor.scrub(settings.lastErrorRedacted.trim()))
        }
        _state.value = read()
    }

    fun updateHealthStatus(
        primaryStatus: String? = null,
        fallbackStatus: String? = null,
        lastErrorRedacted: String? = null
    ) {
        val current = read()
        save(
            current.copy(
                lastPrimaryStatus = primaryStatus ?: current.lastPrimaryStatus,
                lastFallbackStatus = fallbackStatus ?: current.lastFallbackStatus,
                lastErrorRedacted = lastErrorRedacted?.let { SensitiveDataRedactor.scrub(it) }
                    ?: current.lastErrorRedacted
            )
        )
    }

    private fun read(): AiSettingsState {
        val providerName = prefs.getString(KEY_PROVIDER, AIReplyEngine.Provider.OPENAI.name)
        val provider = runCatching {
            AIReplyEngine.Provider.valueOf(providerName ?: AIReplyEngine.Provider.OPENAI.name)
        }.getOrDefault(AIReplyEngine.Provider.OPENAI)

        return AiSettingsState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            provider = provider,
            model = prefs.getString(KEY_MODEL, provider.defaultModels.first())
                .orEmpty()
                .ifBlank { provider.defaultModels.first() },
            systemPrompt = prefs.getString(
                KEY_SYSTEM_PROMPT,
                AiSettingsState.DEFAULT_SYSTEM_PROMPT
            ).orEmpty(),
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f).coerceIn(0f, 1f),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 150).coerceIn(50, 1200),
            primaryBaseUrl = prefs.getString(KEY_PRIMARY_BASE_URL, "").orEmpty(),
            primaryModel = prefs.getString(KEY_PRIMARY_MODEL, "").orEmpty(),
            fallbackEnabled = prefs.getBoolean(KEY_FALLBACK_ENABLED, true),
            fallbackUseNewApiGateway = prefs.getBoolean(KEY_FALLBACK_USE_GATEWAY, false),
            ollamaBaseUrl = prefs.getString(KEY_OLLAMA_BASE_URL, "").orEmpty(),
            ollamaModel = prefs.getString(KEY_OLLAMA_MODEL, AiSettingsState.DEFAULT_QWEN_MODEL)
                .orEmpty()
                .ifBlank { AiSettingsState.DEFAULT_QWEN_MODEL },
            contextWindowPairs = prefs.getInt(
                KEY_CONTEXT_WINDOW_PAIRS,
                AiSettingsState.DEFAULT_CONTEXT_WINDOW_PAIRS
            ).coerceIn(
                AiSettingsState.MIN_CONTEXT_WINDOW_PAIRS,
                AiSettingsState.MAX_CONTEXT_WINDOW_PAIRS
            ),
            syncCustomerMemory = prefs.getBoolean(KEY_SYNC_CUSTOMER_MEMORY, false),
            requireCustomerConsent = prefs.getBoolean(KEY_REQUIRE_CUSTOMER_CONSENT, true),
            saCollaborationEnabled = prefs.getBoolean(KEY_SA_COLLABORATION_ENABLED, true),
            safeFallbackReply = prefs.getString(KEY_SAFE_FALLBACK_REPLY, "").orEmpty(),
            lastPrimaryStatus = prefs.getString(KEY_LAST_PRIMARY_STATUS, "غير مختبر").orEmpty(),
            lastFallbackStatus = prefs.getString(KEY_LAST_FALLBACK_STATUS, "غير مختبر").orEmpty(),
            lastErrorRedacted = prefs.getString(KEY_LAST_ERROR_REDACTED, "").orEmpty()
        )
    }

    companion object {
        private const val PREFS_FILE_NAME = "automate_ai_engine_prefs"
        private const val KEY_ENABLED = "ai_enabled"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_MODEL = "ai_model"
        private const val KEY_SYSTEM_PROMPT = "ai_system_prompt"
        private const val KEY_TEMPERATURE = "ai_temperature"
        private const val KEY_MAX_TOKENS = "ai_max_tokens"
        private const val KEY_PRIMARY_BASE_URL = "ai_primary_base_url"
        private const val KEY_PRIMARY_MODEL = "ai_primary_model"
        private const val KEY_FALLBACK_ENABLED = "ai_fallback_enabled"
        private const val KEY_FALLBACK_USE_GATEWAY = "ai_fallback_use_new_api_gateway"
        private const val KEY_OLLAMA_BASE_URL = "ai_ollama_base_url"
        private const val KEY_OLLAMA_MODEL = "ai_ollama_model"
        private const val KEY_CONTEXT_WINDOW_PAIRS = "ai_context_window_pairs"
        private const val KEY_SYNC_CUSTOMER_MEMORY = "ai_sync_customer_memory"
        private const val KEY_REQUIRE_CUSTOMER_CONSENT = "ai_require_customer_consent"
        private const val KEY_SA_COLLABORATION_ENABLED = "ai_sa_collaboration_enabled"
        private const val KEY_SAFE_FALLBACK_REPLY = "ai_safe_fallback_reply"
        private const val KEY_LAST_PRIMARY_STATUS = "ai_last_primary_status"
        private const val KEY_LAST_FALLBACK_STATUS = "ai_last_fallback_status"
        private const val KEY_LAST_ERROR_REDACTED = "ai_last_error_redacted"

        const val KEY_REF_NEW_API = "ai_api_key_new_api"
        const val KEY_REF_OLLAMA = "ai_api_key_ollama"

        fun apiKeyRefFor(provider: AIReplyEngine.Provider): String {
            return when (provider) {
                AIReplyEngine.Provider.NEW_API -> KEY_REF_NEW_API
                AIReplyEngine.Provider.OPENAI -> "ai_api_key_openai"
                AIReplyEngine.Provider.GEMINI -> "ai_api_key_gemini"
                AIReplyEngine.Provider.OLLAMA -> KEY_REF_OLLAMA
            }
        }
    }
}
