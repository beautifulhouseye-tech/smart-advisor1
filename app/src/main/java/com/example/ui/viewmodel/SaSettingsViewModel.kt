package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.DiagnosticNetworkRecorder
import com.example.engine.DiagnosticTraceContext
import com.example.engine.SafeDiagnosticLogger
import com.example.network.ResponseOutcomeAnalyzer
import com.example.network.ResponseOutcomeAnalyzer.ResponseStatus
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorAuthResolver
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.ui.components.UnifiedTestResultCardModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SaSettingsViewModel @Inject constructor(
    private val prefs: SmartAdvisorPrefs,
    private val client: SmartAdvisorClient,
    private val vault: SecretVault,
    private val diagnosticLogger: SafeDiagnosticLogger? = null
) : ViewModel() {
    constructor(
        prefs: SmartAdvisorPrefs,
        client: SmartAdvisorClient,
        vault: SecretVault
    ) : this(prefs = prefs, client = client, vault = vault, diagnosticLogger = null)

    private val _baseUrl = MutableStateFlow(prefs.baseUrl.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL })
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(
        SmartAdvisorAuthResolver.resolveEffectiveKeyRef(vault, prefs.apiKeyRef)
    )
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    data class TestResult(
        val success: Boolean,
        val statusCode: Int,
        val body: String,
        val error: String?,
        val durationMs: Long,
        val outcomeStatus: ResponseStatus = if (success) ResponseStatus.SUCCESS else ResponseStatus.FAILURE,
        val title: String = if (success) "الاتصال ناجح" else "فشل الاتصال",
        val serverMessage: String = if (success) body else (error ?: "خطأ غير معروف"),
        val reasonSummary: String = "",
        val guidance: String = "",
        val rawSnippet: String = "",
        val truncated: Boolean = false
    ) {
        fun toUnifiedCardModel(): UnifiedTestResultCardModel = UnifiedTestResultCardModel(
            status = outcomeStatus,
            title = title,
            statusCode = statusCode,
            durationMs = durationMs,
            serverMessage = serverMessage,
            sourceLabel = "smart_advisor",
            trustLabel = "متجر Smart Advisor الموثوق (HTTPS)",
            reasonSummary = reasonSummary,
            guidance = guidance,
            rawSnippet = rawSnippet,
            truncated = truncated
        )
    }

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    fun updateBaseUrl(url: String) {
        _baseUrl.value = url
    }

    fun restoreDefaultUrl() {
        val effectiveKeyRef = SmartAdvisorAuthResolver.resolveEffectiveKeyRef(vault, _apiKey.value)
        _apiKey.value = effectiveKeyRef
        _baseUrl.value = SmartAdvisorPrefs.DEFAULT_BASE_URL
        prefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = effectiveKeyRef,
            enabled = _enabled.value
        )
        _saveStatus.value = "تمت استعادة الرابط الافتراضي لـ Smart Advisor"
    }

    fun updateApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            vault.remove(SA_API_KEY_REF)
            _apiKey.value = ""
        } else {
            vault.put(SA_API_KEY_REF, trimmed)
            _apiKey.value = SA_API_KEY_REF
        }
    }

    fun clearApiKey() {
        vault.remove(SA_API_KEY_REF)
        if (_apiKey.value.isNotBlank() && _apiKey.value != SA_API_KEY_REF) {
            vault.remove(_apiKey.value)
        }
        _apiKey.value = ""
        prefs.saveCredentials(
            baseUrl = _baseUrl.value.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL },
            apiKeyRef = "",
            enabled = _enabled.value
        )
        _saveStatus.value = "تم مسح مفتاح API المحفوظ"
    }

    fun toggleEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    fun setEnabledAndSave(enabled: Boolean) {
        _enabled.value = enabled
        val effectiveUrl = _baseUrl.value.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL }
        val effectiveKeyRef = SmartAdvisorAuthResolver.resolveEffectiveKeyRef(vault, _apiKey.value)
        _baseUrl.value = effectiveUrl
        _apiKey.value = effectiveKeyRef
        prefs.saveCredentials(
            baseUrl = effectiveUrl,
            apiKeyRef = effectiveKeyRef,
            enabled = enabled
        )
        _saveStatus.value = if (enabled) {
            "تم تفعيل المحرك الرئيسي Smart Advisor"
        } else {
            "تم إيقاف المحرك الرئيسي Smart Advisor"
        }
    }

    fun hasStoredApiKey(): Boolean {
        val ref = SmartAdvisorAuthResolver.resolveEffectiveKeyRef(vault, _apiKey.value)
        return ref.isNotBlank() && vault.get(ref).isNotEmpty()
    }

    fun save() {
        val effectiveUrl = _baseUrl.value.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL }
        val effectiveKeyRef = SmartAdvisorAuthResolver.resolveEffectiveKeyRef(vault, _apiKey.value)
        prefs.saveCredentials(
            baseUrl = effectiveUrl,
            apiKeyRef = effectiveKeyRef,
            enabled = _enabled.value
        )
        _baseUrl.value = prefs.baseUrl.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL }
        _apiKey.value = SmartAdvisorAuthResolver.resolveEffectiveKeyRef(vault, prefs.apiKeyRef)
        _enabled.value = prefs.enabled
        _saveStatus.value = "تم حفظ إعدادات Smart Advisor بنجاح"
    }

    fun consumeSaveStatus() {
        _saveStatus.value = null
    }

    fun testConnection() {
        if (_isTesting.value) return
        _isTesting.value = true
        _testResult.value = null

        viewModelScope.launch {
            val effectiveUrl = _baseUrl.value.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL }.trim()
            val effectiveKeyRef = SmartAdvisorAuthResolver.resolveEffectiveKeyRef(vault, _apiKey.value)
            _apiKey.value = effectiveKeyRef
            prefs.saveCredentials(
                baseUrl = effectiveUrl,
                apiKeyRef = effectiveKeyRef,
                enabled = _enabled.value
            )

            val traceId = DiagnosticTraceContext.newTraceId()

            // Preflight HTTPS validation
            if (!effectiveUrl.startsWith("https://", ignoreCase = true)) {
                DiagnosticNetworkRecorder.recordPreSendBlocked(
                    logger = diagnosticLogger,
                    traceId = traceId,
                    operationType = "sa_connection_test",
                    method = "POST",
                    rawUrl = effectiveUrl,
                    errorCode = "insecure_transport",
                    reasonAr = "يتطلب Smart Advisor استخدام رابط HTTPS مشفر لحماية مفتاح API."
                )
                val blocked = ResponseOutcomeAnalyzer.fromBlockedAuth(
                    errorCode = "insecure_transport",
                    reasonAr = "يتطلب Smart Advisor استخدام رابط HTTPS مشفر لحماية مفتاح API.",
                    guidanceAr = "غيّر الرابط ليبدأ بـ https:// بدلاً من http:// ثم أعد المحاولة.",
                    sourceLabel = "smart_advisor",
                    trustLabel = "غير آمن"
                )
                _testResult.value = TestResult(
                    success = false,
                    statusCode = blocked.statusCode,
                    body = "",
                    error = blocked.serverMessage,
                    durationMs = 0L,
                    outcomeStatus = blocked.status,
                    title = blocked.title,
                    serverMessage = blocked.serverMessage,
                    reasonSummary = blocked.reasonSummary,
                    guidance = blocked.guidance
                )
                _isTesting.value = false
                return@launch
            }

            // Preflight API Key check: prevent sending unauthenticated request that mimics completion
            if (!hasStoredApiKey()) {
                DiagnosticNetworkRecorder.recordPreSendBlocked(
                    logger = diagnosticLogger,
                    traceId = traceId,
                    operationType = "sa_connection_test",
                    method = "POST",
                    rawUrl = effectiveUrl,
                    errorCode = "sa_missing_key_client",
                    reasonAr = "مفتاح API غير مضبوط في التطبيق (X-SA-API-Key)."
                )
                val blocked = ResponseOutcomeAnalyzer.fromBlockedAuth(
                    errorCode = "sa_missing_key_client",
                    reasonAr = "مفتاح API غير مضبوط في التطبيق (X-SA-API-Key).",
                    guidanceAr = "أدخل مفتاح API السري المنسوخ من إعدادات WordPress في حقل المفتاح أعلاه ثم اضغط اختبار الاتصال.",
                    sourceLabel = "smart_advisor",
                    trustLabel = "بانتظار ضبط المصادقة"
                )
                _testResult.value = TestResult(
                    success = false,
                    statusCode = blocked.statusCode,
                    body = "",
                    error = blocked.serverMessage,
                    durationMs = 0L,
                    outcomeStatus = blocked.status,
                    title = blocked.title,
                    serverMessage = blocked.serverMessage,
                    reasonSummary = blocked.reasonSummary,
                    guidance = blocked.guidance
                )
                _isTesting.value = false
                return@launch
            }

            val result = client.test(traceId = traceId)
            val analyzed = ResponseOutcomeAnalyzer.analyze(
                result = result,
                vault = vault,
                replyKey = "reply",
                expectJson = true,
                maxExcerptChars = 1200,
                isToolPreview = false,
                sourceLabel = "smart_advisor",
                trustLabel = "متجر Smart Advisor الموثوق (HTTPS)"
            )

            _testResult.value = TestResult(
                success = analyzed.isSuccess,
                statusCode = analyzed.statusCode,
                body = analyzed.rawExcerpt.ifBlank { analyzed.serverMessage },
                error = if (analyzed.isSuccess) null else analyzed.serverMessage,
                durationMs = analyzed.latencyMs,
                outcomeStatus = analyzed.status,
                title = analyzed.title,
                serverMessage = analyzed.serverMessage,
                reasonSummary = analyzed.reasonSummary,
                guidance = analyzed.guidance,
                rawSnippet = analyzed.rawExcerpt,
                truncated = analyzed.truncated
            )
            _isTesting.value = false
        }
    }

    companion object {
        const val SA_API_KEY_REF = SmartAdvisorAuthResolver.DEFAULT_API_KEY_REF
    }
}
