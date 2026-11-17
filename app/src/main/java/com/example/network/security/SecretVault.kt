package com.example.network.security

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.local.entity.HeaderItem
import dagger.hilt.android.qualifiers.ApplicationContext

open class SecretVault(
    context: Context
) {
    private var overridePrefs: SharedPreferences? = null

    @VisibleForTesting
    internal constructor(
        context: Context,
        testPrefs: SharedPreferences
    ) : this(context) {
        overridePrefs = testPrefs
    }

    private val prefs: SharedPreferences by lazy {
        overridePrefs ?: run {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                VAULT_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    open fun put(ref: String, value: String) {
        val cleanRef = ref.trim()
        if (cleanRef.isEmpty()) return
        prefs.edit(commit = true) {
            putString(cleanRef, value)
        }
    }

    open fun get(ref: String): String {
        val cleanRef = ref.trim()
        if (cleanRef.isEmpty()) return ""
        return prefs.getString(cleanRef, "").orEmpty()
    }

    open fun remove(ref: String) {
        val cleanRef = ref.trim()
        if (cleanRef.isEmpty()) return
        prefs.edit(commit = true) {
            remove(cleanRef)
        }
    }

    open fun redact(text: String): String {
        if (text.isEmpty()) return text
        val secrets = prefs.all.values
            .filterIsInstance<String>()
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }

        var redacted = text
        for (secret in secrets) {
            redacted = redacted.replace(secret, REDACTED_MARKER)
        }
        return redacted
    }

    open fun resolveHeaders(headers: List<HeaderItem>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (header in headers) {
            val headerKey = header.key.trim()
            if (headerKey.isEmpty()) continue
            val vaultValue = get(header.valueRef)
            val resolvedValue = if (header.isSecret) {
                vaultValue
            } else {
                vaultValue.ifEmpty { header.valueRef }
            }
            if (resolvedValue.isNotEmpty()) {
                result[headerKey] = resolvedValue
            }
        }
        return result
    }

    companion object {
        private const val VAULT_FILE_NAME = "automate_secret_vault"
        const val REDACTED_MARKER = "***REDACTED***"
    }
}
