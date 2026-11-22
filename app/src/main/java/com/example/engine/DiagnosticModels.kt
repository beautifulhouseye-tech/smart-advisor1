package com.example.engine

import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

@Serializable
enum class DiagnosticAuthState(val labelAr: String) {
    NONE("غير مطلوب"),
    KEY_NOT_CONFIGURED("المفتاح غير مهيأ"),
    HEADER_ATTACHED("تم إرفاق ترويسة المفتاح"),
    SERVER_ACCEPTED("قبل الخادم المصادقة"),
    AUTH_REJECTED("رفض الخادم المصادقة"),
    ENDPOINT_NOT_FOUND("المسار غير موجود (404)"),
    CONNECTION_FAILED("تعذر الاتصال بالخادم"),
    REQUEST_TIMEOUT("انتهاء مهلة الطلب")
}

@Serializable
enum class DiagnosticStage(val order: Int, val code: String, val labelAr: String) {
    STAGE_01_USER_MESSAGE_SENT(1, "send_user_message", "1. إرسال رسالة المستخدم"),
    STAGE_02_WP_RECEIVED(2, "wp_received", "2. وصول الطلب إلى WordPress"),
    STAGE_03_AUTH_VERIFIED(3, "auth_verified", "3. التحقق من المصادقة"),
    STAGE_04_INTENT_ANALYZED(4, "intent_analyzed", "4. تحليل النية والرسالة"),
    STAGE_05_CONTEXT_MEMORY_LOADED(5, "context_memory_loaded", "5. استرجاع سياق المحادثة والذاكرة"),
    STAGE_06_ENGINE_SELECTED(6, "engine_selected", "6. اختيار محرك الذكاء الاصطناعي"),
    STAGE_07_NEWAPI_DISPATCHED(7, "newapi_dispatched", "7. الإرسال للمحرك الأساسي NewAPI"),
    STAGE_08_QWEN_FALLBACK_DISPATCHED(8, "qwen_fallback_dispatched", "8. التحويل للاحتياطي Qwen 2.5:1.5b"),
    STAGE_09_ENGINE_RESPONSE_RECEIVED(9, "engine_response_received", "9. استلام استجابة المحرك"),
    STAGE_10_REPLY_COMPOSED(10, "reply_composed", "10. معالجة الاستجابة إلى رد"),
    STAGE_11_REPLY_RETURNED_TO_APP(11, "reply_returned_to_app", "11. إرجاع الرد إلى التطبيق"),
    STAGE_12_REPLY_DISPLAYED_TO_USER(12, "reply_displayed_to_user", "12. عرض أو إرسال الرد للمستخدم");

    companion object {
        fun fromCode(raw: String?): DiagnosticStage? {
            val clean = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.code == clean || it.name.lowercase() == clean }
        }
    }
}

@Serializable
enum class DiagnosticOutcome(val labelAr: String) {
    SUCCESS("ناجحة"),
    FAILURE("فاشلة"),
    UNKNOWN("غير معروفة")
}

@Serializable
enum class DiagnosticCategory(val labelAr: String) {
    AUTH("المصادقة"),
    NETWORK_HTTP("الشبكة وHTTP"),
    STAGE_PIPELINE("مراحل المعالجة"),
    MEMORY_CONTEXT("الذاكرة والسياق"),
    AI_ENGINE("محركات الذكاء"),
    REPLY_ANOMALY("شذوذات الرد")
}

@Serializable
enum class DiagnosticAnomaly(val labelAr: String) {
    EMPTY_REPLY("رد فارغ"),
    INVALID_REPLY("رد غير صالح"),
    REPLY_QUESTION_MISMATCH("عدم تطابق الرد مع السؤال"),
    CONTEXT_LOST("فقدان سياق المحادثة"),
    MEMORY_RETRIEVAL_FAILED("فشل استرجاع الذاكرة"),
    FALLBACK_NOT_TRIGGERED("عدم تحفيز الاحتياطي عند فشل الأساسي"),
    PRIMARY_ENGINE_FAILED("فشل المحرك الأساسي NewAPI"),
    FALLBACK_ENGINE_FAILED("فشل المحرك الاحتياطي Qwen"),
    DUPLICATE_MESSAGE_OR_REPLY("اكتشاف رسالة أو رد مكرر"),
    REPLY_LOOP_DETECTED("اكتشاف حلقة رد مفرغة"),
    HTTP_200_LOGICAL_FAILURE("فشل منطقي داخل استجابة HTTP 200"),
    REPLY_NOT_DISPLAYED_IN_UI("وصل الرد للتطبيق ولم يظهر في الإرسال أو الواجهة")
}

