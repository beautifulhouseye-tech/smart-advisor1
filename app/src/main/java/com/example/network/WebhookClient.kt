package com.example.network

import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.IncomingMessage
import com.example.engine.VariableEngine
import com.example.network.ResponseOutcomeAnalyzer.AnalyzedOutcome
import com.example.network.ResponseOutcomeAnalyzer.ResponseStatus
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorAuthResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

class WebhookClient(
    private val transport: HttpTransport,
    private val vault: SecretVault,
    private val smartAdvisorPrefs: SmartAdvisorPrefs? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class WebhookToolResult(
        val ok: Boolean,
        val statusCode: Int = -1,
        val extractedText: String = "",
        val rawExcerpt: String = "",
        val sourceLabel: String = SOURCE_EXTERNAL_WEBHOOK,
        val trustLevel: String = TRUST_UNVERIFIED_EXTERNAL,
        val latencyMs: Long = 0L,
        val truncated: Boolean = false,
        val redactedError: String? = null,
        val outcomeStatus: ResponseStatus = if (ok) ResponseStatus.SUCCESS else ResponseStatus.FAILURE,
        val title: String = "",
        val serverMessage: String = "",
        val errorCode: String? = null,
        val reasonSummary: String = "",
        val guidance: String = ""
    )

    fun buildHeaders(config: ServerConfigEntity): Map<String, String> {
        val items = if (config.headersJson.isBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<HeaderItem>>(config.headersJson)
            } catch (_: Exception) {
                emptyList()
            }
        }
        return vault.resolveHeaders(items)
    }

    fun inspectOutboundAuth(config: ServerConfigEntity): SmartAdvisorAuthResolver.OutboundAuthDecision {
        val baseHeaders = buildHeaders(config)
        return SmartAdvisorAuthResolver.inspectOutboundAuth(
            targetUrl = config.url,
            configuredHeaders = baseHeaders,
            vault = vault,
            prefs = smartAdvisorPrefs,
            requireSmartAdvisorKeyWhenTargetMatches = true
        )
    }

    fun buildBody(config: ServerConfigEntity, msg: IncomingMessage): String {
        return VariableEngine.buildRequestBody(config, msg)
    }

    fun buildAiToolBody(
        config: ServerConfigEntity,
        msg: IncomingMessage,
        aiQuery: String,
        reason: String = ""
    ): String {
        val cleanQuery = aiQuery.trim().ifBlank { msg.message }
        val enrichedMsg = msg.copy(aiQuery = cleanQuery)
        val baseBody = buildBody(config, enrichedMsg)
        if (!config.contentType.equals("JSON", ignoreCase = true)) {
            return baseBody
        }
        return runCatching {
            val parsed = json.parseToJsonElement(baseBody) as? JsonObject ?: return baseBody
            buildJsonObject {
                parsed.forEach { (k, v) -> put(k, v) }
                if (!parsed.containsKey("ai_query")) {
                    put("ai_query", JsonPrimitive(cleanQuery))
                }
                if (!parsed.containsKey("tool_mode")) {
                    put("tool_mode", JsonPrimitive("ai_tool"))
                }
                if (reason.isNotBlank() && !parsed.containsKey("tool_reason")) {
                    put("tool_reason", JsonPrimitive(reason.trim().take(160)))
                }
            }.toString()
        }.getOrElse { baseBody }
    }

    suspend fun send(
        config: ServerConfigEntity,
        msg: IncomingMessage,
        idempotencyKey: String? = null
    ): HttpTransport.Result {
        val authDecision = inspectOutboundAuth(config)
        if (authDecision is SmartAdvisorAuthResolver.OutboundAuthDecision.Blocked) {
            return HttpTransport.Result.Failure(
                error = authDecision.reasonAr,
                code = if (authDecision.errorCode == "sa_missing_key_client") 401 else -1,
                ms = 0L,
                kind = HttpTransport.FailureKind.NON_RETRYABLE,
                retryable = false
            )
        }
        val proceed = authDecision as SmartAdvisorAuthResolver.OutboundAuthDecision.Proceed
        val method = config.method.trim().uppercase().ifEmpty { "POST" }
        val body = if (method == "GET" || method == "HEAD") {
            null
        } else {
            buildBody(config, msg)
        }

        return transport.execute(
            url = config.url,
            method = method,
            headers = proceed.headers,
            body = body,
            contentType = config.contentType,
            maxAttempts = config.retryCount.coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS),
            timeoutSeconds = config.timeoutSeconds.coerceIn(
                HttpTransport.MIN_TIMEOUT_SECONDS,
                HttpTransport.MAX_TIMEOUT_SECONDS
            ),
            idempotencyKey = idempotencyKey
        )
    }

    suspend fun testWebhook(
        config: ServerConfigEntity,
        msg: IncomingMessage,
        idempotencyKey: String? = null
    ): AnalyzedOutcome {
        val authDecision = inspectOutboundAuth(config)
        val isSaTarget = SmartAdvisorAuthResolver.isSmartAdvisorUrl(config.url)
        val sourceLabel = if (isSaTarget) SOURCE_SMART_ADVISOR_ENDPOINT else SOURCE_EXTERNAL_WEBHOOK
        val trustLabel = if (isSaTarget) "Smart Advisor عبر HTTPS" else "خارجي غير متحقق منه"

        if (authDecision is SmartAdvisorAuthResolver.OutboundAuthDecision.Blocked) {
            return ResponseOutcomeAnalyzer.fromBlockedAuth(
                errorCode = authDecision.errorCode,
                reasonAr = authDecision.reasonAr,
                guidanceAr = authDecision.guidanceAr,
                sourceLabel = sourceLabel,
                trustLabel = trustLabel
            )
        }

        val proceed = authDecision as SmartAdvisorAuthResolver.OutboundAuthDecision.Proceed
        val method = config.method.trim().uppercase().ifEmpty { "POST" }
        val body = if (method == "GET" || method == "HEAD") {
            null
        } else {
            buildBody(config, msg)
        }

        val result = transport.execute(
            url = config.url,
            method = method,
            headers = proceed.headers,
            body = body,
            contentType = config.contentType,
            maxAttempts = config.retryCount.coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS),
            timeoutSeconds = config.timeoutSeconds.coerceIn(
                HttpTransport.MIN_TIMEOUT_SECONDS,
                HttpTransport.MAX_TIMEOUT_SECONDS
            ),
            idempotencyKey = idempotencyKey
        )

        return ResponseOutcomeAnalyzer.analyze(
            result = result,
            vault = vault,
            replyKey = config.responseReplyKey,
            expectJson = config.contentType.equals("JSON", ignoreCase = true),
            maxExcerptChars = config.toolMaxResponseChars.coerceIn(MIN_TOOL_CHARS, MAX_TOOL_CHARS),
            isToolPreview = false,
            sourceLabel = sourceLabel,
            trustLabel = trustLabel,
            extraSecretsToRedact = extractHeaderSecrets(proceed.headers, config.headersJson)
        )
    }

    suspend fun invokeAsAiTool(
        config: ServerConfigEntity,
        msg: IncomingMessage,
        aiQuery: String,
        idempotencyKey: String? = null,
        reason: String = ""
    ): WebhookToolResult {
        val cleanUrl = config.url.trim()
        val isSaTarget = SmartAdvisorAuthResolver.isSmartAdvisorUrl(cleanUrl)
        val sourceLabel = if (isSaTarget) SOURCE_SMART_ADVISOR_ENDPOINT else SOURCE_EXTERNAL_WEBHOOK
        val trustLevel = if (isSaTarget) TRUST_SMART_ADVISOR_AUTH else TRUST_UNVERIFIED_EXTERNAL

        if (cleanUrl.isBlank()) {
            return WebhookToolResult(
                ok = false,
                statusCode = -1,
                sourceLabel = sourceLabel,
                trustLevel = trustLevel,
                redactedError = "رابط خادم Webhook غير مهيأ",
                outcomeStatus = ResponseStatus.FAILURE,
                title = "رابط الخادم غير مهيأ",
                serverMessage = "رابط خادم Webhook غير مهيأ.",
                errorCode = "missing_url",
                reasonSummary = "لم يتم إدخال عنوان HTTPS للخادم.",
                guidance = "أدخل رابط الخادم الصحيح ثم أعد الاختبار."
            )
        }

        val authDecision = inspectOutboundAuth(config)
        if (authDecision is SmartAdvisorAuthResolver.OutboundAuthDecision.Blocked) {
            return WebhookToolResult(
                ok = false,
                statusCode = -1,
                sourceLabel = sourceLabel,
                trustLevel = trustLevel,
                redactedError = authDecision.reasonAr,
                outcomeStatus = ResponseStatus.FAILURE,
                title = when (authDecision.errorCode) {
                    "sa_missing_key_client" -> "مفتاح API غير مضبوط في التطبيق"
                    "insecure_transport" -> "يتطلب الاتصال HTTPS"
                    else -> "تعذر إرسال الطلب"
                },
                serverMessage = authDecision.reasonAr,
                errorCode = authDecision.errorCode,
                reasonSummary = authDecision.reasonAr,
                guidance = authDecision.guidanceAr
            )
        }

        val proceed = authDecision as SmartAdvisorAuthResolver.OutboundAuthDecision.Proceed
        val method = config.method.trim().uppercase().ifEmpty { "POST" }
        val body = if (method == "GET" || method == "HEAD") {
            null
        } else {
            buildAiToolBody(config, msg, aiQuery, reason)
        }

        val result = transport.execute(
            url = cleanUrl,
            method = method,
            headers = proceed.headers,
            body = body,
            contentType = config.contentType,
            maxAttempts = config.retryCount.coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS),
            timeoutSeconds = config.timeoutSeconds.coerceIn(
                HttpTransport.MIN_TIMEOUT_SECONDS,
                HttpTransport.MAX_TIMEOUT_SECONDS
            ),
            idempotencyKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        )

        val maxChars = config.toolMaxResponseChars.coerceIn(MIN_TOOL_CHARS, MAX_TOOL_CHARS)
        val analyzed = ResponseOutcomeAnalyzer.analyze(
            result = result,
            vault = vault,
            replyKey = config.responseReplyKey,
            expectJson = config.contentType.equals("JSON", ignoreCase = true),
            maxExcerptChars = maxChars,
            isToolPreview = true,
            sourceLabel = sourceLabel,
            trustLabel = if (isSaTarget) "Smart Advisor عبر HTTPS" else "خارجي غير متحقق منه",
            extraSecretsToRedact = extractHeaderSecrets(proceed.headers, config.headersJson)
        )

        return WebhookToolResult(
            ok = analyzed.isSuccess,
            statusCode = analyzed.statusCode,
            extractedText = analyzed.extractedText.take(maxChars),
            rawExcerpt = analyzed.rawExcerpt.take(maxChars),
            sourceLabel = sourceLabel,
            trustLevel = trustLevel,
            latencyMs = analyzed.latencyMs,
            truncated = analyzed.truncated,
            redactedError = if (analyzed.isSuccess) null else analyzed.serverMessage.ifBlank { analyzed.reasonSummary },
            outcomeStatus = analyzed.status,
            title = analyzed.title,
            serverMessage = analyzed.serverMessage,
            errorCode = analyzed.errorCode,
            reasonSummary = analyzed.reasonSummary,
            guidance = analyzed.guidance
        )
    }

    private fun extractHeaderSecrets(headers: Map<String, String>, headersJson: String = ""): List<String> {
        val secrets = mutableListOf<String>()
        fun addIfSensitive(key: String, value: String, explicitSecret: Boolean = false) {
            val keyLower = key.trim().lowercase()
            val v = value.trim()
            if (v.length >= 4 && (
                    explicitSecret ||
                        keyLower == "authorization" ||
                        keyLower == "x-sa-api-key" ||
                        keyLower.contains("key") ||
                        keyLower.contains("token") ||
                        keyLower.contains("secret") ||
                        keyLower.contains("auth") ||
                        keyLower.contains("pass")
                    )
            ) {
                secrets += v
                if (v.startsWith("Bearer ", ignoreCase = true) || v.startsWith("Basic ", ignoreCase = true)) {
                    val rawToken = v.substringAfter(' ').trim()
                    if (rawToken.length >= 4) secrets += rawToken
                }
            }
        }
        headers.forEach { (key, value) -> addIfSensitive(key, value) }
        if (headersJson.isNotBlank()) {
            runCatching {
                val arr = json.parseToJsonElement(headersJson) as? JsonArray ?: return@runCatching
                arr.forEach { el ->
                    val obj = el as? JsonObject ?: return@forEach
                    val k = (obj["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val v = (obj["valueRef"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["value"] as? JsonPrimitive)?.contentOrNull
                        ?: ""
                    val isSec = (obj["isSecret"] as? JsonPrimitive)?.booleanOrNull == true
                    addIfSensitive(k, v, isSec)
                }
            }
        }
        return secrets
    }

    companion object {
        const val MIN_TOOL_CHARS = 200
        const val MAX_TOOL_CHARS = 4000
        const val DEFAULT_EXCERPT_CHARS = 1200
        const val SOURCE_EXTERNAL_WEBHOOK = "external_webhook"
        const val SOURCE_SMART_ADVISOR_ENDPOINT = "smart_advisor_webhook"
        const val TRUST_UNVERIFIED_EXTERNAL = "UNVERIFIED_EXTERNAL"
        const val TRUST_SMART_ADVISOR_AUTH = "SMART_ADVISOR_HTTPS"
    }
}
