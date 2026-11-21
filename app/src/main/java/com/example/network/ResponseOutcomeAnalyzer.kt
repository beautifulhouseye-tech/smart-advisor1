package com.example.network

import com.example.engine.VariableEngine
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Centralized analyzer for Webhook and Smart Advisor HTTP responses.
 *
 * Strictly separates HTTP 2xx transport status from semantic outcome:
 * - [ResponseStatus.SUCCESS]: Valid response without error indicators.
 * - [ResponseStatus.WARNING]: Ambiguous/empty/invalid-JSON or temporary service unavailability inside HTTP 200.
 * - [ResponseStatus.FAILURE]: Auth failures (401/403/sa_missing_key/sa_bad_key), HTTP errors (4xx/5xx),
 *   network failures, or explicit JSON logical failure (`ok=false`, `success=false`, `status="error"`, etc.).
 */
object ResponseOutcomeAnalyzer {

    enum class ResponseStatus {
        SUCCESS,
        WARNING,
        FAILURE
    }

    data class AnalyzedOutcome(
        val status: ResponseStatus,
        val statusCode: Int,
        val latencyMs: Long,
        val title: String,
        val serverMessage: String,
        val extractedText: String = "",
        val rawExcerpt: String = "",
        val errorCode: String? = null,
        val reasonSummary: String = "",
        val guidance: String = "",
        val sourceLabel: String = "",
        val trustLabel: String = "",
        val truncated: Boolean = false
    ) {
        val isSuccess: Boolean get() = status == ResponseStatus.SUCCESS
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun analyze(
        result: HttpTransport.Result,
        vault: SecretVault,
        replyKey: String = "reply",
        expectJson: Boolean = true,
        maxExcerptChars: Int = WebhookClient.DEFAULT_EXCERPT_CHARS,
        isToolPreview: Boolean = false,
        sourceLabel: String = "",
        trustLabel: String = "",
        extraSecretsToRedact: Collection<String> = emptyList()
    ): AnalyzedOutcome {
        return when (result) {
            is HttpTransport.Result.Success -> analyzeSuccessHttp(
                result = result,
                vault = vault,
                replyKey = replyKey,
                expectJson = expectJson,
                maxExcerptChars = maxExcerptChars,
                isToolPreview = isToolPreview,
                sourceLabel = sourceLabel,
                trustLabel = trustLabel,
                extraSecretsToRedact = extraSecretsToRedact
            )
            is HttpTransport.Result.Conflict -> {
                val scrubbed = redact(result.body, vault, extraSecretsToRedact)
                val parsed = parseJsonObjectOrNull(scrubbed)
                val extractedMsg = extractServerMessage(parsed).ifBlank { scrubbed.take(300) }
                AnalyzedOutcome(
                    status = ResponseStatus.FAILURE,
                    statusCode = result.code,
                    latencyMs = result.ms,
                    title = "تعارض من الخادم (HTTP ${result.code})",
                    serverMessage = extractedMsg.ifBlank { "الرسالة قيد المعالجة أو يوجد تعارض مؤقت على الخادم." },
                    rawExcerpt = scrubbed.take(maxExcerptChars),
                    errorCode = extractErrorCode(parsed) ?: "http_409",
                    reasonSummary = "الخادم أرجع حالة تعارض 409 (قفل معالجة نشط أو تعارض نسخة).",
                    guidance = "انتظر لحظات وأعد الاختبار، وتأكد من عدم تكرار نفس معرف الرسالة.",
                    sourceLabel = sourceLabel,
                    trustLabel = trustLabel,
                    truncated = scrubbed.length > maxExcerptChars
                )
            }
            is HttpTransport.Result.Failure -> analyzeTransportFailure(
                result = result,
                vault = vault,
                maxExcerptChars = maxExcerptChars,
                sourceLabel = sourceLabel,
                trustLabel = trustLabel,
                extraSecretsToRedact = extraSecretsToRedact
            )
        }
    }

    fun fromBlockedAuth(
        errorCode: String,
        reasonAr: String,
        guidanceAr: String,
        sourceLabel: String = "",
        trustLabel: String = ""
    ): AnalyzedOutcome = AnalyzedOutcome(
        status = ResponseStatus.FAILURE,
        statusCode = -1,
        latencyMs = 0L,
        title = when (errorCode) {
            "sa_missing_key_client" -> "مفتاح API غير مضبوط في التطبيق"
            "insecure_transport" -> "بروتوكول الاتصال غير آمن (يلزم HTTPS)"
            "untrusted_smart_advisor_host" -> "تعارض في نطاق Smart Advisor الموثوق"
            else -> "تعذر تجهيز الطلب"
        },
        serverMessage = reasonAr,
        errorCode = errorCode,
        reasonSummary = reasonAr,
        guidance = guidanceAr,
        sourceLabel = sourceLabel,
        trustLabel = trustLabel
    )

    private fun analyzeTransportFailure(
        result: HttpTransport.Result.Failure,
        vault: SecretVault,
        maxExcerptChars: Int,
        sourceLabel: String,
        trustLabel: String,
        extraSecretsToRedact: Collection<String>
    ): AnalyzedOutcome {
        val scrubbedRaw = redact(result.error, vault, extraSecretsToRedact)
        val parsed = parseJsonObjectOrNull(scrubbedRaw)
        val errCode = extractErrorCode(parsed)
        val serverMsg = extractServerMessage(parsed).ifBlank { scrubbedRaw }
        val statusCode = result.code

        val (title, reason, guidance) = classifyErrorDetails(
            httpCode = statusCode,
            errorCode = errCode,
            serverMsg = serverMsg,
            fallbackTransportKind = result.kind
        )

        return AnalyzedOutcome(
            status = ResponseStatus.FAILURE,
            statusCode = statusCode,
            latencyMs = result.ms,
            title = title,
            serverMessage = serverMsg.ifBlank { reason },
            rawExcerpt = scrubbedRaw.take(maxExcerptChars),
            errorCode = errCode ?: if (statusCode > 0) "http_$statusCode" else "network_error",
            reasonSummary = reason,
            guidance = guidance,
            sourceLabel = sourceLabel,
            trustLabel = trustLabel,
            truncated = scrubbedRaw.length > maxExcerptChars
        )
    }

    private fun analyzeSuccessHttp(
        result: HttpTransport.Result.Success,
        vault: SecretVault,
        replyKey: String,
        expectJson: Boolean,
        maxExcerptChars: Int,
        isToolPreview: Boolean,
        sourceLabel: String,
        trustLabel: String,
        extraSecretsToRedact: Collection<String>
    ): AnalyzedOutcome {
        val scrubbedRaw = redact(result.body, vault, extraSecretsToRedact)
        val trimmedRaw = scrubbedRaw.trim()
        val truncated = scrubbedRaw.length > maxExcerptChars
        val excerpt = scrubbedRaw.take(maxExcerptChars)

        if (trimmedRaw.isEmpty()) {
            return AnalyzedOutcome(
                status = ResponseStatus.WARNING,
                statusCode = result.code,
                latencyMs = result.ms,
                title = "تحذير: استجابة فارغة من الخادم",
                serverMessage = "أعاد الخادم رمز HTTP ${result.code} بدون محتوى في جسم الاستجابة.",
                rawExcerpt = "",
                errorCode = "empty_response_body",
                reasonSummary = "لم يرجع الخادم أي بيانات يمكن التحقق منها أو إرسالها للعميل.",
                guidance = "تأكد من مسار نقطة النهاية على الخادم وأن الإضافة تُرجع JSON يحتوي على الحقل المطلوب.",
                sourceLabel = sourceLabel,
                trustLabel = trustLabel
            )
        }

        val parsedObj = parseJsonObjectOrNull(trimmedRaw)
        val looksLikeJson = trimmedRaw.startsWith("{") || trimmedRaw.startsWith("[") || expectJson

        if (parsedObj == null && looksLikeJson) {
            val isJsonArray = runCatching { json.parseToJsonElement(trimmedRaw) as? JsonArray }.getOrNull() != null
            if (!isJsonArray) {
                // If plain text was returned on a JSON-expected endpoint, check if it's an obvious server/HTML error
                val tempMsg = detectServiceTemporaryMessage(trimmedRaw)
                val looksLikeHtml = trimmedRaw.startsWith("<", ignoreCase = true)
                return if (looksLikeHtml) {
                    AnalyzedOutcome(
                        status = ResponseStatus.FAILURE,
                        statusCode = result.code,
                        latencyMs = result.ms,
                        title = "استجابة HTML بدلاً من JSON",
                        serverMessage = trimmedRaw.take(240),
                        rawExcerpt = excerpt,
                        errorCode = "unexpected_html_response",
                        reasonSummary = "أعاد الخادم صفحة HTML (مثل صفحة حماية أو إعادة توجيه) بدلاً من JSON.",
                        guidance = "تحقق من الرابط ومن إعدادات الحماية/الكاش في الخادم.",
                        sourceLabel = sourceLabel,
                        trustLabel = trustLabel,
                        truncated = truncated
                    )
                } else {
                    AnalyzedOutcome(
                        status = ResponseStatus.WARNING,
                        statusCode = result.code,
                        latencyMs = result.ms,
                        title = "تحذير: صيغة JSON غير صالحة",
                        serverMessage = tempMsg ?: trimmedRaw.take(300),
                        rawExcerpt = excerpt,
                        errorCode = "invalid_json",
                        reasonSummary = "الاستجابة من الخادم ليست بصيغة JSON صالحة رغم توقع استجابة JSON.",
                        guidance = "تأكد من خلو مخرجات WordPress/الخادم من تحذيرات PHP أو نصوص إضافية قبل JSON.",
                        sourceLabel = sourceLabel,
                        trustLabel = trustLabel,
                        truncated = truncated
                    )
                }
            }
        }

        val extractedByPath = if (replyKey.isNotBlank()) {
            redact(VariableEngine.extractReply(trimmedRaw, replyKey).orEmpty(), vault, extraSecretsToRedact)
        } else {
            ""
        }

        if (parsedObj != null) {
            val dataObj = parsedObj["data"] as? JsonObject
            val errCode = extractErrorCode(parsedObj)
            val explicitOk = extractBooleanFlag(parsedObj, "ok") ?: extractBooleanFlag(dataObj, "ok")
            val explicitSuccess = extractBooleanFlag(parsedObj, "success") ?: extractBooleanFlag(dataObj, "success")
            val statusStr = extractStringStatus(parsedObj) ?: extractStringStatus(dataObj)
            val nestedHttpStatus = (dataObj?.get("status") as? JsonPrimitive)?.intOrNull
                ?: (parsedObj["status"] as? JsonPrimitive)?.intOrNull

            val structuredErrMsg = extractContextualErrorField(parsedObj, dataObj)
            val serverMessage = extractServerMessage(parsedObj).ifBlank { extractedByPath }

            // 1) Embedded auth / disabled plugin WP_Error inside HTTP 200
            if (errCode != null && isAuthOrConfigErrorCode(errCode)) {
                val (title, reason, guidance) = classifyErrorDetails(
                    httpCode = nestedHttpStatus ?: result.code,
                    errorCode = errCode,
                    serverMsg = serverMessage.ifBlank { structuredErrMsg.orEmpty() },
                    fallbackTransportKind = HttpTransport.FailureKind.NON_RETRYABLE
                )
                return AnalyzedOutcome(
                    status = ResponseStatus.FAILURE,
                    statusCode = result.code,
                    latencyMs = result.ms,
                    title = title,
                    serverMessage = serverMessage.ifBlank { structuredErrMsg ?: reason },
                    extractedText = extractedByPath.take(maxExcerptChars),
                    rawExcerpt = excerpt,
                    errorCode = errCode,
                    reasonSummary = reason,
                    guidance = guidance,
                    sourceLabel = sourceLabel,
                    trustLabel = trustLabel,
                    truncated = truncated
                )
            }

            // 2) Embedded HTTP >= 400 inside data.status (e.g. WP_Error serialized as 200)
            if (nestedHttpStatus != null && nestedHttpStatus >= 400) {
                val (title, reason, guidance) = classifyErrorDetails(
                    httpCode = nestedHttpStatus,
                    errorCode = errCode,
                    serverMsg = serverMessage.ifBlank { structuredErrMsg.orEmpty() },
                    fallbackTransportKind = HttpTransport.FailureKind.FAILED_CONFIRMED
                )
                return AnalyzedOutcome(
                    status = ResponseStatus.FAILURE,
                    statusCode = result.code,
                    latencyMs = result.ms,
                    title = title,
                    serverMessage = serverMessage.ifBlank { structuredErrMsg ?: reason },
                    extractedText = extractedByPath.take(maxExcerptChars),
                    rawExcerpt = excerpt,
                    errorCode = errCode ?: "embedded_http_$nestedHttpStatus",
                    reasonSummary = reason,
                    guidance = guidance,
                    sourceLabel = sourceLabel,
                    trustLabel = trustLabel,
                    truncated = truncated
                )
            }

            // 3) Logical failure flags: ok=false, success=false, status=error/failed, or contextual error field
            val isStatusFailure = statusStr in FAILURE_STATUS_VALUES
            val isStatusWarning = statusStr in WARNING_STATUS_VALUES
            val isOperationalErrorCode = errCode != null && errCode.lowercase() !in BENIGN_CODES

            if (explicitOk == false || explicitSuccess == false || isStatusFailure || structuredErrMsg != null) {
                val effectiveMsg = serverMessage.ifBlank { structuredErrMsg ?: "أرجع الخادم مؤشر فشل منطقي داخل الاستجابة." }
                val isTemporary = isTemporaryErrorCode(errCode) || detectServiceTemporaryMessage(effectiveMsg) != null
                val finalStatus = if (explicitOk == false || explicitSuccess == false || isStatusFailure || structuredErrMsg != null) {
                    if (statusStr == "service_unavailable" || isTemporary && explicitOk != false && explicitSuccess != false && !isStatusFailure && structuredErrMsg == null) {
                        ResponseStatus.WARNING
                    } else {
                        ResponseStatus.FAILURE
                    }
                } else {
                    ResponseStatus.FAILURE
                }
                return AnalyzedOutcome(
                    status = finalStatus,
                    statusCode = result.code,
                    latencyMs = result.ms,
                    title = if (finalStatus == ResponseStatus.WARNING) {
                        "تحذير: تعذّر مؤقت في الخدمة"
                    } else {
                        "فشل منطقي من الخادم (HTTP ${result.code})"
                    },
                    serverMessage = effectiveMsg,
                    extractedText = extractedByPath.take(maxExcerptChars),
                    rawExcerpt = excerpt,
                    errorCode = errCode ?: statusStr ?: "logical_failure",
                    reasonSummary = "رغم أن رمز HTTP هو ${result.code}، احتوت بنية JSON على مؤشر فشل (${describeFailureSignal(explicitOk, explicitSuccess, statusStr, errCode, structuredErrMsg)}).",
                    guidance = if (isTemporary) {
                        "تحقق من حالة محرك الذكاء الاصطناعي وإعدادات المهارات في لوحة WordPress."
                    } else {
                        "راجع رسالة الخادم وتأكد من صلاحية البيانات والمصادقة وإعدادات الإضافة."
                    },
                    sourceLabel = sourceLabel,
                    trustLabel = trustLabel,
                    truncated = truncated
                )
            }

            if (isStatusWarning || isOperationalErrorCode) {
                val effectiveMsg = serverMessage.ifBlank { "أرجع الخادم تحذيراً تشغيلياً." }
                return AnalyzedOutcome(
                    status = ResponseStatus.WARNING,
                    statusCode = result.code,
                    latencyMs = result.ms,
                    title = "تحذير من الخادم (HTTP ${result.code})",
                    serverMessage = effectiveMsg,
                    extractedText = extractedByPath.take(maxExcerptChars),
                    rawExcerpt = excerpt,
                    errorCode = errCode ?: statusStr,
                    reasonSummary = "الاستجابة غير مكتملة أو تشير إلى حالة تحذيرية (${errCode ?: statusStr}).",
                    guidance = "راجع حالة الخدمة ومحتوى الاستجابة قبل الاعتماد عليها.",
                    sourceLabel = sourceLabel,
                    trustLabel = trustLabel,
                    truncated = truncated
                )
            }

            // 4) Check reply / suggested_reply / message for known temporary service-error templates
            val candidateReply = extractedByPath.ifBlank {
                (parsedObj["suggested_reply"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            }.ifBlank {
                (parsedObj["reply"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            }.ifBlank {
                (parsedObj["message"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            }

            val detectedTempWarning = detectServiceTemporaryMessage(candidateReply)
            if (detectedTempWarning != null) {
                return AnalyzedOutcome(
                    status = ResponseStatus.WARNING,
                    statusCode = result.code,
                    latencyMs = result.ms,
                    title = "تحذير: رسالة تعذّر مؤقت من الخادم",
                    serverMessage = candidateReply,
                    extractedText = extractedByPath.take(maxExcerptChars),
                    rawExcerpt = excerpt,
                    errorCode = "service_temporary_unavailable",
                    reasonSummary = "استجاب الخادم برمز HTTP ${result.code} لكنه أعاد رسالة تعذّر مؤقت للخدمة بدلاً من رد فعلي.",
                    guidance = "تحقق من اتصال محرك الذكاء الاصطناعي في الإضافة وتفعيل مهارتي الترحيب والبحث في إعدادات Smart Advisor.",
                    sourceLabel = sourceLabel,
                    trustLabel = trustLabel,
                    truncated = truncated
                )
            }

            // 5) If extractedText was specifically expected and blank while no suggested_reply/delivery marker exists
            val deferredReply = (parsedObj["suggested_reply"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val effectiveExtracted = extractedByPath.ifBlank { deferredReply }

            return AnalyzedOutcome(
                status = ResponseStatus.SUCCESS,
                statusCode = result.code,
                latencyMs = result.ms,
                title = if (isToolPreview) "نجحت معاينة أداة AI" else "الاتصال ناجح",
                serverMessage = effectiveExtracted.ifBlank { serverMessage }.ifBlank { "استجابة صالحة من الخادم." },
                extractedText = effectiveExtracted.take(maxExcerptChars),
                rawExcerpt = excerpt,
                sourceLabel = sourceLabel,
                trustLabel = trustLabel,
                truncated = truncated
            )
        }

        // Plain-text HTTP 200 or JSON array
        val tempPlainWarning = detectServiceTemporaryMessage(trimmedRaw)
        if (tempPlainWarning != null) {
            return AnalyzedOutcome(
                status = ResponseStatus.WARNING,
                statusCode = result.code,
                latencyMs = result.ms,
                title = "تحذير: رسالة تعذّر مؤقت من الخادم",
                serverMessage = trimmedRaw.take(300),
                extractedText = trimmedRaw.take(maxExcerptChars),
                rawExcerpt = excerpt,
                errorCode = "service_temporary_unavailable",
                reasonSummary = "احتوى النص العائد من الخادم على رسالة تعذّر مؤقت.",
                guidance = "راجع حالة الخدمة على الخادم وأعد الاختبار.",
                sourceLabel = sourceLabel,
                trustLabel = trustLabel,
                truncated = truncated
            )
        }

        return AnalyzedOutcome(
            status = ResponseStatus.SUCCESS,
            statusCode = result.code,
            latencyMs = result.ms,
            title = if (isToolPreview) "نجحت معاينة أداة AI" else "الاتصال ناجح",
            serverMessage = extractedByPath.ifBlank { trimmedRaw.take(300) },
            extractedText = extractedByPath.ifBlank { trimmedRaw }.take(maxExcerptChars),
            rawExcerpt = excerpt,
            sourceLabel = sourceLabel,
            trustLabel = trustLabel,
            truncated = truncated
        )
    }

    private fun classifyErrorDetails(
        httpCode: Int,
        errorCode: String?,
        serverMsg: String,
        fallbackTransportKind: HttpTransport.FailureKind
    ): Triple<String, String, String> {
        val codeLc = errorCode?.trim()?.lowercase().orEmpty()
        return when {
            codeLc == "sa_missing_key" || (httpCode == 401 && codeLc.isEmpty()) -> Triple(
                "فشل المصادقة: مفتاح API مفقود (HTTP 401)",
                "لم يستلم الخادم ترويسة X-SA-API-Key المطلوبة للوصول إلى مسار Smart Advisor.",
                "أدخل مفتاح API السري في إعدادات Smart Advisor واحفظه (أو أضف ترويسة X-SA-API-Key في Webhook)، ثم تأكد أن خادمك لا يحذف الترويسات المخصصة."
            )
            codeLc == "sa_key_not_configured" -> Triple(
                "مفتاح الخادم غير مضبوط في الإضافة",
                "مفتاح API لم يتم توليده أو حفظه بعد داخل إعدادات Smart Advisor على WordPress.",
                "افتح لوحة تحكم WordPress -> Smart Advisor -> إعدادات الواتساب، ثم أنشئ مفتاح API جديداً وانسخه إلى التطبيق."
            )
            codeLc == "sa_bad_key" || httpCode == 403 && (codeLc.isEmpty() || serverMsg.contains("غير صحيح")) -> Triple(
                "فشل المصادقة: مفتاح API غير صحيح (HTTP 403)",
                "المفتاح المرسل في ترويسة X-SA-API-Key لا يطابق المفتاح المحفوظ على الخادم.",
                "انسخ المفتاح الصحيح مجدداً من إعدادات Smart Advisor في WordPress وأعد حفظه في التطبيق."
            )
            codeLc == "sa_wa_disabled" -> Triple(
                "تكامل واتساب معطل في الإضافة (HTTP 403)",
                "المسار مرفوض لأن خيار تكامل الواتساب غير مفعّل داخل إعدادات Smart Advisor.",
                "فعّل خيار تكامل الواتساب في لوحة تحكم الإضافة بـ WordPress."
            )
            codeLc == "sa_customer_service_disabled" || codeLc == "sa_automate_integration_disabled" -> Triple(
                "خدمة المساعد معطلة في الإضافة (HTTP 403)",
                "المساعد الذكي أو تكامل AutoMate معطّل حالياً من إعدادات الإضافة على الخادم.",
                "فعّل خدمة العملاء وتكامل AutoMate AI من إعدادات Smart Advisor في WordPress."
            )
            httpCode == 403 -> Triple(
                "تم رفض الطلب من الخادم (HTTP 403)",
                "رفض الخادم تنفيذ الطلب بسبب الصلاحيات أو جدار الحماية.",
                "تحقق من مفتاح API وصلاحيات الإضافة وإعدادات الحماية على الخادم."
            )
            httpCode == 429 || fallbackTransportKind == HttpTransport.FailureKind.RATE_LIMITED -> Triple(
                "تجاوز حد الطلبات (HTTP 429)",
                "أرسل التطبيق طلبات أكثر من الحد المسموح به في الدقيقة.",
                "انتظر دقيقة ثم أعد المحاولة."
            )
            httpCode >= 500 || isTemporaryErrorCode(codeLc) -> Triple(
                "تعذّر مؤقت في الخادم (HTTP ${if (httpCode > 0) httpCode else 503})",
                "أبلغ الخادم عن عطل داخلي أو تعذّر مؤقت في المحرك.",
                "راجع سجل أخطاء الإضافة وإعدادات مفتاح Gemini/الذكاء الاصطناعي في WordPress."
            )
            httpCode == 408 || fallbackTransportKind == HttpTransport.FailureKind.DELIVERY_UNKNOWN -> Triple(
                "انتهت مهلة الانتظار أو حالة التسليم غير مؤكدة",
                "لم يصل رد مكتمل من الخادم ضمن المهلة المحددة.",
                "تأكد من سرعة استجابة الخادم أو ارفع مهلة الانتظار."
            )
            else -> Triple(
                "فشل الطلب" + if (httpCode > 0) " (HTTP $httpCode)" else "",
                "تعذر إتمام الطلب إلى نقطة النهاية المحددة.",
                "تحقق من صحة رابط HTTPS وتوافر الاتصال بالإنترنت وإعدادات الخادم."
            )
        }
    }

    private fun parseJsonObjectOrNull(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun extractErrorCode(obj: JsonObject?): String? {
        if (obj == null) return null
        val dataObj = obj["data"] as? JsonObject
        val candidates = listOfNotNull(
            (obj["error_code"] as? JsonPrimitive)?.contentOrNull,
            (obj["code"] as? JsonPrimitive)?.contentOrNull,
            (dataObj?.get("error_code") as? JsonPrimitive)?.contentOrNull,
            (dataObj?.get("code") as? JsonPrimitive)?.contentOrNull
        )
        return candidates.firstOrNull { c ->
            val trimmed = c.trim()
            trimmed.isNotEmpty() && trimmed.lowercase() !in BENIGN_CODES
        }?.trim()
    }

    private fun extractServerMessage(obj: JsonObject?): String {
        if (obj == null) return ""
        val dataObj = obj["data"] as? JsonObject
        val candidates = listOfNotNull(
            (obj["message"] as? JsonPrimitive)?.contentOrNull,
            (obj["error_message"] as? JsonPrimitive)?.contentOrNull,
            (obj["suggested_reply"] as? JsonPrimitive)?.contentOrNull,
            (obj["reply"] as? JsonPrimitive)?.contentOrNull,
            (dataObj?.get("message") as? JsonPrimitive)?.contentOrNull,
            (dataObj?.get("reply") as? JsonPrimitive)?.contentOrNull
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun extractBooleanFlag(obj: JsonObject?, key: String): Boolean? {
        val el = obj?.get(key) as? JsonPrimitive ?: return null
        return el.booleanOrNull
    }

    private fun extractStringStatus(obj: JsonObject?): String? {
        val el = obj?.get("status") as? JsonPrimitive ?: return null
        if (!el.isString) return null
        return el.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Context-aware inspection of `error` property.
     * Ignores `null`, `false`, `""`, `"none"`, `0`, or `{}`.
     */
    private fun extractContextualErrorField(root: JsonObject, dataObj: JsonObject?): String? {
        val candidates = listOfNotNull(root["error"], dataObj?.get("error"))
        for (el in candidates) {
            when (el) {
                is JsonNull -> Unit
                is JsonPrimitive -> {
                    val boolVal = el.booleanOrNull
                    if (boolVal == true) return "أرجع الخادم error=true"
                    if (boolVal == false) continue
                    val intVal = el.intOrNull
                    if (intVal != null) {
                        if (intVal != 0) return "أرجع الخادم رمز خطأ: $intVal"
                        continue
                    }
                    val text = el.contentOrNull?.trim().orEmpty()
                    if (text.isNotEmpty() && text.lowercase() !in setOf("none", "null", "ok", "false", "0")) {
                        return text
                    }
                }
                is JsonObject -> {
                    if (el.isNotEmpty()) {
                        val msg = (el["message"] as? JsonPrimitive)?.contentOrNull
                            ?: (el["code"] as? JsonPrimitive)?.contentOrNull
                        if (!msg.isNullOrBlank()) return msg.trim()
                        return el.toString()
                    }
                }
                is JsonArray -> {
                    if (el.isNotEmpty()) return el.toString()
                }
            }
        }
        return null
    }

    /**
     * Detects explicit temporary-service failure apologies inside operational reply text
     * without triggering false positives on normal conversational replies that merely mention words like "مشكلة".
     */
    internal fun detectServiceTemporaryMessage(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val matched = SERVICE_FAILURE_TEMPLATES.any { regex -> regex.containsMatchIn(trimmed) }
        return if (matched) trimmed else null
    }

    private fun isAuthOrConfigErrorCode(code: String): Boolean =
        code.lowercase() in setOf(
            "sa_missing_key",
            "sa_bad_key",
            "sa_key_not_configured",
            "sa_wa_disabled",
            "sa_customer_service_disabled",
            "sa_automate_integration_disabled",
            "unauthorized",
            "forbidden"
        )

    private fun isTemporaryErrorCode(code: String?): Boolean =
        code?.lowercase() in setOf(
            "service_error",
            "service_temporary_unavailable",
            "analyzer_temporary_error",
            "missing_analyzer",
            "inbox_unavailable",
            "sa_rl_error",
            "sa_rate",
            "sa_account_inbox_error",
            "sa_account_action_error",
            "sa_greeting_disabled"
        )

    private fun describeFailureSignal(
        ok: Boolean?,
        success: Boolean?,
        statusStr: String?,
        errCode: String?,
        errField: String?
    ): String {
        val parts = mutableListOf<String>()
        if (ok == false) parts += "ok=false"
        if (success == false) parts += "success=false"
        if (statusStr != null) parts += "status=$statusStr"
        if (errCode != null) parts += "code=$errCode"
        if (errField != null && parts.isEmpty()) parts += "error=$errField"
        return parts.joinToString(", ").ifBlank { "error" }
    }

    private fun redact(
        raw: String,
        vault: SecretVault,
        extraSecrets: Collection<String> = emptyList()
    ): String {
        if (raw.isEmpty()) return ""
        var clean = vault.redact(SensitiveDataRedactor.scrub(raw, vault))
        if (extraSecrets.isNotEmpty()) {
            extraSecrets
                .asSequence()
                .map { it.trim() }
                .filter { it.length >= 4 }
                .sortedByDescending { it.length }
                .distinct()
                .forEach { secret ->
                    if (clean.contains(secret)) {
                        clean = clean.replace(secret, "***REDACTED***")
                    }
                }
        }
        return clean
    }

    private val FAILURE_STATUS_VALUES = setOf(
        "error", "failed", "fail", "rejected", "refused", "unauthorized", "forbidden", "invalid"
    )

    private val WARNING_STATUS_VALUES = setOf(
        "warning", "degraded", "partial", "service_unavailable", "temporary_error", "retryable"
    )

    private val BENIGN_CODES = setOf("ok", "success", "0", "none")

    private val SERVICE_FAILURE_TEMPLATES = listOf(
        Regex("""عذر[ا\u064B]{0,2}\s*[،,]?\s*أواجه مشكلة مؤقتة في الخدمة"""),
        Regex("""عذر[ا\u064B]{0,2}\s*[،,]?\s*أواجه ضغط[ا\u064B]{0,2}\s+في الخدمة"""),
        Regex("""الخدمة غير متاحة (?:حالي[ا\u064B]{0,2}|مؤقت[ا\u064B]{0,2})"""),
        Regex("""حدث خطأ مؤقت أثناء معالجة الرسالة"""),
        Regex("""تعذ[ّ]?ر الاتصال بالمحرك"""),
        Regex("""تعذ[ّ]?ر تنفيذ الطلب"""),
        Regex("""الخدمة تحت الضغط"""),
        Regex("""المساعد وخدمة العملاء معطلان حالي[ا\u064B]{0,2}"""),
        Regex("""(?i)temporary\s+service\s+(?:error|unavailable)|service\s+temporarily\s+unavailable""")
    )
}
