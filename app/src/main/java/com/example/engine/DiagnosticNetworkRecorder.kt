package com.example.engine

import com.example.network.HttpTransport
import com.example.network.ResponseOutcomeAnalyzer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Shared instrumentation bridge mapping HTTP / Smart Advisor / Webhook / AI responses into
 * strictly allowlisted [DiagnosticTraceRecord] entries on [SafeDiagnosticLogger].
 */
object DiagnosticNetworkRecorder {

    data class ServerTraceSummary(
        val traceId: String = "",
        val authAccepted: Boolean = false,
        val stage: DiagnosticStage? = null,
        val engineUsed: String = "",
        val fallbackTriggered: Boolean = false,
        val errorCode: String = "",
        val memoryFailed: Boolean = false,
        val duplicateDetected: Boolean = false,
        val emptyReply: Boolean = false,
        val steps: List<String> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun recordPreSendBlocked(
        logger: SafeDiagnosticLogger?,
        traceId: String,
        operationType: String,
        method: String,
        rawUrl: String,
        errorCode: String,
        reasonAr: String
    ) {
        if (logger == null) return
        val authState = when (errorCode) {
            "sa_missing_key_client" -> DiagnosticAuthState.KEY_NOT_CONFIGURED
            "untrusted_smart_advisor_host" -> DiagnosticAuthState.AUTH_REJECTED
            "missing_url", "invalid_url" -> DiagnosticAuthState.ENDPOINT_NOT_FOUND
            else -> DiagnosticAuthState.CONNECTION_FAILED
        }
        val category = if (authState == DiagnosticAuthState.KEY_NOT_CONFIGURED || authState == DiagnosticAuthState.AUTH_REJECTED) {
            DiagnosticCategory.AUTH
        } else {
            DiagnosticCategory.NETWORK_HTTP
        }
        logger.logTrace(
            traceId = traceId,
            operationType = operationType,
            stage = DiagnosticStage.STAGE_03_AUTH_VERIFIED,
            outcome = DiagnosticOutcome.FAILURE,
            category = category,
            httpMethod = method,
            rawUrl = rawUrl,
            latencyMs = 0L,
            httpStatusCode = if (errorCode == "sa_missing_key_client") 401 else -1,
            stageDetailCode = "preflight_blocked",
            authState = authState,
            errorCode = errorCode,
            description = reasonAr
        )
    }

    fun recordHeaderAttached(
        logger: SafeDiagnosticLogger?,
        traceId: String,
        operationType: String,
        method: String,
        rawUrl: String,
        headerName: String = "X-SA-API-Key"
    ) {
        logger?.logTrace(
            traceId = traceId,
            operationType = operationType,
            stage = DiagnosticStage.STAGE_01_USER_MESSAGE_SENT,
            outcome = DiagnosticOutcome.UNKNOWN,
            category = DiagnosticCategory.AUTH,
            httpMethod = method,
            rawUrl = rawUrl,
            latencyMs = 0L,
            httpStatusCode = -1,
            stageDetailCode = "header_attached",
            authState = DiagnosticAuthState.HEADER_ATTACHED,
            description = "تم إرفاق ترويسة $headerName محلياً قبل الإرسال (لم يتم تأكيد قبول الخادم بعد)",
            isVerboseStep = true
        )
    }

    fun recordHttpResult(
        logger: SafeDiagnosticLogger?,
        traceId: String,
        operationType: String,
        method: String,
        rawUrl: String,
        result: HttpTransport.Result,
        defaultStage: DiagnosticStage,
        analyzedOutcome: ResponseOutcomeAnalyzer.AnalyzedOutcome? = null,
        extraSecretsToRedact: Collection<String> = emptyList(),
        expectReplyPayload: Boolean = false
    ): ServerTraceSummary {
        return when (result) {
            is HttpTransport.Result.Success -> {
                val traceSummary = parseServerTrace(result.body, fallbackTraceId = traceId)
                val anomalies = mutableListOf<DiagnosticAnomaly>()
                val parsedJson = parseJsonObjectOrNull(result.body)

                val okFlag = parsedJson?.get("ok")?.let { (it as? JsonPrimitive)?.booleanOrNull }
                val successFlag = parsedJson?.get("success")?.let { (it as? JsonPrimitive)?.booleanOrNull }
                val statusStr = parsedJson?.get("status")?.let { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }.orEmpty()
                val explicitLogicalFailure = okFlag == false ||
                    successFlag == false ||
                    statusStr in setOf("error", "failed", "failure", "disabled", "refused", "rejected") ||
                    analyzedOutcome?.status == ResponseOutcomeAnalyzer.ResponseStatus.FAILURE

                val isWarning = analyzedOutcome?.status == ResponseOutcomeAnalyzer.ResponseStatus.WARNING
                if (explicitLogicalFailure || isWarning) {
                    anomalies += DiagnosticAnomaly.HTTP_200_LOGICAL_FAILURE
                }
                if (traceSummary.emptyReply ||
                    (expectReplyPayload && analyzedOutcome != null && analyzedOutcome.extractedText.isBlank() && !explicitLogicalFailure && !isWarning && isReplyEmptyInJson(parsedJson))
                ) {
                    anomalies += DiagnosticAnomaly.EMPTY_REPLY
                }
                if (traceSummary.memoryFailed) {
                    anomalies += DiagnosticAnomaly.MEMORY_RETRIEVAL_FAILED
                }
                if (traceSummary.duplicateDetected) {
                    anomalies += DiagnosticAnomaly.DUPLICATE_MESSAGE_OR_REPLY
                }

                val effectiveAuthState = when {
                    traceSummary.errorCode in AUTH_ERROR_CODES -> DiagnosticAuthState.AUTH_REJECTED
                    traceSummary.authAccepted -> DiagnosticAuthState.SERVER_ACCEPTED
                    analyzedOutcome != null && analyzedOutcome.isSuccess -> DiagnosticAuthState.SERVER_ACCEPTED
                    !explicitLogicalFailure -> DiagnosticAuthState.SERVER_ACCEPTED
                    else -> DiagnosticAuthState.NONE
                }
                val finalOutcome = when {
                    explicitLogicalFailure -> DiagnosticOutcome.FAILURE
                    isWarning -> DiagnosticOutcome.UNKNOWN
                    anomalies.contains(DiagnosticAnomaly.EMPTY_REPLY) -> DiagnosticOutcome.UNKNOWN
                    else -> DiagnosticOutcome.SUCCESS
                }
                val finalCategory = when {
                    effectiveAuthState == DiagnosticAuthState.AUTH_REJECTED -> DiagnosticCategory.AUTH
                    anomalies.contains(DiagnosticAnomaly.MEMORY_RETRIEVAL_FAILED) -> DiagnosticCategory.MEMORY_CONTEXT
                    anomalies.isNotEmpty() -> DiagnosticCategory.REPLY_ANOMALY
                    else -> DiagnosticCategory.NETWORK_HTTP
                }
                val effectiveStage = traceSummary.stage ?: defaultStage
                val desc = analyzedOutcome?.serverMessage?.takeIf { it.isNotBlank() }
                    ?: analyzedOutcome?.reasonSummary?.takeIf { it.isNotBlank() }
                    ?: if (finalOutcome == DiagnosticOutcome.SUCCESS) {
                        "تم تنفيذ الطلب بنجاح (HTTP ${result.code})"
                    } else {
                        "أعاد الخادم فشلاً أو تحذيراً داخل HTTP ${result.code}"
                    }

                logger?.logTrace(
                    traceId = traceSummary.traceId.ifBlank { traceId },
                    operationType = operationType,
                    stage = effectiveStage,
                    outcome = finalOutcome,
                    category = finalCategory,
                    httpMethod = method,
                    rawUrl = rawUrl,
                    latencyMs = result.ms,
                    httpStatusCode = result.code,
                    stageDetailCode = traceSummary.steps.lastOrNull() ?: if (finalOutcome == DiagnosticOutcome.SUCCESS) "response_sent" else "http_200_logical_issue",
                    authState = effectiveAuthState,
                    errorCode = traceSummary.errorCode.ifBlank { analyzedOutcome?.errorCode.orEmpty() },
                    description = desc,
                    engineUsed = traceSummary.engineUsed,
                    fallbackTriggered = traceSummary.fallbackTriggered,
                    anomalies = anomalies,
                    wpServerTraceSteps = traceSummary.steps,
                    extraSecretsToRedact = extraSecretsToRedact,
                    isVerboseStep = finalOutcome == DiagnosticOutcome.SUCCESS && anomalies.isEmpty()
                )
                traceSummary
            }
            is HttpTransport.Result.Conflict -> {
                val traceSummary = parseServerTrace(result.body, fallbackTraceId = traceId)
                logger?.logTrace(
                    traceId = traceSummary.traceId.ifBlank { traceId },
                    operationType = operationType,
                    stage = defaultStage,
                    outcome = DiagnosticOutcome.UNKNOWN,
                    category = DiagnosticCategory.NETWORK_HTTP,
                    httpMethod = method,
                    rawUrl = rawUrl,
                    latencyMs = result.ms,
                    httpStatusCode = result.code,
                    stageDetailCode = "http_409_conflict",
                    authState = DiagnosticAuthState.SERVER_ACCEPTED,
                    errorCode = traceSummary.errorCode.ifBlank { "http_409" },
                    description = "تعارض من الخادم (HTTP 409): الطلب قيد المعالجة أو متزامن.",
                    anomalies = listOf(DiagnosticAnomaly.DUPLICATE_MESSAGE_OR_REPLY),
                    wpServerTraceSteps = traceSummary.steps,
                    extraSecretsToRedact = extraSecretsToRedact
                )
                traceSummary
            }
            is HttpTransport.Result.Failure -> {
                val traceSummary = parseServerTrace(result.error, fallbackTraceId = traceId)
                val extractedCode = traceSummary.errorCode.ifBlank {
                    analyzedOutcome?.errorCode.orEmpty()
                }.ifBlank {
                    extractErrorCodeLoose(result.error)
                }
                val isTimeout = result.cause is SocketTimeoutException ||
                    result.cause is InterruptedIOException ||
                    result.error.contains("timeout", ignoreCase = true) ||
                    result.error.contains("timed out", ignoreCase = true) ||
                    result.error.contains("مهلة")

                val authState = when {
                    result.code == 401 || result.code == 403 || extractedCode in AUTH_ERROR_CODES ->
                        DiagnosticAuthState.AUTH_REJECTED
                    result.code == 404 -> DiagnosticAuthState.ENDPOINT_NOT_FOUND
                    isTimeout -> DiagnosticAuthState.REQUEST_TIMEOUT
                    else -> DiagnosticAuthState.CONNECTION_FAILED
                }
                val category = if (authState == DiagnosticAuthState.AUTH_REJECTED) {
                    DiagnosticCategory.AUTH
                } else {
                    DiagnosticCategory.NETWORK_HTTP
                }
                val stage = if (authState == DiagnosticAuthState.AUTH_REJECTED) {
                    DiagnosticStage.STAGE_03_AUTH_VERIFIED
                } else {
                    traceSummary.stage ?: defaultStage
                }
                val desc = analyzedOutcome?.serverMessage?.takeIf { it.isNotBlank() }
                    ?: result.error.ifBlank { "فشل الطلب الشبكي" }

                logger?.logTrace(
                    traceId = traceSummary.traceId.ifBlank { traceId },
                    operationType = operationType,
                    stage = stage,
                    outcome = DiagnosticOutcome.FAILURE,
                    category = category,
                    httpMethod = method,
                    rawUrl = rawUrl,
                    latencyMs = result.ms,
                    httpStatusCode = result.code,
                    stageDetailCode = extractedCode.ifBlank { authState.name.lowercase() },
                    authState = authState,
                    errorCode = extractedCode.ifBlank {
                        when {
                            result.code > 0 -> "http_${result.code}"
                            isTimeout -> "request_timeout"
                            else -> "connection_failed"
                        }
                    },
                    description = desc,
                    engineUsed = traceSummary.engineUsed,
                    fallbackTriggered = traceSummary.fallbackTriggered,
                    wpServerTraceSteps = traceSummary.steps,
                    extraSecretsToRedact = extraSecretsToRedact
                )
                traceSummary
            }
        }
    }

    fun parseServerTrace(body: String, fallbackTraceId: String = ""): ServerTraceSummary {
        val obj = parseJsonObjectOrNull(body) ?: return ServerTraceSummary(traceId = fallbackTraceId)
        val diagObj = obj["diagnostic_trace"] as? JsonObject
        val authStatus = (obj["auth_status"] as? JsonPrimitive)?.contentOrNull
            ?: (diagObj?.get("auth_status") as? JsonPrimitive)?.contentOrNull
        val traceId = (diagObj?.get("trace_id") as? JsonPrimitive)?.contentOrNull
            ?: (obj["trace_id"] as? JsonPrimitive)?.contentOrNull
            ?: fallbackTraceId
        val stageStr = (diagObj?.get("stage") as? JsonPrimitive)?.contentOrNull
        val engineStr = (diagObj?.get("engine") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val fallbackTrig = (diagObj?.get("fallback_triggered") as? JsonPrimitive)?.booleanOrNull == true
        val errCode = (diagObj?.get("error_code") as? JsonPrimitive)?.contentOrNull
            ?: (obj["error_code"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["code"] as? JsonPrimitive)?.contentOrNull
            ?: ""
        val memStatus = (diagObj?.get("memory_status") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val dupFlag = (diagObj?.get("duplicate_blocked") as? JsonPrimitive)?.booleanOrNull == true
        val steps = (diagObj?.get("steps") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        val emptyFlag = (diagObj?.get("empty_reply") as? JsonPrimitive)?.booleanOrNull == true

        return ServerTraceSummary(
            traceId = traceId,
            authAccepted = authStatus.equals("accepted", ignoreCase = true),
            stage = DiagnosticStage.fromCode(stageStr),
            engineUsed = DiagnosticSanitizer.normalizeEngineLabel(engineStr),
            fallbackTriggered = fallbackTrig,
            errorCode = DiagnosticSanitizer.sanitizeErrorCode(errCode),
            memoryFailed = memStatus.equals("failed", ignoreCase = true) || errCode.contains("memory", ignoreCase = true),
            duplicateDetected = dupFlag,
            emptyReply = emptyFlag,
            steps = DiagnosticSanitizer.sanitizeWpTraceSteps(steps)
        )
    }

    private fun isReplyEmptyInJson(obj: JsonObject?): Boolean {
        if (obj == null) return false
        val reply = (obj["reply"] as? JsonPrimitive)?.contentOrNull
        val suggested = (obj["suggested_reply"] as? JsonPrimitive)?.contentOrNull
        val products = obj["products"] as? JsonArray
        val deferred = (obj["reply_deferred_to_orchestrator"] as? JsonPrimitive)?.booleanOrNull == true
        if (deferred) return suggested.isNullOrBlank() && (products == null || products.isEmpty())
        return reply != null && reply.isBlank() && (products == null || products.isEmpty())
    }

    private fun parseJsonObjectOrNull(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
    }

    private fun extractErrorCodeLoose(raw: String): String {
        val parsed = parseJsonObjectOrNull(raw)
        if (parsed != null) {
            val code = (parsed["error_code"] as? JsonPrimitive)?.contentOrNull
                ?: (parsed["code"] as? JsonPrimitive)?.contentOrNull
            if (!code.isNullOrBlank()) return DiagnosticSanitizer.sanitizeErrorCode(code)
        }
        return ""
    }

    private val AUTH_ERROR_CODES = setOf(
        "sa_missing_key",
        "sa_missing_key_client",
        "sa_bad_key",
        "sa_key_not_configured",
        "sa_wa_disabled",
        "sa_customer_service_disabled",
        "auth_error",
        "sa_forbidden",
        "rest_forbidden"
    )
}
