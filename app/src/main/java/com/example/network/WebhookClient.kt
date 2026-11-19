package com.example.network

import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.ServerConfigEntity
import com.example.engine.IncomingMessage
import com.example.engine.VariableEngine
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class WebhookClient(
    private val transport: HttpTransport,
    private val vault: SecretVault
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
        val redactedError: String? = null
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
        val headers = buildHeaders(config)
        val method = config.method.trim().uppercase().ifEmpty { "POST" }
        val body = if (method == "GET" || method == "HEAD") {
            null
        } else {
            buildBody(config, msg)
        }

        return transport.execute(
            url = config.url,
            method = method,
            headers = headers,
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

    suspend fun invokeAsAiTool(
        config: ServerConfigEntity,
        msg: IncomingMessage,
        aiQuery: String,
        idempotencyKey: String? = null,
        reason: String = ""
    ): WebhookToolResult {
        val cleanUrl = config.url.trim()
        if (cleanUrl.isBlank()) {
            return WebhookToolResult(
                ok = false,
                statusCode = -1,
                redactedError = "رابط خادم Webhook غير مهيأ"
            )
        }

        val headers = buildHeaders(config)
        val method = config.method.trim().uppercase().ifEmpty { "POST" }
        val body = if (method == "GET" || method == "HEAD") {
            null
        } else {
            buildAiToolBody(config, msg, aiQuery, reason)
        }

        val result = transport.execute(
            url = cleanUrl,
            method = method,
            headers = headers,
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

        return when (result) {
            is HttpTransport.Result.Success -> {
                val extractedRaw = VariableEngine.extractReply(result.body, config.responseReplyKey)
                    ?.trim()
                    .orEmpty()
                val scrubbedBody = sanitizeExternalText(result.body)
                val scrubbedExtracted = sanitizeExternalText(extractedRaw)
                val chosen = scrubbedExtracted.ifBlank { scrubbedBody }
                val truncated = chosen.length > maxChars || scrubbedBody.length > maxChars
                WebhookToolResult(
                    ok = chosen.isNotBlank(),
                    statusCode = result.code,
                    extractedText = scrubbedExtracted.take(maxChars),
                    rawExcerpt = scrubbedBody.take(maxChars),
                    latencyMs = result.ms,
                    truncated = truncated,
                    redactedError = if (chosen.isBlank()) "استجابة Webhook فارغة" else null
                )
            }
            is HttpTransport.Result.Conflict -> WebhookToolResult(
                ok = false,
                statusCode = result.code,
                rawExcerpt = sanitizeExternalText(result.body).take(maxChars),
                latencyMs = result.ms,
                redactedError = "تعارض مؤقت من الخادم الخارجي (HTTP 409)"
            )
            is HttpTransport.Result.Failure -> WebhookToolResult(
                ok = false,
                statusCode = result.code,
                latencyMs = result.ms,
                redactedError = sanitizeExternalText(result.error).ifBlank { "تعذر الاتصال بخادم Webhook" }
            )
        }
    }

    private fun sanitizeExternalText(raw: String): String {
        if (raw.isBlank()) return ""
        return vault.redact(SensitiveDataRedactor.scrub(raw, vault)).trim()
    }

    companion object {
        const val MIN_TOOL_CHARS = 200
        const val MAX_TOOL_CHARS = 4000
        const val SOURCE_EXTERNAL_WEBHOOK = "external_webhook"
        const val TRUST_UNVERIFIED_EXTERNAL = "UNVERIFIED_EXTERNAL"
    }
}
