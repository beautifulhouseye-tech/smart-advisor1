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

enum class ResponseTone(
    val labelAr: String,
    val promptInstruction: String
) {
    FRIENDLY("ودية", "استخدم نبرة ودية دافئة ومرحبة تشعر العميل بالراحة."),
    FORMAL("رسمية", "استخدم نبرة رسمية راقية ومهنية واضحة."),
    PROFESSIONAL("احترافية", "استخدم نبرة احترافية متزنة وموثوقة ومباشرة."),
    MARKETING("تسويقية", "استخدم نبرة تسويقية جذابة تبرز القيمة والمزايا دون مبالغة أو اختلاق معلومات."),
    CONCISE_DIRECT("مختصرة ومباشرة", "كن مختصراً ومباشراً جداً وادخل في صلب الإجابة دون حشو."),
    CUSTOM("مخصصة", "اتبع تعليمات النبرة والأسلوب المخصصة التالية بدقة:")
}

enum class ResponseLength(
    val labelAr: String,
    val defaultMaxChars: Int,
    val suggestedTokens: Int,
    val maxSentencesHint: Int,
    val promptInstruction: String
) {
    VERY_SHORT("قصير جداً", 140, 90, 2, "أجب في جملة إلى جملتين كحد أقصى وبإيجاز شديد."),
    SHORT("قصير", 320, 160, 3, "أجب باختصار واضح في فقرة قصيرة (2-3 جمل)."),
    MEDIUM("متوسط", 650, 320, 6, "أجب بتفصيل متوازن يوضح النقاط الأساسية في فقرة إلى فقرتين."),
    DETAILED("مفصل", 1200, 700, 12, "أجب بشرح وافٍ ومنظم يغطي جميع التفاصيل المهمة دون حشو.")
}

