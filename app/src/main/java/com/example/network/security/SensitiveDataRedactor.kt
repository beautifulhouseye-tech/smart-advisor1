package com.example.network.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SensitiveDataRedactor {
    private const val REDACTED_URL = "[رابط دخول محمي محذوف]"
    private const val REDACTED_TOKEN = "***REDACTED_TOKEN***"

    private val BEARER_REGEX = Regex("""(?i)Bearer\s+[A-Za-z0-9\-._~+/]+=*""")
    private val HEADER_SECRET_REGEX = Regex("""(?i)(X-SA-API-Key|Authorization|api[_-]?key|token|secret)\s*[:=]\s*[^\s,;]+""")
    private val QUERY_SECRET_REGEX = Regex("""(?i)([?&](?:token|key|api_key|secret|auth|login_token|sa_token)=)[^&\s]+""")
    private val MAGIC_LINK_URL_REGEX = Regex("""https?://[^\s]*(?:magic[_-]?login|sa_token|login_token|token=|auth=)[^\s]*""", RegexOption.IGNORE_CASE)

    fun scrub(text: String, vault: SecretVault? = null): String {
        if (text.isBlank()) return text
        var clean = vault?.redact(text) ?: text
        clean = MAGIC_LINK_URL_REGEX.replace(clean, REDACTED_URL)
        clean = QUERY_SECRET_REGEX.replace(clean) { mr -> "${mr.groupValues[1]}$REDACTED_TOKEN" }
        clean = BEARER_REGEX.replace(clean, "Bearer $REDACTED_TOKEN")
        clean = HEADER_SECRET_REGEX.replace(clean) { mr -> "${mr.groupValues[1]}=$REDACTED_TOKEN" }
        return clean
    }

    fun containsSensitivePaymentOrCredential(text: String): Boolean {
        val lower = text.lowercase()
        val hasPasswordKeyword = lower.contains("كلمة المرور") ||
            lower.contains("كلمة السر") ||
            lower.contains("باسورد") ||
            lower.contains("password") ||
            lower.contains("otp") ||
            lower.contains("رمز التحقق")
        val hasPaymentKeyword = lower.contains("cvv") ||
            lower.contains("بطاقة ائتمان") ||
            lower.contains("رقم البطاقة") ||
            lower.contains("iban") ||
            CARD_NUMBER_REGEX.containsMatchIn(text)
        return hasPasswordKeyword || hasPaymentKeyword ||
            MAGIC_LINK_URL_REGEX.containsMatchIn(text)
    }

    private val CARD_NUMBER_REGEX = Regex("""\b(?:\d[ -]*?){13,19}\b""")
}

object OutboxCrypto {
    private const val VAULT_MASTER_SEED_KEY = "memory_outbox_aes_key_b64"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(plainText: String, vault: SecretVault): String {
        val keyBytes = getOrCreateKeyBytes(vault)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return "v1:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String, vault: SecretVault): String? {
        if (!encoded.startsWith("v1:")) return null
        val keyBytes = getOrCreateKeyBytes(vault)
        return runCatching {
            val combined = Base64.decode(encoded.removePrefix("v1:"), Base64.NO_WRAP)
            if (combined.size <= IV_BYTES) return null
            val iv = combined.copyOfRange(0, IV_BYTES)
            val cipherBytes = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateKeyBytes(vault: SecretVault): ByteArray {
        val existing = vault.get(VAULT_MASTER_SEED_KEY)
        if (existing.isNotBlank()) {
            val decoded = runCatching { Base64.decode(existing, Base64.NO_WRAP) }.getOrNull()
            if (decoded != null && decoded.size == 32) return decoded
        }
        val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        vault.put(VAULT_MASTER_SEED_KEY, Base64.encodeToString(keyBytes, Base64.NO_WRAP))
        return MessageDigest.getInstance("SHA-256").digest(keyBytes).let { keyBytes }
    }
}
