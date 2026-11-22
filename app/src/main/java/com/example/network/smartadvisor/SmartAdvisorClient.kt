package com.example.network.smartadvisor

import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.DiagnosticAnomaly
import com.example.engine.DiagnosticAuthState
import com.example.engine.DiagnosticCategory
import com.example.engine.DiagnosticNetworkRecorder
import com.example.engine.DiagnosticOutcome
import com.example.engine.DiagnosticSanitizer
import com.example.engine.DiagnosticStage
import com.example.engine.DiagnosticTraceContext
import com.example.engine.IncomingMessage
import com.example.engine.SafeDiagnosticLogger
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartAdvisorClient internal constructor(
    private val transport: HttpTransport,
    private val vault: SecretVault,
    private val baseUrlProvider: () -> String,
    private val apiKeyRefProvider: () -> String,
    private val timeoutSecondsProvider: () -> Int = { HttpTransport.DEFAULT_TIMEOUT_SECONDS },
    private val maxAttemptsProvider: () -> Int = { HttpTransport.DEFAULT_MAX_ATTEMPTS },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val diagnosticLogger: SafeDiagnosticLogger? = null
) {
    @Inject
    constructor(
        transport: HttpTransport,
        prefs: SmartAdvisorPrefs,
        vault: SecretVault,
        diagnosticLogger: SafeDiagnosticLogger
    ) : this(
        transport = transport,
        vault = vault,
        baseUrlProvider = { prefs.baseUrl },
        apiKeyRefProvider = { prefs.apiKeyRef },
        timeoutSecondsProvider = { prefs.timeoutSeconds },
        maxAttemptsProvider = { prefs.maxAttempts },
        clockMs = { System.currentTimeMillis() },
        diagnosticLogger = diagnosticLogger
    )

    constructor(
        transport: HttpTransport,
        prefs: SmartAdvisorPrefs,
        vault: SecretVault
    ) : this(
        transport = transport,
        vault = vault,
        baseUrlProvider = { prefs.baseUrl },
        apiKeyRefProvider = { prefs.apiKeyRef },
        timeoutSecondsProvider = { prefs.timeoutSeconds },
        maxAttemptsProvider = { prefs.maxAttempts },
        clockMs = { System.currentTimeMillis() },
        diagnosticLogger = null
    )

    data class SaCapabilities(
        val knowledgeEnabled: Boolean = true,
        val memoryEnabled: Boolean = false,
        val accountActionsEnabled: Boolean = false,
        val consentRequired: Boolean = true,
        val storeName: String = "",
        val fetchedAtMs: Long = 0L
    )

    data class SaKnowledgeResponse(
        val ok: Boolean,
        val hasVerifiedData: Boolean,
        val intent: String,
        val summary: String = "",
        val facts: List<String> = emptyList(),
        val itemsJson: String = "[]",
        val statusCode: Int = 200,
        val skillDisabled: Boolean = false,
        val error: String? = null,
        val storeName: String = "",
        val categoryUrl: String? = null,
        val suggestedReply: String = ""
    ) {
        fun toParsedPayload(selectedProductId: Long? = null): SmartAdvisorResponseParser.ParsedPayload =
            SmartAdvisorResponseParser.parseKnowledgeItems(
                itemsJson = itemsJson,
                storeName = storeName,
                intent = intent,
                summary = suggestedReply.ifBlank { summary },
                categoryUrl = categoryUrl,
                selectedProductId = selectedProductId
            )
    }

    data class SaMemoryPayload(
        val summary: String = "",
        val preferences: List<String> = emptyList(),
        val interests: List<String> = emptyList(),
        val constraints: List<String> = emptyList(),
        val confirmed: Boolean = false
    )

    data class SaMemoryResponse(
        val ok: Boolean,
        val consentRequired: Boolean = false,
        val skillDisabled: Boolean = false,
        val conflict: Boolean = false,
        val revision: Long = 0L,
        val memory: SaMemoryPayload? = null,
        val statusCode: Int = 200,
        val error: String? = null
    )

    data class SaAccountActionResponse(
        val ok: Boolean,
        val status: String = "",
        val message: String = "",
        val accountCreated: Boolean = false,
        val linkCreated: Boolean = false,
        val ephemeralLoginUrl: String = "",
        val skillDisabled: Boolean = false,
        val statusCode: Int = 200,
        val error: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val capabilitiesCache = ConcurrentHashMap<String, SaCapabilities>()

    // -------------------------------------------------------------------------
    // Legacy endpoint (Kept strictly compatible for SmartAdvisorClientTest & standalone toggle)
    // -------------------------------------------------------------------------

    fun endpoint(): String {
        val base = baseUrlProvider().trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.contains("/wp-json")) {
            base
        } else {
            "$base/wp-json/smart-advisor/v1/whatsapp/reply"
        }
    }

    fun assistantBaseUrl(): String {
        val base = baseUrlProvider().trim().trimEnd('/')
        if (base.isEmpty()) return ""
        val uri = runCatching { URI(base) }.getOrNull() ?: return ""
        if (!uri.scheme.equals("https", ignoreCase = true)) return ""
        val wpIndex = base.indexOf("/wp-json")
        val siteOrigin = if (wpIndex >= 0) base.substring(0, wpIndex) else base
        return "${siteOrigin.trimEnd('/')}/wp-json/smart-advisor/v1/whatsapp/assistant"
    }

    fun buildHeaders(): Map<String, String> {
        val ref = apiKeyRefProvider().trim()
        if (ref.isEmpty()) return emptyMap()
        val apiKey = vault.get(ref)
        if (apiKey.isEmpty()) return emptyMap()
        return mapOf(HEADER_API_KEY to apiKey)
    }

    fun buildBody(
        msg: IncomingMessage,
        excludeProductIds: List<Long> = emptyList()
    ): String {
        return buildJsonObject {
            put("app", JsonPrimitive(msg.app))
            put("sender", JsonPrimitive(msg.sender))
            put("message", JsonPrimitive(msg.message))
            put("group_name", JsonPrimitive(msg.groupName))
            put("phone", JsonPrimitive(msg.phone))
            if (excludeProductIds.isNotEmpty()) {
                put(
                    "active_context",
                    buildJsonObject {
                        put(
                            "shown_product_ids",
                            buildJsonArray {
                                excludeProductIds.distinct().forEach { add(JsonPrimitive(it)) }
                            }
                        )
                    }
                )
            }
        }.toString()
    }

    suspend fun send(
        msg: IncomingMessage,
        idempotencyKey: String? = null,
        excludeProductIds: List<Long> = emptyList(),
        traceId: String = DiagnosticTraceContext.newTraceId()
    ): HttpTransport.Result {
        val url = endpoint()
        if (url.isBlank()) {
            DiagnosticNetworkRecorder.recordPreSendBlocked(
                logger = diagnosticLogger,
                traceId = traceId,
                operationType = "sa_whatsapp_reply",
                method = "POST",
                rawUrl = url,
                errorCode = "missing_url",
                reasonAr = "رابط Smart Advisor غير مهيأ"
            )
            return unconfiguredFailure()
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            DiagnosticNetworkRecorder.recordPreSendBlocked(
                logger = diagnosticLogger,
                traceId = traceId,
                operationType = "sa_whatsapp_reply",
                method = "POST",
                rawUrl = url,
                errorCode = "insecure_transport",
                reasonAr = "يتطلب Smart Advisor استخدام رابط HTTPS مشفر لحماية مفتاح API."
            )
            return HttpTransport.Result.Failure(
                error = "يتطلب Smart Advisor استخدام رابط HTTPS مشفر لحماية مفتاح API.",
                code = -1,
                ms = 0L,
                kind = HttpTransport.FailureKind.NON_RETRYABLE,
                retryable = false
            )
        }
        val headers = prepareHeadersWithDiagnostics(
            traceId = traceId,
            operationType = "sa_whatsapp_reply",
            method = "POST",
            url = url
        )
        val effectiveKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: msg.notificationId.trim().takeIf {
                it.isNotEmpty() && it != "smart-advisor-test" && !it.startsWith("queue-retry-")
            }
        val result = transport.execute(
            url = url,
            method = "POST",
            headers = headers,
            body = buildBody(msg, excludeProductIds),
            contentType = "JSON",
            maxAttempts = effectiveAttempts(),
            timeoutSeconds = effectiveTimeoutSeconds(),
            idempotencyKey = effectiveKey
        )
        DiagnosticNetworkRecorder.recordHttpResult(
            logger = diagnosticLogger,
            traceId = traceId,
            operationType = "sa_whatsapp_reply",
            method = "POST",
            rawUrl = url,
            result = result,
            defaultStage = DiagnosticStage.STAGE_09_ENGINE_RESPONSE_RECEIVED,
            extraSecretsToRedact = currentApiSecrets(),
            expectReplyPayload = true
        )
        return result
    }

    suspend fun test(traceId: String = DiagnosticTraceContext.newTraceId()): HttpTransport.Result {
        val testMsg = IncomingMessage(
            app = "com.whatsapp",
            sender = "AutoMate-Test",
            senderName = "AutoMate-Test",
            message = "مرحبا",
            phone = "",
            groupName = "",
            isGroup = false,
            notificationId = "smart-advisor-test"
        )
        return send(testMsg, traceId = traceId)
    }

    // -------------------------------------------------------------------------
    // Smart Advisor 6.5.0 Assistant Endpoints
    // -------------------------------------------------------------------------

    suspend fun fetchCapabilities(
        forceRefresh: Boolean = false,
        traceId: String = DiagnosticTraceContext.newTraceId()
    ): SaCapabilities {
        val base = assistantBaseUrl()
        if (base.isBlank()) return SaCapabilities()

        val now = clockMs()
        if (!forceRefresh) {
            val cached = capabilitiesCache[base]
            if (cached != null && (now - cached.fetchedAtMs) < CAPABILITIES_CACHE_TTL_MS) {
                return cached
            }
        }

        val url = "$base/capabilities"
        val res = transport.execute(
            url = url,
            method = "GET",
            headers = prepareHeadersWithDiagnostics(traceId, "sa_capabilities", "GET", url),
            body = null,
            contentType = "JSON",
            maxAttempts = 1,
            timeoutSeconds = effectiveTimeoutSeconds()
        )
        DiagnosticNetworkRecorder.recordHttpResult(
            logger = diagnosticLogger,
            traceId = traceId,
            operationType = "sa_capabilities",
            method = "GET",
            rawUrl = url,
            result = res,
            defaultStage = DiagnosticStage.STAGE_03_AUTH_VERIFIED,
            extraSecretsToRedact = currentApiSecrets()
        )
        if (res is HttpTransport.Result.Success) {
            val parsed = runCatching {
                val obj = json.parseToJsonElement(res.body).jsonObject
                val capsObj = (obj["capabilities"] as? JsonObject) ?: obj
                SaCapabilities(
                    knowledgeEnabled = capsObj["knowledge"]?.jsonPrimitive?.booleanOrNull
                        ?: capsObj["knowledge_enabled"]?.jsonPrimitive?.booleanOrNull
                        ?: true,
                    memoryEnabled = capsObj["memory"]?.jsonPrimitive?.booleanOrNull
                        ?: capsObj["memory_enabled"]?.jsonPrimitive?.booleanOrNull
                        ?: false,
                    accountActionsEnabled = capsObj["account_actions"]?.jsonPrimitive?.booleanOrNull
                        ?: capsObj["account_actions_enabled"]?.jsonPrimitive?.booleanOrNull
                        ?: false,
                    consentRequired = capsObj["consent_required"]?.jsonPrimitive?.booleanOrNull ?: true,
                    storeName = obj["store_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    fetchedAtMs = now
                )
            }.getOrNull()
            if (parsed != null) {
                capabilitiesCache[base] = parsed
                return parsed
            }
        }
        return capabilitiesCache[base] ?: SaCapabilities()
    }

    suspend fun queryKnowledge(
        query: String,
        intent: String,
        customerKey: String = "",
        isGroup: Boolean = false,
        excludeProductIds: List<Long> = emptyList(),
        traceId: String = DiagnosticTraceContext.newTraceId()
    ): SaKnowledgeResponse {
        val base = assistantBaseUrl()
        if (base.isBlank()) {
            DiagnosticNetworkRecorder.recordPreSendBlocked(
                logger = diagnosticLogger,
                traceId = traceId,
                operationType = "sa_knowledge",
                method = "POST",
                rawUrl = "",
                errorCode = "missing_url",
                reasonAr = "رابط Smart Advisor الآمن (HTTPS) غير مهيأ"
            )
            return SaKnowledgeResponse(
                ok = false,
                hasVerifiedData = false,
                intent = normalizeKnowledgeIntent(intent),
                statusCode = -1,
                error = "رابط Smart Advisor الآمن (HTTPS) غير مهيأ"
            )
        }

        val normalizedIntent = normalizeKnowledgeIntent(intent)
        val payload = buildJsonObject {
            put("query", JsonPrimitive(query.trim()))
            put("intent", JsonPrimitive(normalizedIntent))
            if (customerKey.isNotBlank()) put("customer_key", JsonPrimitive(customerKey))
            put("is_group", JsonPrimitive(isGroup))
            if (excludeProductIds.isNotEmpty()) {
                put(
                    "exclude_ids",
                    buildJsonArray {
                        excludeProductIds.distinct().forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
        }.toString()

        val url = "$base/knowledge"
        val result = transport.execute(
            url = url,
            method = "POST",
            headers = prepareHeadersWithDiagnostics(traceId, "sa_knowledge", "POST", url),
            body = payload,
            contentType = "JSON",
            maxAttempts = 1,
            timeoutSeconds = effectiveTimeoutSeconds()
        )
        DiagnosticNetworkRecorder.recordHttpResult(
            logger = diagnosticLogger,
            traceId = traceId,
            operationType = "sa_knowledge",
            method = "POST",
            rawUrl = url,
            result = result,
            defaultStage = DiagnosticStage.STAGE_09_ENGINE_RESPONSE_RECEIVED,
            extraSecretsToRedact = currentApiSecrets()
        )

        return when (result) {
            is HttpTransport.Result.Success -> parseKnowledgeSuccess(result.body, result.code, normalizedIntent)
            is HttpTransport.Result.Conflict -> SaKnowledgeResponse(
                ok = false,
                hasVerifiedData = false,
                intent = normalizedIntent,
                statusCode = result.code,
                error = "تعارض من الخادم"
            )
            is HttpTransport.Result.Failure -> SaKnowledgeResponse(
                ok = false,
                hasVerifiedData = false,
                intent = normalizedIntent,
                statusCode = result.code,
                skillDisabled = result.code == 403,
                error = SensitiveDataRedactor.scrub(result.error, vault)
            )
        }
    }

    suspend fun getCustomerMemory(
        customerKey: String,
        phone: String = "",
        traceId: String = DiagnosticTraceContext.newTraceId()
    ): SaMemoryResponse {
        val base = assistantBaseUrl()
        if (base.isBlank() || customerKey.isBlank()) {
            return SaMemoryResponse(ok = false, statusCode = -1, error = "بيانات غير مكتملة")
        }

        val payload = buildJsonObject {
            put("action", JsonPrimitive("get"))
            put("customer_key", JsonPrimitive(customerKey))
            if (phone.isNotBlank()) put("phone", JsonPrimitive(phone))
        }.toString()

        val url = "$base/memory"
        val result = transport.execute(
            url = url,
            method = "POST",
            headers = prepareHeadersWithDiagnostics(traceId, "sa_memory_get", "POST", url),
            body = payload,
            contentType = "JSON",
            maxAttempts = 1,
            timeoutSeconds = effectiveTimeoutSeconds()
        )
        DiagnosticNetworkRecorder.recordHttpResult(
            logger = diagnosticLogger,
            traceId = traceId,
            operationType = "sa_memory_get",
            method = "POST",
            rawUrl = url,
            result = result,
            defaultStage = DiagnosticStage.STAGE_05_CONTEXT_MEMORY_LOADED,
            extraSecretsToRedact = currentApiSecrets()
        )

        val response = when (result) {
            is HttpTransport.Result.Success -> parseMemoryResponse(result.body, result.code)
            is HttpTransport.Result.Conflict -> parseMemoryConflict(result.body, result.code)
            is HttpTransport.Result.Failure -> SaMemoryResponse(
                ok = false,
                skillDisabled = result.code == 403,
                statusCode = result.code,
                error = SensitiveDataRedactor.scrub(result.error, vault)
            )
        }
        if (!response.ok && !response.consentRequired && !response.skillDisabled) {
            diagnosticLogger?.logTrace(
                traceId = traceId,
                operationType = "sa_memory_get",
                stage = DiagnosticStage.STAGE_05_CONTEXT_MEMORY_LOADED,
                outcome = DiagnosticOutcome.FAILURE,
                category = DiagnosticCategory.MEMORY_CONTEXT,
                httpMethod = "POST",
                rawUrl = url,
                httpStatusCode = response.statusCode,
                errorCode = "memory_retrieval_failed",
                description = response.error ?: "فشل استرجاع ذاكرة العميل من الخادم",
                anomalies = listOf(DiagnosticAnomaly.MEMORY_RETRIEVAL_FAILED),
                extraSecretsToRedact = currentApiSecrets()
            )
        }
        return response
    }

    suspend fun saveCustomerMemory(
        customerKey: String,
        eventId: String,
        revision: Long,
        memory: SaMemoryPayload,
        consentConfirmed: Boolean,
        phone: String = "",
        traceId: String = DiagnosticTraceContext.newTraceId()
    ): SaMemoryResponse {
        val base = assistantBaseUrl()
        if (base.isBlank() || customerKey.isBlank()) {
            return SaMemoryResponse(ok = false, statusCode = -1, error = "بيانات غير مكتملة")
        }
        if (!consentConfirmed) {
            return SaMemoryResponse(
                ok = false,
                consentRequired = true,
                statusCode = 400,
                error = "موافقة العميل مطلوبة قبل حفظ الذاكرة الدائمة"
            )
        }

        val payload = buildJsonObject {
            put("action", JsonPrimitive("save"))
            put("customer_key", JsonPrimitive(customerKey))
            if (phone.isNotBlank()) put("phone", JsonPrimitive(phone))
            put("event_id", JsonPrimitive(eventId))
            put("revision", JsonPrimitive(revision))
            put("consent_confirmed", JsonPrimitive(true))
            put("memory", buildJsonObject {
                put("summary", JsonPrimitive(SensitiveDataRedactor.scrub(memory.summary, vault)))
                put("preferences", buildJsonArray {
                    memory.preferences.forEach { add(JsonPrimitive(SensitiveDataRedactor.scrub(it, vault))) }
                })
                put("interests", buildJsonArray {
                    memory.interests.forEach { add(JsonPrimitive(SensitiveDataRedactor.scrub(it, vault))) }
                })
                put("constraints", buildJsonArray {
                    memory.constraints.forEach { add(JsonPrimitive(SensitiveDataRedactor.scrub(it, vault))) }
                })
                put("confirmed", JsonPrimitive(true))
            })
        }.toString()

        val url = "$base/memory"
        val result = transport.execute(
            url = url,
            method = "POST",
            headers = prepareHeadersWithDiagnostics(traceId, "sa_memory_save", "POST", url),
            body = payload,
            contentType = "JSON",
            maxAttempts = 1,
            timeoutSeconds = effectiveTimeoutSeconds(),
            idempotencyKey = eventId
        )
        DiagnosticNetworkRecorder.recordHttpResult(
            logger = diagnosticLogger,
            traceId = traceId,
            operationType = "sa_memory_save",
            method = "POST",
            rawUrl = url,
            result = result,
            defaultStage = DiagnosticStage.STAGE_05_CONTEXT_MEMORY_LOADED,
            extraSecretsToRedact = currentApiSecrets()
        )

        return when (result) {
            is HttpTransport.Result.Success -> parseMemoryResponse(result.body, result.code)
            is HttpTransport.Result.Conflict -> parseMemoryConflict(result.body, result.code)
            is HttpTransport.Result.Failure -> SaMemoryResponse(
                ok = false,
                skillDisabled = result.code == 403,
                statusCode = result.code,
                error = SensitiveDataRedactor.scrub(result.error, vault)
            )
        }
    }

    suspend fun performAccountAction(
        action: String,
        customerKey: String,
        phone: String = "",
        email: String = "",
        name: String = "",
        idempotencyKey: String? = null,
        traceId: String = DiagnosticTraceContext.newTraceId()
    ): SaAccountActionResponse {
        val base = assistantBaseUrl()
        if (base.isBlank()) {
            return SaAccountActionResponse(
                ok = false,
                statusCode = -1,
                error = "رابط Smart Advisor غير مهيأ"
            )
        }
        val cleanAction = action.trim().lowercase()
        if (cleanAction != "register" && cleanAction != "magic_login") {
            return SaAccountActionResponse(
                ok = false,
                statusCode = 400,
                error = "إجراء حساب غير مدعوم"
            )
        }

        val payload = buildJsonObject {
            put("action", JsonPrimitive(cleanAction))
            if (customerKey.isNotBlank()) put("customer_key", JsonPrimitive(customerKey))
            if (phone.isNotBlank()) put("phone", JsonPrimitive(phone))
            if (email.isNotBlank()) put("email", JsonPrimitive(email))
            if (name.isNotBlank()) put("name", JsonPrimitive(name))
        }.toString()

        val url = "$base/account-action"
        val result = transport.execute(
            url = url,
            method = "POST",
            headers = prepareHeadersWithDiagnostics(traceId, "sa_account_action", "POST", url),
            body = payload,
            contentType = "JSON",
            maxAttempts = 1,
            timeoutSeconds = effectiveTimeoutSeconds(),
            idempotencyKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        )
        DiagnosticNetworkRecorder.recordHttpResult(
            logger = diagnosticLogger,
            traceId = traceId,
            operationType = "sa_account_action",
            method = "POST",
            rawUrl = url,
            result = result,
            defaultStage = DiagnosticStage.STAGE_10_REPLY_COMPOSED,
            extraSecretsToRedact = currentApiSecrets()
        )

        return when (result) {
            is HttpTransport.Result.Success -> parseAccountActionSuccess(result.body, result.code)
            is HttpTransport.Result.Conflict -> SaAccountActionResponse(
                ok = false,
                statusCode = result.code,
                error = "تعارض أثناء تنفيذ إجراء الحساب"
            )
            is HttpTransport.Result.Failure -> SaAccountActionResponse(
                ok = false,
                skillDisabled = result.code == 403,
                statusCode = result.code,
                error = SensitiveDataRedactor.scrub(result.error, vault)
            )
        }
    }

    private fun prepareHeadersWithDiagnostics(
        traceId: String,
        operationType: String,
        method: String,
        url: String
    ): Map<String, String> {
        val authHeaders = buildHeaders()
        if (authHeaders.containsKey(HEADER_API_KEY)) {
            DiagnosticNetworkRecorder.recordHeaderAttached(
                logger = diagnosticLogger,
                traceId = traceId,
                operationType = operationType,
                method = method,
                rawUrl = url,
                headerName = HEADER_API_KEY
            )
        } else {
            DiagnosticNetworkRecorder.recordPreSendBlocked(
                logger = diagnosticLogger,
                traceId = traceId,
                operationType = operationType,
                method = method,
                rawUrl = url,
                errorCode = "sa_missing_key_client",
                reasonAr = "مفتاح X-SA-API-Key غير مضبوط محلياً عند تجهيز الطلب"
            )
        }
        return authHeaders + (DiagnosticSanitizer.HEADER_TRACE_ID to traceId)
    }

    private fun currentApiSecrets(): List<String> {
        val ref = apiKeyRefProvider().trim()
        if (ref.isEmpty()) return emptyList()
        val key = vault.get(ref).trim()
        return if (key.length >= 4) listOf(key) else emptyList()
    }

    fun clearCapabilitiesCache() {
        capabilitiesCache.clear()
    }

    private fun parseKnowledgeSuccess(body: String, code: Int, fallbackIntent: String): SaKnowledgeResponse {
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val dataObj = (obj["data"] as? JsonObject) ?: obj
            val itemsElement = dataObj["items"] ?: dataObj["products"] ?: obj["items"] ?: obj["products"]
            val itemsJson = (itemsElement as? JsonArray)?.toString() ?: "[]"
            val facts = ((dataObj["facts"] ?: obj["facts"]) as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                ?: emptyList()
            val summary = (dataObj["summary"] ?: obj["summary"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val explicitVerified = (dataObj["has_verified_data"] ?: obj["has_verified_data"])
                ?.jsonPrimitive?.booleanOrNull
            val hasItemsOrFacts = itemsJson != "[]" || facts.isNotEmpty() || summary.isNotBlank()
            val hasVerified = explicitVerified ?: hasItemsOrFacts
            val storeName = (obj["store_name"] ?: dataObj["store_name"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val categoryUrl = (obj["category_url"] ?: dataObj["category_url"])?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
            val suggestedReply = (obj["suggested_reply"] ?: dataObj["suggested_reply"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

            SaKnowledgeResponse(
                ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: true,
                hasVerifiedData = hasVerified,
                intent = (obj["intent"] ?: dataObj["intent"])?.jsonPrimitive?.contentOrNull ?: fallbackIntent,
                summary = summary,
                facts = facts,
                itemsJson = itemsJson,
                statusCode = code,
                storeName = storeName,
                categoryUrl = categoryUrl,
                suggestedReply = suggestedReply
            )
        }.getOrElse {
            SaKnowledgeResponse(
                ok = false,
                hasVerifiedData = false,
                intent = fallbackIntent,
                statusCode = code,
                error = "تعذر قراءة استجابة معرفة المتجر"
            )
        }
    }

    private fun parseMemoryResponse(body: String, code: Int): SaMemoryResponse {
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val memObj = (obj["memory"] as? JsonObject) ?: (obj["data"] as? JsonObject)?.get("memory") as? JsonObject
            val parsedMem = memObj?.let { m ->
                SaMemoryPayload(
                    summary = m["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    preferences = extractStringList(m["preferences"]),
                    interests = extractStringList(m["interests"]),
                    constraints = extractStringList(m["constraints"]),
                    confirmed = m["confirmed"]?.jsonPrimitive?.booleanOrNull == true
                )
            }
            val consentReq = obj["consent_required"]?.jsonPrimitive?.booleanOrNull == true
            SaMemoryResponse(
                ok = (obj["ok"]?.jsonPrimitive?.booleanOrNull ?: !consentReq) && !consentReq,
                consentRequired = consentReq,
                revision = obj["revision"]?.jsonPrimitive?.longOrNull ?: 0L,
                memory = parsedMem,
                statusCode = code,
                error = obj["error"]?.jsonPrimitive?.contentOrNull
            )
        }.getOrElse {
            SaMemoryResponse(ok = false, statusCode = code, error = "تعذر قراءة استجابة الذاكرة")
        }
    }

    private fun parseMemoryConflict(body: String, code: Int): SaMemoryResponse {
        val baseParsed = parseMemoryResponse(body, code)
        return baseParsed.copy(
            ok = false,
            conflict = true,
            statusCode = 409,
            error = baseParsed.error ?: "تعارض في نسخة الذاكرة (HTTP 409)"
        )
    }

    private fun parseAccountActionSuccess(body: String, code: Int): SaAccountActionResponse {
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val status = obj["status"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val message = obj["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val accountCreated = obj["account_created"]?.jsonPrimitive?.booleanOrNull
                ?: (status == "account_created")
            val linkCreated = obj["link_created"]?.jsonPrimitive?.booleanOrNull
                ?: (status == "link_created")
            val ephemeralUrl = (
                obj["login_url"]
                    ?: obj["magic_link"]
                    ?: obj["ephemeral_login_url"]
                )?.jsonPrimitive?.contentOrNull.orEmpty()

            SaAccountActionResponse(
                ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: (accountCreated || linkCreated),
                status = status,
                message = SensitiveDataRedactor.scrub(message, vault),
                accountCreated = accountCreated,
                linkCreated = linkCreated,
                ephemeralLoginUrl = ephemeralUrl,
                statusCode = code
            )
        }.getOrElse {
            SaAccountActionResponse(
                ok = false,
                statusCode = code,
                error = "تعذر قراءة استجابة إجراء الحساب"
            )
        }
    }

    private fun extractStringList(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun effectiveAttempts(): Int =
        maxAttemptsProvider().coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS)

    private fun effectiveTimeoutSeconds(): Int =
        timeoutSecondsProvider().coerceIn(
            HttpTransport.MIN_TIMEOUT_SECONDS,
            HttpTransport.MAX_TIMEOUT_SECONDS
        )

    private fun unconfiguredFailure(): HttpTransport.Result.Failure =
        HttpTransport.Result.Failure(
            error = "رابط Smart Advisor غير مهيأ",
            code = -1,
            ms = 0L,
            kind = HttpTransport.FailureKind.NON_RETRYABLE,
            retryable = false
        )

    companion object {
        const val HEADER_API_KEY = "X-SA-API-Key"
        const val CAPABILITIES_CACHE_TTL_MS = 10L * 60_000L

        val SUPPORTED_KNOWLEDGE_INTENTS = setOf(
            "product_search",
            "product_details",
            "offers",
            "store_info",
            "store_location",
            "shipping_policy",
            "returns_policy",
            "general"
        )

        private val UPPERCASE_ASSISTANT_INTENTS = setOf(
            "CATEGORY_BROWSE",
            "CATEGORY_LINK",
            "MORE_PRODUCTS",
            "PRODUCT_COMPARE",
            "PRODUCT_COMPARISON"
        )

        fun normalizeKnowledgeIntent(rawIntent: String): String {
            val trimmed = rawIntent.trim()
            val upper = trimmed.uppercase()
            if (upper in UPPERCASE_ASSISTANT_INTENTS) return upper
            val cleaned = trimmed.lowercase()
            return if (cleaned in SUPPORTED_KNOWLEDGE_INTENTS) cleaned else "general"
        }
    }
}