enum class ReplyLanguage(
    val labelAr: String,
    val promptInstruction: String
) {
    AUTO("تلقائي (لغة العميل)", "التزم بلغة رسالة العميل نفسها (العربية للعربية والإنجليزية للإنجليزية)."),
    ARABIC("العربية", "اكتب الرد النهائي باللغة العربية السلسة الواضحة."),
    ENGLISH("الإنجليزية", "Write the final customer reply strictly in clear, natural English."),
    CUSTOM("لغة محددة", "اكتب الرد النهائي باللغة التالية:")
}

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
    val lastErrorRedacted: String = "",
    val responseTone: ResponseTone = ResponseTone.PROFESSIONAL,
    val customToneInstruction: String = "",
    val responseLength: ResponseLength = ResponseLength.SHORT,
    val maxReplyLengthChars: Int = DEFAULT_MAX_REPLY_LENGTH_CHARS,
    val replyLanguage: ReplyLanguage = ReplyLanguage.AUTO,
    val customReplyLanguage: String = "",
    val useConversationContext: Boolean = true,
    val useKeywordRules: Boolean = true,
    val webhookToolEnabled: Boolean = true
) {
    fun effectivePrimaryModel(): String =
        primaryModel.trim().ifBlank { model.trim().ifBlank { provider.defaultModels.first() } }

    fun effectiveOllamaModel(): String =
        ollamaModel.trim().ifBlank { DEFAULT_QWEN_MODEL }

    fun effectiveMaxReplyChars(): Int =
        maxReplyLengthChars.coerceIn(MIN_REPLY_LENGTH_CHARS, MAX_REPLY_LENGTH_CHARS)

    fun effectiveToneInstruction(): String = when (responseTone) {
        ResponseTone.CUSTOM -> customToneInstruction.trim().ifBlank {
            ResponseTone.PROFESSIONAL.promptInstruction
        }.let { "${ResponseTone.CUSTOM.promptInstruction} $it" }
        else -> responseTone.promptInstruction
    }

    fun effectiveLengthInstruction(): String =
        "${responseLength.promptInstruction} (الحد الأقصى لطول الرد النهائي: ${effectiveMaxReplyChars()} حرفاً)."

    fun effectiveLanguageInstruction(): String = when (replyLanguage) {
        ReplyLanguage.CUSTOM -> customReplyLanguage.trim().ifBlank {
            ReplyLanguage.AUTO.promptInstruction
        }.let { "${ReplyLanguage.CUSTOM.promptInstruction} $it" }
        else -> replyLanguage.promptInstruction
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "أنت مساعد ذكي للرد التلقائي على رسائل العملاء بأسلوب مهذب ومختصر باللغة العربية."
        const val DEFAULT_QWEN_MODEL = "qwen2.5:1.5b"
        const val DEFAULT_CONTEXT_WINDOW_PAIRS = 6
        const val MIN_CONTEXT_WINDOW_PAIRS = 6
        const val MAX_CONTEXT_WINDOW_PAIRS = 8
        const val DEFAULT_MAX_REPLY_LENGTH_CHARS = 320
        const val MIN_REPLY_LENGTH_CHARS = 40
        const val MAX_REPLY_LENGTH_CHARS = 2000
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
        val cleanMaxChars = settings.maxReplyLengthChars.coerceIn(
            AiSettingsState.MIN_REPLY_LENGTH_CHARS,
            AiSettingsState.MAX_REPLY_LENGTH_CHARS
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
            putString(KEY_RESPONSE_TONE, settings.responseTone.name)
            putString(KEY_CUSTOM_TONE_INSTRUCTION, settings.customToneInstruction.trim())
            putString(KEY_RESPONSE_LENGTH, settings.responseLength.name)
            putInt(KEY_MAX_REPLY_LENGTH_CHARS, cleanMaxChars)
            putString(KEY_REPLY_LANGUAGE, settings.replyLanguage.name)
            putString(KEY_CUSTOM_REPLY_LANGUAGE, settings.customReplyLanguage.trim())
            putBoolean(KEY_USE_CONVERSATION_CONTEXT, settings.useConversationContext)
            putBoolean(KEY_USE_KEYWORD_RULES, settings.useKeywordRules)
            putBoolean(KEY_WEBHOOK_TOOL_ENABLED, settings.webhookToolEnabled)
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

        val tone = runCatching {
            ResponseTone.valueOf(prefs.getString(KEY_RESPONSE_TONE, ResponseTone.PROFESSIONAL.name) ?: ResponseTone.PROFESSIONAL.name)
        }.getOrDefault(ResponseTone.PROFESSIONAL)

        val length = runCatching {
            ResponseLength.valueOf(prefs.getString(KEY_RESPONSE_LENGTH, ResponseLength.SHORT.name) ?: ResponseLength.SHORT.name)
        }.getOrDefault(ResponseLength.SHORT)

        val language = runCatching {
            ReplyLanguage.valueOf(prefs.getString(KEY_REPLY_LANGUAGE, ReplyLanguage.AUTO.name) ?: ReplyLanguage.AUTO.name)
        }.getOrDefault(ReplyLanguage.AUTO)

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
            lastErrorRedacted = prefs.getString(KEY_LAST_ERROR_REDACTED, "").orEmpty(),
            responseTone = tone,
            customToneInstruction = prefs.getString(KEY_CUSTOM_TONE_INSTRUCTION, "").orEmpty(),
            responseLength = length,
            maxReplyLengthChars = prefs.getInt(
                KEY_MAX_REPLY_LENGTH_CHARS,
                length.defaultMaxChars
            ).coerceIn(
                AiSettingsState.MIN_REPLY_LENGTH_CHARS,
                AiSettingsState.MAX_REPLY_LENGTH_CHARS
            ),
            replyLanguage = language,
            customReplyLanguage = prefs.getString(KEY_CUSTOM_REPLY_LANGUAGE, "").orEmpty(),
            useConversationContext = prefs.getBoolean(KEY_USE_CONVERSATION_CONTEXT, true),
            useKeywordRules = prefs.getBoolean(KEY_USE_KEYWORD_RULES, true),
            webhookToolEnabled = prefs.getBoolean(KEY_WEBHOOK_TOOL_ENABLED, true)
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
        private const val KEY_RESPONSE_TONE = "ai_response_tone"
        private const val KEY_CUSTOM_TONE_INSTRUCTION = "ai_custom_tone_instruction"
        private const val KEY_RESPONSE_LENGTH = "ai_response_length"
        private const val KEY_MAX_REPLY_LENGTH_CHARS = "ai_max_reply_length_chars"
        private const val KEY_REPLY_LANGUAGE = "ai_reply_language"
        private const val KEY_CUSTOM_REPLY_LANGUAGE = "ai_custom_reply_language"
        private const val KEY_USE_CONVERSATION_CONTEXT = "ai_use_conversation_context"
        private const val KEY_USE_KEYWORD_RULES = "ai_use_keyword_rules"
        private const val KEY_WEBHOOK_TOOL_ENABLED = "ai_webhook_tool_enabled"

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
