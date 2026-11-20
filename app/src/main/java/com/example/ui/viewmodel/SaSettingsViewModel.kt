package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.security.SmartAdvisorPrefs
import com.example.network.HttpTransport
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
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
    private val vault: SecretVault
) : ViewModel() {

    private val _baseUrl = MutableStateFlow(prefs.baseUrl.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL })
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.apiKeyRef)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    data class TestResult(
        val success: Boolean,
        val statusCode: Int,
        val body: String,
        val error: String?,
        val durationMs: Long
    )

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
        _baseUrl.value = SmartAdvisorPrefs.DEFAULT_BASE_URL
        prefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = _apiKey.value,
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

    fun toggleEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    fun setEnabledAndSave(enabled: Boolean) {
        _enabled.value = enabled
        val effectiveUrl = _baseUrl.value.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL }
        _baseUrl.value = effectiveUrl
        prefs.saveCredentials(
            baseUrl = effectiveUrl,
            apiKeyRef = _apiKey.value,
            enabled = enabled
        )
        _saveStatus.value = if (enabled) {
            "تم تفعيل المحرك الرئيسي Smart Advisor"
        } else {
            "تم إيقاف المحرك الرئيسي Smart Advisor"
        }
    }

    fun hasStoredApiKey(): Boolean {
        val ref = _apiKey.value.ifBlank { SA_API_KEY_REF }
        return vault.get(ref).isNotEmpty()
    }

    fun save() {
        val effectiveUrl = _baseUrl.value.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL }
        prefs.saveCredentials(
            baseUrl = effectiveUrl,
            apiKeyRef = _apiKey.value,
            enabled = _enabled.value
        )
        _baseUrl.value = prefs.baseUrl.ifBlank { SmartAdvisorPrefs.DEFAULT_BASE_URL }
        _apiKey.value = prefs.apiKeyRef
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
            prefs.saveCredentials(
                baseUrl = _baseUrl.value,
                apiKeyRef = _apiKey.value,
                enabled = _enabled.value
            )
            val result = client.test()
            _testResult.value = when (result) {
                is HttpTransport.Result.Success -> TestResult(
                    success = true,
                    statusCode = result.code,
                    body = vault.redact(result.body),
                    error = null,
                    durationMs = result.ms
                )
                is HttpTransport.Result.Conflict -> TestResult(
                    success = false,
                    statusCode = result.code,
                    body = vault.redact(result.body),
                    error = "تعارض من الخادم (HTTP 409)",
                    durationMs = result.ms
                )
                is HttpTransport.Result.Failure -> TestResult(
                    success = false,
                    statusCode = result.code,
                    body = "",
                    error = vault.redact(result.error),
                    durationMs = result.ms
                )
            }
            _isTesting.value = false
        }
    }

    companion object {
        const val SA_API_KEY_REF = "smart_advisor_api_key"
    }
}
