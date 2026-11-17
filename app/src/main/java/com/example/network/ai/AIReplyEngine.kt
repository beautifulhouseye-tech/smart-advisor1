package com.example.network.ai

import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.network.HttpTransport
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIReplyEngine @Inject constructor(
    private val transport: HttpTransport,
    private val vault: SecretVault
) {
    enum class Provider(val labelAr: String, val defaultModels: List<String>) {
        NEW_API("New API (بوابة OpenAI)", listOf("gpt-4o-mini", "gpt-4o", "qwen2.5:7b")),
        OLLAMA("Ollama (Qwen احتياطي)", listOf("qwen2.5:1.5b", "qwen2.5:7b", "llama3")),
        OPENAI("OpenAI", listOf("gpt-3.5-turbo", "gpt-4", "gpt-4o")),
        GEMINI("Gemini", listOf("gemini-pro", "gemini-1.5-pro"))
    }

    data class AIConfig(
        val provider: Provider,
        val apiKey: String,
        val model: String,
        val systemPrompt: String,
        val temperature: Float = 0.7f,
        val maxTokens: Int = 150,
        val baseUrl: String = ""
    )

    data class ChatTurn(
        val role: String,
        val text: String,
        val timestampMs: Long = 0L
    )

    data class AiMemoryProposal(
        val summary: String = "",
        val preferences: List<String> = emptyList(),
        val interests: List<String> = emptyList(),
        val constraints: List<String> = emptyList(),
        val consentConfirmed: Boolean = false
    )

    data class AiStructuredDecision(
        val reply: String = "",
        val intent: String = "general",
        val confidence: Double = 0.0,
        val needsStoreData: Boolean = false,
        val storeQuery: String = "",
        val storeIntent: String = "general",
        val needsHuman: Boolean = false,
        val accountAction: String = "",
        val memoryUpdate: AiMemoryProposal? = null,
        val isStructuredJson: Boolean = false
    )

    sealed class UrlValidationResult {
        data class Valid(val resolvedUrl: String) : UrlValidationResult()
        data class Invalid(val errorAr: String) : UrlValidationResult()
    }

    data class CollaborationAiOutcome(
        val decision: AiStructuredDecision?,
        val primaryEngineLabel: String,
        val activeEngineLabel: String,
        val switchedToFallback: Boolean,
        val primaryLatencyMs: Long = 0L,
        val fallbackLatencyMs: Long = 0L,
        val redactedError: String? = null
    )

    data class ConnectionTestOutcome(
        val success: Boolean,
        val engineLabel: String,
        val model: String,
        val statusCode: Int,
        val latencyMs: Long,
        val replyPreview: String = "",
        val redactedError: String? = null
    )

    private val parserJson = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Legacy API (Preserved 100% for backward compatibility & existing unit tests)
    // -------------------------------------------------------------------------

    suspend fun generateReply(
        message: String,
        config: AIConfig,
        context: String = ""
    ): String? {
        return when (config.provider) {
            Provider.OPENAI -> callOpenAiCompatible(
                endpointUrl = resolveOpenAiEndpoint(config.baseUrl, fallbackToDefaultOpenAi = true),
                apiKey = resolveApiKey(config),
                model = config.model.ifBlank { "gpt-3.5-turbo" },
                systemContent = buildLegacySystemContent(config.systemPrompt, context),
                turns = emptyList(),
                userMessage = message,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                requireApiKey = true
            ).text
            Provider.NEW_API -> {
                val validated = validateExternalUrl(config.baseUrl, "/v1/chat/completions")
                if (validated !is UrlValidationResult.Valid) return null
                callOpenAiCompatible(
                    endpointUrl = validated.resolvedUrl,
                    apiKey = resolveApiKey(config),
                    model = config.model.ifBlank { "gpt-4o-mini" },
                    systemContent = buildLegacySystemContent(config.systemPrompt, context),
                    turns = emptyList(),
                    userMessage = message,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    requireApiKey = true
                ).text
            }
            Provider.GEMINI -> callGemini(message, config, context)
            Provider.OLLAMA -> {
                val base = config.baseUrl.trim()
                if (base.isEmpty()) return null // loopback / unconfigured Ollama not available
                val validated = validateOllamaEndpoint(base)
                if (validated !is UrlValidationResult.Valid) return null
                callOllamaEndpoint(
                    endpointUrl = validated.resolvedUrl,
                    apiKey = resolveApiKey(config),
                    model = config.model.ifBlank { AiSettingsState.DEFAULT_QWEN_MODEL },
                    systemContent = buildLegacySystemContent(config.systemPrompt, context),
                    turns = emptyList(),
                    userMessage = message,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens
                ).text
            }
        }
    }

    suspend fun generateReplyWithFallback(
        message: String,
        primaryConfig: AIConfig,
        fallbackConfig: AIConfig? = null,
        context: String = ""
    ): String? {
        val primaryReply = generateReply(message, primaryConfig, context)
        if (!primaryReply.isNullOrBlank()) return primaryReply

        val secondary = fallbackConfig ?: resolveAutomaticFallback(primaryConfig) ?: return null
        return generateReply(message, secondary, context)?.takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------------------
    // Smart Collaboration API (New API primary + Ollama/Qwen fallback + JSON)
    // -------------------------------------------------------------------------

    suspend fun runCollaborationTurn(
        settings: AiSettingsState,
        userMessage: String,
        systemInstruction: String,
        recentTurns: List<ChatTurn> = emptyList()
    ): CollaborationAiOutcome {
        val primaryLabel = resolvePrimaryEngineLabel(settings)
        val startPrimaryNs = System.nanoTime()

        val primaryResult = executeEngineTurn(
            settings = settings,
            useFallbackEngine = false,
            systemInstruction = systemInstruction,
            recentTurns = recentTurns,
            userMessage = userMessage
        )
        val primaryLatencyMs = ((System.nanoTime() - startPrimaryNs) / 1_000_000L).coerceAtLeast(1L)

        if (!primaryResult.text.isNullOrBlank()) {
            val decision = parseStructuredDecision(primaryResult.text)
            if (decision != null && (decision.reply.isNotBlank() || decision.needsStoreData || decision.accountAction.isNotBlank())) {
                return CollaborationAiOutcome(
                    decision = decision,
                    primaryEngineLabel = primaryLabel,
                    activeEngineLabel = primaryLabel,
                    switchedToFallback = false,
                    primaryLatencyMs = primaryLatencyMs
                )
            }
        }

        val primaryError = primaryResult.error ?: "فشل الحصول على استجابة من المحرك الرئيسي"
        if (!settings.fallbackEnabled) {
            return CollaborationAiOutcome(
                decision = null,
                primaryEngineLabel = primaryLabel,
                activeEngineLabel = primaryLabel,
                switchedToFallback = false,
                primaryLatencyMs = primaryLatencyMs,
                redactedError = redactError(primaryError)
            )
        }

        val fallbackLabel = resolveFallbackEngineLabel(settings)
        val startFallbackNs = System.nanoTime()
        val fallbackResult = executeEngineTurn(
            settings = settings,
            useFallbackEngine = true,
            systemInstruction = systemInstruction,
            recentTurns = recentTurns,
            userMessage = userMessage
        )
        val fallbackLatencyMs = ((System.nanoTime() - startFallbackNs) / 1_000_000L).coerceAtLeast(1L)

        if (!fallbackResult.text.isNullOrBlank()) {
            val decision = parseStructuredDecision(fallbackResult.text)
            if (decision != null && (decision.reply.isNotBlank() || decision.needsStoreData || decision.accountAction.isNotBlank())) {
                return CollaborationAiOutcome(
                    decision = decision,
                    primaryEngineLabel = primaryLabel,
                    activeEngineLabel = fallbackLabel,
                    switchedToFallback = true,
                    primaryLatencyMs = primaryLatencyMs,
                    fallbackLatencyMs = fallbackLatencyMs,
                    redactedError = redactError(primaryError)
                )
            }
        }

        val combinedError = buildString {
            append(primaryError)
            if (!fallbackResult.error.isNullOrBlank()) {
                append(" | الاحتياطي: ")
                append(fallbackResult.error)
            }
        }
        return CollaborationAiOutcome(
            decision = null,
            primaryEngineLabel = primaryLabel,
            activeEngineLabel = fallbackLabel,
            switchedToFallback = true,
            primaryLatencyMs = primaryLatencyMs,
            fallbackLatencyMs = fallbackLatencyMs,
            redactedError = redactError(combinedError)
        )
    }

    suspend fun testPrimaryConnection(settings: AiSettingsState): ConnectionTestOutcome {
        val model = settings.effectivePrimaryModel()
        val label = resolvePrimaryEngineLabel(settings)
        val result = executeEngineTurn(
            settings = settings,
            useFallbackEngine = false,
            systemInstruction = "أنت مساعد اختبار اتصال مختصر. أجب بجملة عربية قصيرة.",
            recentTurns = emptyList(),
            userMessage = "اختبار اتصال قصير"
        )
        return ConnectionTestOutcome(
            success = !result.text.isNullOrBlank(),
            engineLabel = label,
            model = model,
            statusCode = result.statusCode,
            latencyMs = result.latencyMs,
            replyPreview = result.text.orEmpty(),
            redactedError = result.error?.let(::redactError)
        )
    }

    suspend fun testFallbackConnection(settings: AiSettingsState): ConnectionTestOutcome {
        val model = settings.effectiveOllamaModel()
        val label = resolveFallbackEngineLabel(settings)
        val result = executeEngineTurn(
            settings = settings,
            useFallbackEngine = true,
            systemInstruction = "أنت مساعد اختبار اتصال مختصر. أجب بجملة عربية قصيرة.",
            recentTurns = emptyList(),
            userMessage = "اختبار اتصال قصير"
        )
        return ConnectionTestOutcome(
            success = !result.text.isNullOrBlank(),
            engineLabel = label,
            model = model,
            statusCode = result.statusCode,
            latencyMs = result.latencyMs,
            replyPreview = result.text.orEmpty(),
            redactedError = result.error?.let(::redactError)
        )
    }

    private data class RawTurnCallResult(
        val text: String? = null,
        val statusCode: Int = -1,
        val latencyMs: Long = 0L,
        val error: String? = null
    )

    private suspend fun executeEngineTurn(
        settings: AiSettingsState,
        useFallbackEngine: Boolean,
        systemInstruction: String,
        recentTurns: List<ChatTurn>,
        userMessage: String
    ): RawTurnCallResult {
        if (!useFallbackEngine) {
            return when {
                settings.provider == Provider.NEW_API || settings.primaryBaseUrl.isNotBlank() -> {
                    val baseUrl = settings.primaryBaseUrl.trim()
                    val urlCheck = validateExternalUrl(baseUrl, "/v1/chat/completions")
                    if (urlCheck !is UrlValidationResult.Valid) {
                        return RawTurnCallResult(
                            error = (urlCheck as UrlValidationResult.Invalid).errorAr
                        )
                    }
                    val apiKey = resolvePrimaryNewApiKey()
                    if (apiKey.isBlank()) {
                        return RawTurnCallResult(error = "مفتاح New API غير مهيأ في SecretVault")
                    }
                    callOpenAiCompatible(
                        endpointUrl = urlCheck.resolvedUrl,
                        apiKey = apiKey,
                        model = settings.effectivePrimaryModel(),
                        systemContent = systemInstruction,
                        turns = recentTurns,
                        userMessage = userMessage,
                        temperature = settings.temperature,
                        maxTokens = settings.maxTokens,
                        requireApiKey = true
                    )
                }
                settings.provider == Provider.OPENAI -> {
                    val apiKey = vault.get(AiSettingsPrefs.apiKeyRefFor(Provider.OPENAI)).trim()
                    if (apiKey.isBlank()) {
                        return RawTurnCallResult(error = "مفتاح OpenAI غير مهيأ في SecretVault")
                    }
                    callOpenAiCompatible(
                        endpointUrl = OPENAI_URL,
                        apiKey = apiKey,
                        model = settings.effectivePrimaryModel(),
                        systemContent = systemInstruction,
                        turns = recentTurns,
                        userMessage = userMessage,
                        temperature = settings.temperature,
                        maxTokens = settings.maxTokens,
                        requireApiKey = true
                    )
                }
                settings.provider == Provider.GEMINI -> {
                    val apiKey = vault.get(AiSettingsPrefs.apiKeyRefFor(Provider.GEMINI)).trim()
                    if (apiKey.isBlank()) {
                        return RawTurnCallResult(error = "مفتاح Gemini غير مهيأ في SecretVault")
                    }
                    val contextSummary = recentTurns.joinToString("\n") { "${it.role}: ${it.text}" }
                    val reply = callGemini(
                        msg = userMessage,
                        config = AIConfig(
                            provider = Provider.GEMINI,
                            apiKey = apiKey,
                            model = settings.effectivePrimaryModel(),
                            systemPrompt = systemInstruction,
                            temperature = settings.temperature,
                            maxTokens = settings.maxTokens
                        ),
                        ctx = contextSummary
                    )
                    if (reply.isNullOrBlank()) {
                        RawTurnCallResult(error = "فشل استدعاء Gemini")
                    } else {
                        RawTurnCallResult(text = reply, statusCode = 200)
                    }
                }
                else -> {
                    // Explicit OLLAMA selected as primary -> uses ollamaBaseUrl
                    executeOllamaCall(settings, systemInstruction, recentTurns, userMessage)
                }
            }
        } else {
            // Fallback engine: either Qwen via New API gateway OR direct HTTPS Ollama URL
            if (settings.fallbackUseNewApiGateway) {
                val baseUrl = settings.primaryBaseUrl.trim()
                val urlCheck = validateExternalUrl(baseUrl, "/v1/chat/completions")
                if (urlCheck !is UrlValidationResult.Valid) {
                    return RawTurnCallResult(error = (urlCheck as UrlValidationResult.Invalid).errorAr)
                }
                val apiKey = resolvePrimaryNewApiKey()
                if (apiKey.isBlank()) {
                    return RawTurnCallResult(error = "مفتاح بوابة New API غير متوفر للاحتياطي")
                }
                return callOpenAiCompatible(
                    endpointUrl = urlCheck.resolvedUrl,
                    apiKey = apiKey,
                    model = settings.effectiveOllamaModel(),
                    systemContent = systemInstruction,
                    turns = recentTurns,
                    userMessage = userMessage,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    requireApiKey = true
                )
            }
            return executeOllamaCall(settings, systemInstruction, recentTurns, userMessage)
        }
    }

    private suspend fun executeOllamaCall(
        settings: AiSettingsState,
        systemInstruction: String,
        recentTurns: List<ChatTurn>,
        userMessage: String
    ): RawTurnCallResult {
        val rawUrl = settings.ollamaBaseUrl.trim()
        val urlCheck = validateOllamaEndpoint(rawUrl)
        if (urlCheck !is UrlValidationResult.Valid) {
            // Secondary automatic fallback to Gemini if legacy OpenAI failed and Ollama URL wasn't set
            val gemKey = vault.get(AiSettingsPrefs.apiKeyRefFor(Provider.GEMINI)).trim()
            if (settings.primaryBaseUrl.isBlank() && settings.provider == Provider.OPENAI && gemKey.isNotBlank()) {
                val reply = callGemini(
                    msg = userMessage,
                    config = AIConfig(
                        provider = Provider.GEMINI,
                        apiKey = gemKey,
                        model = Provider.GEMINI.defaultModels.first(),
                        systemPrompt = systemInstruction,
                        temperature = settings.temperature,
                        maxTokens = settings.maxTokens
                    ),
                    ctx = recentTurns.joinToString("\n") { "${it.role}: ${it.text}" }
                )
                if (!reply.isNullOrBlank()) {
                    return RawTurnCallResult(text = reply, statusCode = 200)
                }
            }
            return RawTurnCallResult(error = (urlCheck as UrlValidationResult.Invalid).errorAr)
        }

        val apiKey = vault.get(AiSettingsPrefs.apiKeyRefFor(Provider.OLLAMA)).trim()
            .ifBlank { resolvePrimaryNewApiKey() }
        return callOllamaEndpoint(
            endpointUrl = urlCheck.resolvedUrl,
            apiKey = apiKey,
            model = settings.effectiveOllamaModel(),
            systemContent = systemInstruction,
            turns = recentTurns,
            userMessage = userMessage,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens
        )
    }

    private fun resolvePrimaryNewApiKey(): String {
        return vault.get(AiSettingsPrefs.KEY_REF_NEW_API).trim()
            .ifBlank { vault.get(AiSettingsPrefs.apiKeyRefFor(Provider.OPENAI)).trim() }
    }

    private fun resolvePrimaryEngineLabel(settings: AiSettingsState): String {
        return when {
            settings.provider == Provider.NEW_API || settings.primaryBaseUrl.isNotBlank() ->
                "New API (${settings.effectivePrimaryModel()})"
            else -> "${settings.provider.labelAr} (${settings.effectivePrimaryModel()})"
        }
    }

    private fun resolveFallbackEngineLabel(settings: AiSettingsState): String {
        val qwenModel = settings.effectiveOllamaModel()
        return if (settings.fallbackUseNewApiGateway) {
            "Qwen via New API ($qwenModel)"
        } else {
            "Ollama ($qwenModel)"
        }
    }

    private fun resolveAutomaticFallback(primaryConfig: AIConfig): AIConfig? {
        val altProvider = when (primaryConfig.provider) {
            Provider.OPENAI, Provider.NEW_API -> Provider.GEMINI
            Provider.GEMINI -> Provider.OPENAI
            Provider.OLLAMA -> Provider.OPENAI
        }
        val altKey = vault.get(AiSettingsPrefs.apiKeyRefFor(altProvider))
        if (altKey.isBlank()) {
            if (primaryConfig.provider == Provider.OLLAMA) {
                val geminiKey = vault.get(AiSettingsPrefs.apiKeyRefFor(Provider.GEMINI))
                if (geminiKey.isNotBlank()) {
                    return AIConfig(
                        provider = Provider.GEMINI,
                        apiKey = geminiKey,
                        model = Provider.GEMINI.defaultModels.first(),
                        systemPrompt = primaryConfig.systemPrompt,
                        temperature = primaryConfig.temperature,
                        maxTokens = primaryConfig.maxTokens
                    )
                }
            }
            return null
        }
        return AIConfig(
            provider = altProvider,
            apiKey = altKey,
            model = altProvider.defaultModels.first(),
            systemPrompt = primaryConfig.systemPrompt,
            temperature = primaryConfig.temperature,
            maxTokens = primaryConfig.maxTokens
        )
    }

    private fun resolveApiKey(config: AIConfig): String {
        val direct = config.apiKey.trim()
        if (direct.isNotEmpty()) {
            val fromVault = vault.get(direct)
            return if (fromVault.isNotEmpty()) fromVault else direct
        }
        return vault.get(AiSettingsPrefs.apiKeyRefFor(config.provider)).trim()
    }

    private fun buildLegacySystemContent(systemPrompt: String, ctx: String): String {
        return buildString {
            if (systemPrompt.isNotBlank()) append(systemPrompt.trim())
            if (ctx.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("سياق المحادثة:\n")
                append(ctx.trim())
            }
        }
    }

    private suspend fun callOpenAiCompatible(
        endpointUrl: String,
        apiKey: String,
        model: String,
        systemContent: String,
        turns: List<ChatTurn>,
        userMessage: String,
        temperature: Float,
        maxTokens: Int,
        requireApiKey: Boolean
    ): RawTurnCallResult {
        if (requireApiKey && apiKey.isBlank()) {
            return RawTurnCallResult(error = "مفتاح API مطلوب")
        }

        val messages = buildJsonArray {
            if (systemContent.isNotBlank()) {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(systemContent))
                })
            }
            turns.forEach { turn ->
                val normalizedRole = when (turn.role.lowercase()) {
                    "assistant" -> "assistant"
                    "system" -> "system"
                    else -> "user"
                }
                if (turn.text.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", JsonPrimitive(normalizedRole))
                        put("content", JsonPrimitive(turn.text))
                    })
                }
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", messages)
            put("temperature", JsonPrimitive(temperature.coerceIn(0f, 1f)))
            put("max_tokens", JsonPrimitive(maxTokens.coerceIn(50, 1200)))
        }.toString()

        val headers = if (apiKey.isNotBlank()) {
            mapOf("Authorization" to "Bearer $apiKey")
        } else {
            emptyMap()
        }

        return when (
            val result = transport.execute(
                url = endpointUrl,
                method = "POST",
                headers = headers,
                body = payload,
                contentType = "JSON",
                maxAttempts = 1,
                timeoutSeconds = HttpTransport.DEFAULT_TIMEOUT_SECONDS
            )
        ) {
            is HttpTransport.Result.Success -> {
                val parsed = parseOpenAiResponse(result.body)
                if (parsed.isNullOrBlank()) {
                    RawTurnCallResult(
                        statusCode = result.code,
                        latencyMs = result.ms,
                        error = "استجابة فارغة أو غير صالحة من الخادم"
                    )
                } else {
                    RawTurnCallResult(text = parsed, statusCode = result.code, latencyMs = result.ms)
                }
            }
            is HttpTransport.Result.Conflict -> RawTurnCallResult(
                statusCode = result.code,
                latencyMs = result.ms,
                error = "تعارض من الخادم (HTTP 409)"
            )
            is HttpTransport.Result.Failure -> RawTurnCallResult(
                statusCode = result.code,
                latencyMs = result.ms,
                error = redactError(result.error)
            )
        }
    }

    private suspend fun callOllamaEndpoint(
        endpointUrl: String,
        apiKey: String,
        model: String,
        systemContent: String,
        turns: List<ChatTurn>,
        userMessage: String,
        temperature: Float,
        maxTokens: Int
    ): RawTurnCallResult {
        // OpenAI-compatible gateway URL if it ends with /chat/completions
        if (endpointUrl.endsWith("/chat/completions", ignoreCase = true)) {
            return callOpenAiCompatible(
                endpointUrl = endpointUrl,
                apiKey = apiKey,
                model = model,
                systemContent = systemContent,
                turns = turns,
                userMessage = userMessage,
                temperature = temperature,
                maxTokens = maxTokens,
                requireApiKey = false
            )
        }

        val messages = buildJsonArray {
            if (systemContent.isNotBlank()) {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(systemContent))
                })
            }
            turns.forEach { turn ->
                if (turn.text.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", JsonPrimitive(if (turn.role == "assistant") "assistant" else "user"))
                        put("content", JsonPrimitive(turn.text))
                    })
                }
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("stream", JsonPrimitive(false))
            put("messages", messages)
            put("options", buildJsonObject {
                put("temperature", JsonPrimitive(temperature.coerceIn(0f, 1f)))
                put("num_predict", JsonPrimitive(maxTokens.coerceIn(50, 1200)))
            })
        }.toString()

        val headers = if (apiKey.isNotBlank()) {
            mapOf("Authorization" to "Bearer $apiKey")
        } else {
            emptyMap()
        }

        return when (
            val result = transport.execute(
                url = endpointUrl,
                method = "POST",
                headers = headers,
                body = payload,
                contentType = "JSON",
                maxAttempts = 1,
                timeoutSeconds = HttpTransport.DEFAULT_TIMEOUT_SECONDS
            )
        ) {
            is HttpTransport.Result.Success -> {
                val parsed = parseOllamaResponse(result.body) ?: parseOpenAiResponse(result.body)
                if (parsed.isNullOrBlank()) {
                    RawTurnCallResult(
                        statusCode = result.code,
                        latencyMs = result.ms,
                        error = "استجابة Ollama فارغة"
                    )
                } else {
                    RawTurnCallResult(text = parsed, statusCode = result.code, latencyMs = result.ms)
                }
            }
            is HttpTransport.Result.Conflict -> RawTurnCallResult(
                statusCode = result.code,
                latencyMs = result.ms,
                error = "تعارض من الخادم (HTTP 409)"
            )
            is HttpTransport.Result.Failure -> RawTurnCallResult(
                statusCode = result.code,
                latencyMs = result.ms,
                error = redactError(result.error)
            )
        }
    }

    private suspend fun callGemini(msg: String, config: AIConfig, ctx: String): String? {
        val apiKey = resolveApiKey(config)
        if (apiKey.isBlank()) return null

        val model = config.model.ifBlank { "gemini-1.5-pro" }
        val combinedPrompt = buildString {
            if (config.systemPrompt.isNotBlank()) {
                append(config.systemPrompt.trim())
                append("\n\n")
            }
            if (ctx.isNotBlank()) {
                append("السياق: ")
                append(ctx.trim())
                append("\n\n")
            }
            append(msg)
        }

        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive(combinedPrompt))
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", JsonPrimitive(config.temperature.coerceIn(0f, 1f)))
                put("maxOutputTokens", JsonPrimitive(config.maxTokens.coerceIn(50, 1200)))
            })
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val result = transport.execute(
            url = url,
            method = "POST",
            body = payload,
            contentType = "JSON",
            maxAttempts = 1,
            timeoutSeconds = HttpTransport.DEFAULT_TIMEOUT_SECONDS
        )

        if (result !is HttpTransport.Result.Success) return null
        return parseGeminiResponse(result.body)
    }

    fun parseStructuredDecision(rawText: String): AiStructuredDecision? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        val candidateJson = extractJsonObjectText(trimmed)
        if (candidateJson != null) {
            val parsedObj = runCatching {
                parserJson.parseToJsonElement(candidateJson).jsonObject
            }.getOrNull()

            if (parsedObj != null && (
                    parsedObj.containsKey("reply") ||
                        parsedObj.containsKey("needs_store_data") ||
                        parsedObj.containsKey("intent")
                    )
            ) {
                val memObj = (parsedObj["memory_update"] as? JsonObject)
                val memProposal = memObj?.let { m ->
                    AiMemoryProposal(
                        summary = m["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                        preferences = parseStringArray(m["preferences"]),
                        interests = parseStringArray(m["interests"]),
                        constraints = parseStringArray(m["constraints"]),
                        consentConfirmed = m["consent_confirmed"]?.jsonPrimitive?.booleanOrNull == true
                    )
                }?.takeIf {
                    it.summary.isNotBlank() ||
                        it.preferences.isNotEmpty() ||
                        it.interests.isNotEmpty() ||
                        it.constraints.isNotEmpty()
                }

                return AiStructuredDecision(
                    reply = parsedObj["reply"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    intent = parsedObj["intent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        .ifBlank { "general" },
                    confidence = parsedObj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.8,
                    needsStoreData = parsedObj["needs_store_data"]?.jsonPrimitive?.booleanOrNull == true,
                    storeQuery = parsedObj["store_query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    storeIntent = parsedObj["store_intent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        .ifBlank { "general" },
                    needsHuman = parsedObj["needs_human"]?.jsonPrimitive?.booleanOrNull == true,
                    accountAction = parsedObj["account_action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    memoryUpdate = memProposal,
                    isStructuredJson = true
                )
            }
        }

        // Plain-text fallback when the model answers cleanly without JSON wrapper
        return AiStructuredDecision(
            reply = trimmed,
            intent = "general",
            confidence = 0.75,
            needsStoreData = false,
            isStructuredJson = false
        )
    }

    private fun extractJsonObjectText(text: String): String? {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1)
        }
        return null
    }

    private fun parseStringArray(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun parseOpenAiResponse(body: String): String? {
        return runCatching {
            val root = parserJson.parseToJsonElement(body).jsonObject
            val choices = root["choices"]?.jsonArray ?: return null
            val firstChoice = choices.firstOrNull()?.jsonObject ?: return null
            val message = firstChoice["message"]?.jsonObject ?: return null
            message["content"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun parseOllamaResponse(body: String): String? {
        return runCatching {
            val root = parserJson.parseToJsonElement(body).jsonObject
            val msgContent = root["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            val respContent = root["response"]?.jsonPrimitive?.contentOrNull
            (msgContent ?: respContent)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun parseGeminiResponse(body: String): String? {
        return runCatching {
            val root = parserJson.parseToJsonElement(body).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return null
            val firstCandidate = candidates.firstOrNull()?.jsonObject ?: return null
            val content = firstCandidate["content"]?.jsonObject ?: return null
            val parts = content["parts"]?.jsonArray ?: return null
            val firstPart = parts.firstOrNull()?.jsonObject ?: return null
            firstPart["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun resolveOpenAiEndpoint(rawBaseUrl: String, fallbackToDefaultOpenAi: Boolean): String {
        val cleaned = rawBaseUrl.trim()
        if (cleaned.isEmpty()) {
            return if (fallbackToDefaultOpenAi) OPENAI_URL else ""
        }
        return when (val validated = validateExternalUrl(cleaned, "/v1/chat/completions")) {
            is UrlValidationResult.Valid -> validated.resolvedUrl
            is UrlValidationResult.Invalid -> ""
        }
    }

    private fun redactError(error: String): String =
        SensitiveDataRedactor.scrub(error, vault)

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"

        fun validateExternalUrl(rawUrl: String, defaultPathSuffix: String): UrlValidationResult {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                return UrlValidationResult.Invalid("الرابط غير مهيأ")
            }
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: return UrlValidationResult.Invalid("صيغة الرابط غير صالحة")
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme != "https") {
                return UrlValidationResult.Invalid("يتطلب التطبيق رابط HTTPS آمن (تم رفض HTTP غير المشفر)")
            }
            if (host.isBlank()) {
                return UrlValidationResult.Invalid("اسم النطاق في الرابط غير صالح")
            }
            if (isLoopbackHost(host)) {
                return UrlValidationResult.Invalid(
                    "عنوان localhost غير قابل للوصول من الهاتف؛ أدخل رابط HTTPS خارجي أو استخدم بوابة New API."
                )
            }
            val path = uri.path.orEmpty().trimEnd('/')
            val resolved = when {
                path.endsWith("/chat/completions") -> trimmed
                path.endsWith("/v1") -> "$trimmed/chat/completions"
                else -> "$trimmed$defaultPathSuffix"
            }
            return UrlValidationResult.Valid(resolved)
        }

        fun validateOllamaEndpoint(rawUrl: String): UrlValidationResult {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                return UrlValidationResult.Invalid("رابط Ollama غير مهيأ")
            }
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: return UrlValidationResult.Invalid("صيغة رابط Ollama غير صالحة")
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme != "https") {
                return UrlValidationResult.Invalid("يجب أن يكون رابط Ollama عبر HTTPS (سياسة الشبكة تمنع HTTP)")
            }
            if (host.isBlank()) {
                return UrlValidationResult.Invalid("اسم النطاق في رابط Ollama غير صالح")
            }
            if (isLoopbackHost(host)) {
                return UrlValidationResult.Invalid(
                    "عنوان localhost/127.0.0.1 يشير للهاتف نفسه وليس للخادم؛ استخدم نفق HTTPS أو بوابة New API."
                )
            }
            val path = uri.path.orEmpty().trimEnd('/')
            val resolved = when {
                path.endsWith("/api/chat") ||
                    path.endsWith("/api/generate") ||
                    path.endsWith("/chat/completions") -> trimmed
                path.endsWith("/v1") -> "$trimmed/chat/completions"
                else -> "$trimmed/api/chat"
            }
            return UrlValidationResult.Valid(resolved)
        }

        private fun isLoopbackHost(host: String): Boolean {
            return host == "localhost" ||
                host == "127.0.0.1" ||
                host == "0.0.0.0" ||
                host == "::1" ||
                host == "10.0.2.2"
        }
    }
}