@Serializable
data class DiagnosticTraceRecord(
    val id: String,
    val traceId: String,
    val timestampMs: Long,
    val sourceSide: String = SIDE_APP,
    val operationType: String,
    val httpMethod: String = "LOCAL",
    val sanitizedUrlPath: String = "",
    val latencyMs: Long = 0L,
    val httpStatusCode: Int = -1,
    val stage: DiagnosticStage,
    val stageDetailCode: String = "",
    val authState: DiagnosticAuthState = DiagnosticAuthState.NONE,
    val errorCode: String = "",
    val shortDescription: String = "",
    val engineUsed: String = "",
    val fallbackTriggered: Boolean = false,
    val outcome: DiagnosticOutcome,
    val category: DiagnosticCategory,
    val anomalies: List<DiagnosticAnomaly> = emptyList(),
    val wpServerTraceSteps: List<String> = emptyList()
) {
    companion object {
        const val SIDE_APP = "APP"
        const val SIDE_WORDPRESS = "WORDPRESS"
    }
}

@Serializable
data class DiagnosticReportExport(
    val schemaVersion: Int = 1,
    val exportedAtMs: Long,
    val totalEvents: Int,
    val countsByOutcome: Map<String, Int>,
    val countsByCategory: Map<String, Int>,
    val countsByAuthState: Map<String, Int>,
    val events: List<DiagnosticTraceRecord>
)

