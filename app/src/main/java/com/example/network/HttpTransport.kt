package com.example.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

open class HttpTransport(
    private val client: OkHttpClient = defaultClient(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    enum class FailureKind {
        FAILED_CONFIRMED,
        DELIVERY_UNKNOWN,
        RATE_LIMITED,
        NON_RETRYABLE
    }

    sealed class Result {
        data class Success(
            val body: String,
            val code: Int,
            val ms: Long,
            val headers: Map<String, String> = emptyMap(),
            val idempotencySupported: Boolean = false,
            val idempotencyReplayed: Boolean = false
        ) : Result()

        data class Conflict(
            val body: String = "",
            val code: Int = 409,
            val ms: Long = 0L,
            val retryAfterMs: Long? = null,
            val headers: Map<String, String> = emptyMap(),
            val idempotencySupported: Boolean = false,
            val idempotencyReplayed: Boolean = false
        ) : Result()

        data class Failure(
            val error: String,
            val code: Int = -1,
            val ms: Long = 0L,
            val cause: Throwable? = null,
            val kind: FailureKind = FailureKind.FAILED_CONFIRMED,
            val retryable: Boolean = false,
            val retryAfterMs: Long? = null,
            val headers: Map<String, String> = emptyMap(),
            val idempotencySupported: Boolean = false,
            val idempotencyReplayed: Boolean = false
        ) : Result()
    }

    private val hostIdempotencySupport = ConcurrentHashMap<String, Boolean>()

    fun isHostIdempotencySupported(host: String): Boolean =
        hostIdempotencySupport[host.trim().lowercase()] == true

    fun markHostIdempotencySupported(host: String, supported: Boolean) {
        val clean = host.trim().lowercase()
        if (clean.isNotEmpty()) {
            hostIdempotencySupport[clean] = supported
        }
    }

    open suspend fun execute(
        url: String,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String = "JSON",
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        idempotencyKey: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty()) {
            return@withContext Result.Failure(
                error = "URL is blank",
                code = -1,
                ms = 0L,
                kind = FailureKind.NON_RETRYABLE,
                retryable = false
            )
        }

        val normalizedMethod = method.trim().uppercase().ifEmpty { "POST" }
        val requestedMaxAttempts = maxAttempts.coerceIn(1, MAX_ALLOWED_ATTEMPTS)
        val effectiveTimeoutSeconds = if (timeoutSeconds > 0) {
            timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        } else {
            DEFAULT_TIMEOUT_SECONDS
        }

        val finalHeaders = LinkedHashMap(headers)
        val trimmedIdemKey = idempotencyKey?.trim().orEmpty()
        if (trimmedIdemKey.isNotEmpty() && !finalHeaders.keys.any { it.equals(HEADER_IDEMPOTENCY_KEY, ignoreCase = true) }) {
            finalHeaders[HEADER_IDEMPOTENCY_KEY] = trimmedIdemKey
        }

        val hasSensitiveAuthHeader = finalHeaders.keys.any { key ->
            key.equals("X-SA-API-Key", ignoreCase = true) ||
                key.equals("Authorization", ignoreCase = true)
        }

        val requestClient = client.newBuilder()
            .callTimeout(effectiveTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .apply {
                if (hasSensitiveAuthHeader) {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
            .build()

        val requestBody = buildRequestBody(normalizedMethod, body, contentType)
        val request = try {
            Request.Builder()
                .url(normalizedUrl)
                .headers(finalHeaders.toHeaders())
                .method(normalizedMethod, requestBody)
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.Failure(
                error = e.message ?: "Invalid URL or headers",
                code = -1,
                ms = 0L,
                cause = e,
                kind = FailureKind.NON_RETRYABLE,
                retryable = false
            )
        }

        val host = request.url.host.lowercase()
        val startNs = System.nanoTime()
        var lastFailure = Result.Failure(
            error = "Request failed",
            code = -1,
            ms = 0L,
            kind = FailureKind.FAILED_CONFIRMED,
            retryable = false
        )

        for (attemptIndex in 0 until requestedMaxAttempts) {
            try {
                requestClient.newCall(request).execute().use { response ->
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    val responseBody = response.body?.string().orEmpty()
                    val responseHeaders = linkedMapOf<String, String>()
                    for (i in 0 until response.headers.size) {
                        responseHeaders[response.headers.name(i)] = response.headers.value(i)
                    }

                    val headerSupported = parseTruthyHeader(response.header(HEADER_IDEMPOTENCY_SUPPORTED))
                    val headerReplayed = parseTruthyHeader(response.header(HEADER_IDEMPOTENCY_REPLAYED))
                    if (headerSupported) {
                        hostIdempotencySupport[host] = true
                    }
                    val hostSupportsIdempotency = headerSupported || (hostIdempotencySupport[host] == true)

                    if (response.isSuccessful) {
                        return@withContext Result.Success(
                            body = responseBody,
                            code = response.code,
                            ms = elapsedMs,
                            headers = responseHeaders,
                            idempotencySupported = hostSupportsIdempotency,
                            idempotencyReplayed = headerReplayed
                        )
                    }

                    if (hasSensitiveAuthHeader && response.code in 300..399) {
                        val locationHeader = response.header("Location").orEmpty()
                        val targetRedirect = locationHeader.takeIf { it.isNotBlank() }?.let { request.url.resolve(it) }
                        val redirectDetail = when {
                            targetRedirect == null -> "رفض إعادة التوجيه لحماية مفتاح المصادقة."
                            !targetRedirect.isHttps -> "تم حظر إعادة التوجيه إلى اتصال غير مشفر (HTTP) لحماية مفتاح المصادقة."
                            !targetRedirect.host.equals(host, ignoreCase = true) ->
                                "تم حظر إعادة التوجيه إلى نطاق آخر (${targetRedirect.host}) لمنع تسريب مفتاح المصادقة."
                            else -> "أعاد الخادم إعادة توجيه (${response.code}) لمسار يتطلب مصادقة."
                        }
                        return@withContext Result.Failure(
                            error = redirectDetail,
                            code = response.code,
                            ms = elapsedMs,
                            kind = FailureKind.NON_RETRYABLE,
                            retryable = false,
                            headers = responseHeaders,
                            idempotencySupported = hostSupportsIdempotency,
                            idempotencyReplayed = headerReplayed
                        )
                    }

                    val retryAfterMs = parseRetryAfterMs(response.header(HEADER_RETRY_AFTER))
                    if (response.code == 409) {
                        return@withContext Result.Conflict(
                            body = responseBody,
                            code = 409,
                            ms = elapsedMs,
                            retryAfterMs = retryAfterMs,
                            headers = responseHeaders,
                            idempotencySupported = hostSupportsIdempotency,
                            idempotencyReplayed = headerReplayed
                        )
                    }

                    val errorMessage = responseBody.ifBlank {
                        response.message.ifBlank { "HTTP ${response.code}" }
                    }

                    if (response.code == 429) {
                        return@withContext Result.Failure(
                            error = errorMessage,
                            code = 429,
                            ms = elapsedMs,
                            kind = FailureKind.RATE_LIMITED,
                            retryable = false,
                            retryAfterMs = retryAfterMs ?: DEFAULT_RATE_LIMIT_RETRY_AFTER_MS,
                            headers = responseHeaders,
                            idempotencySupported = hostSupportsIdempotency,
                            idempotencyReplayed = headerReplayed
                        )
                    }

                    if (response.code == 408) {
                        return@withContext Result.Failure(
                            error = errorMessage,
                            code = 408,
                            ms = elapsedMs,
                            kind = FailureKind.DELIVERY_UNKNOWN,
                            retryable = false,
                            headers = responseHeaders,
                            idempotencySupported = hostSupportsIdempotency,
                            idempotencyReplayed = headerReplayed
                        )
                    }

                    if (response.code in 400..499) {
                        return@withContext Result.Failure(
                            error = errorMessage,
                            code = response.code,
                            ms = elapsedMs,
                            kind = FailureKind.NON_RETRYABLE,
                            retryable = false,
                            headers = responseHeaders,
                            idempotencySupported = hostSupportsIdempotency,
                            idempotencyReplayed = headerReplayed
                        )
                    }

                    // 5xx or other server error
                    val canRetryServer = response.code >= 500 && hostSupportsIdempotency
                    lastFailure = Result.Failure(
                        error = errorMessage,
                        code = response.code,
                        ms = elapsedMs,
                        kind = FailureKind.FAILED_CONFIRMED,
                        retryable = response.code >= 500,
                        retryAfterMs = retryAfterMs,
                        headers = responseHeaders,
                        idempotencySupported = hostSupportsIdempotency,
                        idempotencyReplayed = headerReplayed
                    )
                    if (!canRetryServer || attemptIndex == requestedMaxAttempts - 1) {
                        return@withContext lastFailure
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val preConnectFailure = isPreConnectFailure(e)
                val hostKnownIdempotent = hostIdempotencySupport[host] == true

                if (!preConnectFailure) {
                    return@withContext Result.Failure(
                        error = e.message ?: "Uncertain network delivery failure",
                        code = -1,
                        ms = elapsedMs,
                        cause = e,
                        kind = FailureKind.DELIVERY_UNKNOWN,
                        retryable = false,
                        idempotencySupported = hostKnownIdempotent
                    )
                }

                lastFailure = Result.Failure(
                    error = e.message ?: "Connection failed before send",
                    code = -1,
                    ms = elapsedMs,
                    cause = e,
                    kind = FailureKind.FAILED_CONFIRMED,
                    retryable = true,
                    idempotencySupported = hostKnownIdempotent
                )
                if (attemptIndex == requestedMaxAttempts - 1) {
                    return@withContext lastFailure
                }
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                return@withContext Result.Failure(
                    error = e.message ?: "Unexpected HTTP error",
                    code = -1,
                    ms = elapsedMs,
                    cause = e,
                    kind = FailureKind.NON_RETRYABLE,
                    retryable = false
                )
            }

            val backoffMs = BACKOFF_DELAYS_MS.getOrElse(attemptIndex) { BACKOFF_DELAYS_MS.last() }
            sleeper(backoffMs)
        }

        lastFailure
    }

    private fun isPreConnectFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is SocketTimeoutException, is InterruptedIOException -> return false
                is UnknownHostException, is ConnectException, is NoRouteToHostException -> return true
            }
            current = current.cause
        }
        return false
    }

    private fun parseTruthyHeader(raw: String?): Boolean {
        val cleaned = raw?.trim()?.lowercase() ?: return false
        return cleaned == "1" || cleaned == "true" || cleaned == "yes"
    }

    private fun parseRetryAfterMs(raw: String?): Long? {
        val seconds = raw?.trim()?.toLongOrNull() ?: return null
        return seconds.coerceAtLeast(1L) * 1_000L
    }

    private fun buildRequestBody(
        method: String,
        body: String?,
        contentType: String
    ): RequestBody? {
        if (method == "GET" || method == "HEAD") return null
        val mediaType = when (contentType.trim().uppercase()) {
            "FORM" -> "application/x-www-form-urlencoded; charset=utf-8"
            "TEXT" -> "text/plain; charset=utf-8"
            else -> "application/json; charset=utf-8"
        }.toMediaType()
        return (body ?: "").toRequestBody(mediaType)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 25
        const val MIN_TIMEOUT_SECONDS = 10
        const val MAX_TIMEOUT_SECONDS = 60
        const val DEFAULT_MAX_ATTEMPTS = 2
        const val MAX_ALLOWED_ATTEMPTS = 5
        const val DEFAULT_RATE_LIMIT_RETRY_AFTER_MS = 60_000L

        const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
        const val HEADER_IDEMPOTENCY_SUPPORTED = "Idempotency-Supported"
        const val HEADER_IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
        const val HEADER_RETRY_AFTER = "Retry-After"

        val BACKOFF_DELAYS_MS = longArrayOf(2_000L, 5_000L)

        fun totalBackoffMs(maxAttempts: Int): Long {
            val safeAttempts = maxAttempts.coerceIn(1, MAX_ALLOWED_ATTEMPTS)
            var sum = 0L
            for (attemptIndex in 0 until (safeAttempts - 1)) {
                sum += BACKOFF_DELAYS_MS.getOrElse(attemptIndex) { BACKOFF_DELAYS_MS.last() }
            }
            return sum
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
