package com.example.network.smartadvisor

import com.example.data.security.SmartAdvisorPrefs
import com.example.network.security.SecretVault
import java.net.URI

/**
 * Resolves and strictly scopes Smart Advisor API authentication (`X-SA-API-Key`).
 *
 * Security invariants:
 * - Key is attached ONLY via HTTP header `X-SA-API-Key` (never JSON body, URL, or logs).
 * - Key is NEVER auto-attached to arbitrary external Webhook domains.
 * - Target must use HTTPS and match the trusted Smart Advisor store host configured by the user.
 */
object SmartAdvisorAuthResolver {

    const val HEADER_API_KEY = "X-SA-API-Key"
    const val DEFAULT_API_KEY_REF = "smart_advisor_api_key"

    sealed class OutboundAuthDecision {
        data class Proceed(
            val headers: Map<String, String>,
            val isSmartAdvisorTarget: Boolean,
            val usesSmartAdvisorAuth: Boolean
        ) : OutboundAuthDecision()

        data class Blocked(
            val errorCode: String,
            val reasonAr: String,
            val guidanceAr: String
        ) : OutboundAuthDecision()
    }

    fun isSmartAdvisorUrl(url: String): Boolean {
        val uri = parseUri(url) ?: return false
        val path = uri.path?.lowercase().orEmpty()
        return path.contains("/wp-json/smart-advisor/")
    }

    fun resolveStoredApiKey(vault: SecretVault, prefs: SmartAdvisorPrefs?): String {
        val configuredRef = prefs?.apiKeyRef?.trim().orEmpty()
        if (configuredRef.isNotEmpty()) {
            val valFromPref = vault.get(configuredRef).trim()
            if (valFromPref.isNotEmpty()) return valFromPref
        }
        return vault.get(DEFAULT_API_KEY_REF).trim()
    }

    fun resolveEffectiveKeyRef(vault: SecretVault, currentRef: String): String {
        val trimmed = currentRef.trim()
        if (trimmed.isNotEmpty() && vault.get(trimmed).isNotEmpty()) {
            return trimmed
        }
        if (vault.get(DEFAULT_API_KEY_REF).isNotEmpty()) {
            return DEFAULT_API_KEY_REF
        }
        return trimmed
    }

    fun isTrustedSmartAdvisorHost(targetUrl: String, trustedBaseUrl: String): Boolean {
        val targetUri = parseUri(targetUrl) ?: return false
        val trustedUri = parseUri(trustedBaseUrl) ?: return false
        if (!targetUri.scheme.equals("https", ignoreCase = true)) return false
        if (!trustedUri.scheme.equals("https", ignoreCase = true)) return false
        val targetHost = targetUri.host?.trim()?.lowercase().orEmpty()
        val trustedHost = trustedUri.host?.trim()?.lowercase().orEmpty()
        if (targetHost.isEmpty() || trustedHost.isEmpty()) return false
        return targetHost == trustedHost
    }

    fun hasExplicitApiKeyHeader(headers: Map<String, String>): Boolean =
        headers.entries.any { (k, v) ->
            k.equals(HEADER_API_KEY, ignoreCase = true) && v.trim().isNotEmpty()
        }