data class DiagnosticTraceContext(
    val traceId: String = newTraceId(),
    val operationType: String = "smart_advisor"
) {
    companion object {
        fun newTraceId(): String =
            "tr-" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

object DiagnosticSanitizer {

    const val HEADER_TRACE_ID = "X-SA-Trace-Id"
    private const val MAX_DESC_CHARS = 220
    private const val MAX_URL_CHARS = 180
    private const val MAX_OP_CHARS = 64

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val SENSITIVE_HEADER_PAIR_REGEX = Regex(
        """(?i)(X-SA-API-Key|Authorization|Proxy-Authorization|X-WP-Nonce|Cookie|Set-Cookie|api[_-]?key|secret[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|password|passwd|passcode|otp|pin|bearer|token|secret)\s*[:=]\s*[^\s,;\]}"']+"""
    )
    private val BEARER_TOKEN_REGEX = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9\-._~+/=]+""")
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val INTL_PHONE_REGEX = Regex("""\+?\d[\d\s\-().]{6,18}\d""")
    private val LONG_DIGIT_REGEX = Regex("""\b\d{6,19}\b""")
    private val IPV4_OPTIONAL_REGEX = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val ADDRESS_HINT_REGEX = Regex(
        """(?i)(?:العنوان|عنوان\s+التوصيل|شارع|حي|عمارة|شقة|address|street|building|apartment)\s*[:\-]\s*[^\n,؛]+"""
    )
    private val QUERY_KEY_VALUE_REGEX = Regex("""([^&=]+)=([^&]*)""")
    private val SENSITIVE_QUERY_KEYS = setOf(
        "key", "api_key", "apikey", "token", "access_token", "refresh_token", "auth",
        "secret", "password", "passwd", "passcode", "otp", "pin", "phone", "mobile",
        "tel", "email", "mail", "address", "name", "sender", "message", "reply",
        "signature", "sig", "nonce", "sa_token", "login_token", "wpnonce"
    )
    private val SAFE_ENGINE_ALLOWLIST = mapOf(
        "newapi" to "NewAPI",
        "new api" to "NewAPI",
        "new_api" to "NewAPI",
        "gateway" to "NewAPI",
        "qwen" to "Qwen 2.5:1.5b",
        "qwen2.5:1.5b" to "Qwen 2.5:1.5b",
        "ollama" to "Qwen 2.5:1.5b",
        "openai" to "OpenAI",
        "gemini" to "Gemini",
        "direct" to "Direct"
    )

    fun sanitizeUrlAndPath(rawUrl: String): String {
        val clean = rawUrl.trim()
        if (clean.isEmpty()) return ""
        return runCatching {
            val uri = URI(clean)
            val scheme = uri.scheme?.lowercase()?.takeIf { it.isNotBlank() }
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().let { rawPath ->
                var p = EMAIL_REGEX.replace(rawPath, "***")
                p = LONG_DIGIT_REGEX.replace(p, "***")
                p
            }
            val safeQuery = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { query ->
                val sanitizedPairs = QUERY_KEY_VALUE_REGEX.findAll(query).map { mr ->
                    val k = mr.groupValues[1].trim()
                    val v = mr.groupValues[2]
                    val lowerK = k.lowercase()
                    if (lowerK in SENSITIVE_QUERY_KEYS ||
                        lowerK.contains("key") ||
                        lowerK.contains("token") ||
                        lowerK.contains("secret") ||
                        lowerK.contains("auth") ||
                        lowerK.contains("pass") ||
                        lowerK.contains("phone") ||
                        lowerK.contains("mail") ||
                        lowerK.contains("msg")
                    ) {
                        "$k=***"
                    } else {
                        val safeV = scrubDiagnosticText(v, null).take(32)
                        "$k=$safeV"
                    }
                }.toList()
                if (sanitizedPairs.isEmpty()) "" else "?" + sanitizedPairs.joinToString("&")
            }.orEmpty()

            buildString {
                if (scheme != null && host != null) {
                    append(scheme).append("://").append(host).append(port)
                }
                append(path.ifBlank { "/" })
                append(safeQuery)
            }.take(MAX_URL_CHARS)
        }.getOrElse {
            scrubDiagnosticText(clean.substringBefore('?'), null).take(MAX_URL_CHARS)
        }
    }

    fun scrubDiagnosticText(
        raw: String,
        vault: SecretVault? = null,
        extraSecrets: Collection<String> = emptyList()
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        var clean = SensitiveDataRedactor.scrub(trimmed, vault)
        extraSecrets.asSequence()
            .map { it.trim() }
            .filter { it.length >= 4 }
            .sortedByDescending { it.length }
            .forEach { secret ->
                clean = clean.replace(secret, "***REDACTED***")
            }
        clean = BEARER_TOKEN_REGEX.replace(clean) { mr -> "${mr.groupValues[1]} ***REDACTED***" }
        clean = SENSITIVE_HEADER_PAIR_REGEX.replace(clean) { mr -> "${mr.groupValues[1]}=***REDACTED***" }
        clean = EMAIL_REGEX.replace(clean, "***@***")
        clean = ADDRESS_HINT_REGEX.replace(clean, "[عنوان محذوف]")
        clean = INTL_PHONE_REGEX.replace(clean, "***PHONE***")
        clean = LONG_DIGIT_REGEX.replace(clean, "***")
        clean = clean.replace('\r', ' ').replace('\n', ' ').replace(Regex("""\s{2,}"""), " ").trim()
        // Never allow leaked IP addresses in user-facing diagnostic details if unintended? Keep host names only, redact raw private tokens.
        return clean.take(MAX_DESC_CHARS)
    }

    fun sanitizeTraceId(raw: String?): String {
        val cleaned = raw.orEmpty().trim().replace(Regex("""[^A-Za-z0-9\-_]"""), "")
        return if (cleaned.isNotBlank()) cleaned.take(40) else DiagnosticTraceContext.newTraceId()
    }

    fun sanitizeOperationType(raw: String): String =
        raw.trim().replace(Regex("""[^A-Za-z0-9_.\-]"""), "_").take(MAX_OP_CHARS).ifBlank { "unknown_op" }

    fun sanitizeHttpMethod(raw: String): String =
        raw.trim().uppercase().replace(Regex("""[^A-Z]"""), "").take(12).ifBlank { "LOCAL" }

    fun sanitizeErrorCode(raw: String?): String {
        val clean = raw.orEmpty().trim().replace(Regex("""[^A-Za-z0-9_.\-]"""), "")
        return clean.take(64)
    }

    fun normalizeEngineLabel(raw: String?): String {
        val trimmed = raw.orEmpty().trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        SAFE_ENGINE_ALLOWLIST.entries.firstOrNull { (k, _) ->
            lower == k || lower.contains(k)
        }?.let { return it.value }
        return scrubDiagnosticText(trimmed, null).take(48)
    }

    fun sanitizeWpTraceSteps(steps: List<String>): List<String> =
        steps.mapNotNull { step ->
            val clean = step.trim().replace(Regex("""[^A-Za-z0-9_:\-.]"""), "")
            clean.takeIf { it.isNotEmpty() }?.take(64)
        }.take(20)

    fun exportSanitizedReportJson(
        records: List<DiagnosticTraceRecord>,
        vault: SecretVault? = null,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String {
        val scrubbedEvents = records.map { r ->
            r.copy(
                traceId = sanitizeTraceId(r.traceId),
                operationType = sanitizeOperationType(r.operationType),
                httpMethod = sanitizeHttpMethod(r.httpMethod),
                sanitizedUrlPath = sanitizeUrlAndPath(r.sanitizedUrlPath),
                stageDetailCode = sanitizeErrorCode(r.stageDetailCode),
                errorCode = sanitizeErrorCode(r.errorCode),
                shortDescription = scrubDiagnosticText(r.shortDescription, vault),
                engineUsed = normalizeEngineLabel(r.engineUsed),
                wpServerTraceSteps = sanitizeWpTraceSteps(r.wpServerTraceSteps)
            )
        }
        val report = DiagnosticReportExport(
            schemaVersion = 1,
            exportedAtMs = exportedAtMs,
            totalEvents = scrubbedEvents.size,
            countsByOutcome = scrubbedEvents.groupingBy { it.outcome.name }.eachCount(),
            countsByCategory = scrubbedEvents.groupingBy { it.category.name }.eachCount(),
            countsByAuthState = scrubbedEvents.groupingBy { it.authState.name }.eachCount(),
            events = scrubbedEvents
        )
        return prettyJson.encodeToString(report)
    }
}
