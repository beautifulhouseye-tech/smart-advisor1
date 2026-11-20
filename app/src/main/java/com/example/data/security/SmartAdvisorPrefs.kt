package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.network.HttpTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SmartAdvisorCredentials(
    val baseUrl: String = "",
    val apiKeyRef: String = "",
    val enabled: Boolean = false,
    val timeoutSeconds: Int = HttpTransport.DEFAULT_TIMEOUT_SECONDS,
    val maxAttempts: Int = HttpTransport.DEFAULT_MAX_ATTEMPTS
)

@Singleton
class SmartAdvisorPrefs internal constructor(
    context: Context,
    private val overridePrefs: SharedPreferences?,
    private val useRuntimeDefaultUrl: Boolean = (overridePrefs == null)
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null, true)

    private val encryptedPrefs: SharedPreferences by lazy {
        overridePrefs ?: run {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private val _credentialsFlow = MutableStateFlow(readFromPrefs())
    val credentialsFlow: StateFlow<SmartAdvisorCredentials> = _credentialsFlow.asStateFlow()

    var baseUrl: String
        get() = resolveStoredOrDefaultBaseUrl()
        set(value) {
            val normalized = value.trim().trimEnd('/')
            encryptedPrefs.edit(commit = true) { putString(KEY_BASE_URL, normalized) }
            _credentialsFlow.value = readFromPrefs()
        }

    var apiKeyRef: String
        get() = encryptedPrefs.getString(KEY_API_KEY_REF, "").orEmpty()
        set(value) {
            encryptedPrefs.edit(commit = true) { putString(KEY_API_KEY_REF, value.trim()) }
            _credentialsFlow.value = readFromPrefs()
        }

    var enabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            encryptedPrefs.edit(commit = true) { putBoolean(KEY_ENABLED, value) }
            _credentialsFlow.value = readFromPrefs()
        }

    var timeoutSeconds: Int
        get() = encryptedPrefs.getInt(KEY_TIMEOUT_SECONDS, HttpTransport.DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(HttpTransport.MIN_TIMEOUT_SECONDS, HttpTransport.MAX_TIMEOUT_SECONDS)
        set(value) {
            val clamped = value.coerceIn(HttpTransport.MIN_TIMEOUT_SECONDS, HttpTransport.MAX_TIMEOUT_SECONDS)
            encryptedPrefs.edit(commit = true) { putInt(KEY_TIMEOUT_SECONDS, clamped) }
            _credentialsFlow.value = readFromPrefs()
        }

    var maxAttempts: Int
        get() = encryptedPrefs.getInt(KEY_MAX_ATTEMPTS, HttpTransport.DEFAULT_MAX_ATTEMPTS)
            .coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS)
        set(value) {
            val clamped = value.coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS)
            encryptedPrefs.edit(commit = true) { putInt(KEY_MAX_ATTEMPTS, clamped) }
            _credentialsFlow.value = readFromPrefs()
        }

    fun getCredentials(): SmartAdvisorCredentials = readFromPrefs()

    fun saveCredentials(
        baseUrl: String,
        apiKeyRef: String,
        enabled: Boolean,
        timeoutSeconds: Int = this.timeoutSeconds,
        maxAttempts: Int = this.maxAttempts
    ) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val trimmedKey = apiKeyRef.trim()
        val clampedTimeout = timeoutSeconds.coerceIn(
            HttpTransport.MIN_TIMEOUT_SECONDS,
            HttpTransport.MAX_TIMEOUT_SECONDS
        )
        val clampedAttempts = maxAttempts.coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS)
        encryptedPrefs.edit(commit = true) {
            putString(KEY_BASE_URL, normalizedUrl)
            putString(KEY_API_KEY_REF, trimmedKey)
            putBoolean(KEY_ENABLED, enabled)
            putInt(KEY_TIMEOUT_SECONDS, clampedTimeout)
            putInt(KEY_MAX_ATTEMPTS, clampedAttempts)
        }
        _credentialsFlow.value = SmartAdvisorCredentials(
            baseUrl = normalizedUrl,
            apiKeyRef = trimmedKey,
            enabled = enabled,
            timeoutSeconds = clampedTimeout,
            maxAttempts = clampedAttempts
        )
    }

    fun clear() {
        encryptedPrefs.edit(commit = true) { clear() }
        _credentialsFlow.value = readFromPrefs()
    }

    private fun resolveStoredOrDefaultBaseUrl(): String {
        val stored = encryptedPrefs.getString(KEY_BASE_URL, null)
        return when {
            stored != null -> stored
            useRuntimeDefaultUrl -> DEFAULT_BASE_URL
            else -> ""
        }
    }

    private fun readFromPrefs(): SmartAdvisorCredentials {
        return SmartAdvisorCredentials(
            baseUrl = resolveStoredOrDefaultBaseUrl(),
            apiKeyRef = encryptedPrefs.getString(KEY_API_KEY_REF, "").orEmpty(),
            enabled = encryptedPrefs.getBoolean(KEY_ENABLED, false),
            timeoutSeconds = encryptedPrefs.getInt(
                KEY_TIMEOUT_SECONDS,
                HttpTransport.DEFAULT_TIMEOUT_SECONDS
            ).coerceIn(HttpTransport.MIN_TIMEOUT_SECONDS, HttpTransport.MAX_TIMEOUT_SECONDS),
            maxAttempts = encryptedPrefs.getInt(
                KEY_MAX_ATTEMPTS,
                HttpTransport.DEFAULT_MAX_ATTEMPTS
            ).coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS)
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply"
        private const val PREFS_FILE_NAME = "smart_advisor_encrypted_prefs"
        private const val KEY_BASE_URL = "sa_base_url"
        private const val KEY_API_KEY_REF = "sa_api_key_ref"
        private const val KEY_ENABLED = "sa_enabled"
        private const val KEY_TIMEOUT_SECONDS = "sa_timeout_seconds"
        private const val KEY_MAX_ATTEMPTS = "sa_max_attempts"
    }
}