    fun inspectOutboundAuth(
        targetUrl: String,
        configuredHeaders: Map<String, String>,
        vault: SecretVault,
        prefs: SmartAdvisorPrefs?,
        requireSmartAdvisorKeyWhenTargetMatches: Boolean = true
    ): OutboundAuthDecision {
        val cleanUrl = targetUrl.trim()
        if (cleanUrl.isEmpty()) {
            return OutboundAuthDecision.Blocked(
                errorCode = "missing_url",
                reasonAr = "رابط الخادم غير مهيأ.",
                guidanceAr = "أدخل رابط HTTPS صحيحاً ثم أعد المحاولة."
            )
        }

        val targetUri = parseUri(cleanUrl)
            ?: return OutboundAuthDecision.Blocked(
                errorCode = "invalid_url",
                reasonAr = "صيغة رابط الخادم غير صالحة.",
                guidanceAr = "تأكد من كتابة الرابط بصيغة https://example.com/..."
            )

        val isSaTarget = isSmartAdvisorUrl(cleanUrl)
        val hasExplicitHeader = hasExplicitApiKeyHeader(configuredHeaders)

        if (!isSaTarget) {
            // External arbitrary webhook: never auto-inject Smart Advisor's stored key.
            val hasSensitiveHeader = hasExplicitHeader ||
                configuredHeaders.keys.any { it.equals("Authorization", ignoreCase = true) }
            return OutboundAuthDecision.Proceed(
                headers = configuredHeaders,
                isSmartAdvisorTarget = false,
                usesSmartAdvisorAuth = hasSensitiveHeader
            )
        }

        // Smart Advisor endpoint checks
        if (!targetUri.scheme.equals("https", ignoreCase = true)) {
            return OutboundAuthDecision.Blocked(
                errorCode = "insecure_transport",
                reasonAr = "يتطلب مسار Smart Advisor اتصال HTTPS مشفراً لحماية مفتاح API.",
                guidanceAr = "استبدل http:// بـ https:// في رابط الخادم ولا ترسل مفتاح المصادقة عبر اتصال غير مشفر."
            )
        }

        val trustedBaseUrl = prefs?.baseUrl?.trim().orEmpty()
        val targetHost = targetUri.host?.trim()?.lowercase().orEmpty()
        val trustedUri = parseUri(trustedBaseUrl)
        val trustedHost = trustedUri?.host?.trim()?.lowercase().orEmpty()

        val matchesTrustedStoreHost = trustedHost.isNotEmpty() &&
            trustedUri?.scheme.equals("https", ignoreCase = true) &&
            targetHost == trustedHost

        if (hasExplicitHeader) {
            // Explicit header provided by user on Webhook config.
            // If a trusted SA store host exists and differs, do not silently leak SA vault key;
            // explicit user header is respected for its configured target while guarding redirects.
            return OutboundAuthDecision.Proceed(
                headers = configuredHeaders,
                isSmartAdvisorTarget = true,
                usesSmartAdvisorAuth = true
            )
        }

        if (!matchesTrustedStoreHost) {
            return if (requireSmartAdvisorKeyWhenTargetMatches) {
                OutboundAuthDecision.Blocked(
                    errorCode = "untrusted_smart_advisor_host",
                    reasonAr = "نطاق الرابط لا يطابق نطاق متجر Smart Advisor الموثوق المحفوظ في الإعدادات.",
                    guidanceAr = "تم منع إرسال مفتاح Smart Advisor تلقائياً لحمايته. طابق رابط المتجر في إعدادات Smart Advisor أو أضف ترويسة مخصصة للخادم المستهدف."
                )
            } else {
                OutboundAuthDecision.Proceed(
                    headers = configuredHeaders,
                    isSmartAdvisorTarget = true,
                    usesSmartAdvisorAuth = false
                )
            }
        }

        val resolvedKey = resolveStoredApiKey(vault, prefs)
        if (resolvedKey.isEmpty()) {
            return if (requireSmartAdvisorKeyWhenTargetMatches) {
                OutboundAuthDecision.Blocked(
                    errorCode = "sa_missing_key_client",
                    reasonAr = "مفتاح المصادقة (X-SA-API-Key) غير محفوظ في التطبيق.",
                    guidanceAr = "افتح شاشة إعدادات Smart Advisor وأدخل مفتاح API السري واحفظه، أو أضف ترويسة سرية باسم X-SA-API-Key في إعدادات Webhook."
                )
            } else {
                OutboundAuthDecision.Proceed(
                    headers = configuredHeaders,
                    isSmartAdvisorTarget = true,
                    usesSmartAdvisorAuth = false
                )
            }
        }

        val enrichedHeaders = LinkedHashMap(configuredHeaders)
        enrichedHeaders[HEADER_API_KEY] = resolvedKey
        return OutboundAuthDecision.Proceed(
            headers = enrichedHeaders,
            isSmartAdvisorTarget = true,
            usesSmartAdvisorAuth = true
        )
    }

    private fun parseUri(raw: String): URI? =
        runCatching { URI(raw.trim()) }.getOrNull()?.takeIf { !it.host.isNullOrBlank() }
}
