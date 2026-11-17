package com.example.network

import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.ServerConfigEntity
import com.example.engine.IncomingMessage
import com.example.engine.VariableEngine
import com.example.network.security.SecretVault
import kotlinx.serialization.json.Json

class WebhookClient(
    private val transport: HttpTransport,
    private val vault: SecretVault
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
}
