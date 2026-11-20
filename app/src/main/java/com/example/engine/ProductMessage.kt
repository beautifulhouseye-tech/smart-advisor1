package com.example.engine

import java.net.URI

/**
 * Grounded WooCommerce / Smart Advisor product payload used for single-message
 * WhatsApp image + caption rendering and clean text fallback.
 */
data class ProductMessage(
    val productId: Long? = null,
    val storeName: String = "",
    val imageUrl: String? = null,
    val productName: String = "",
    val price: String? = null,
    val currency: String? = null,
    val shortDescription: String? = null,
    val productUrl: String? = null
) {
    fun hasValidImageUrl(): Boolean = isValidProductImageUrl(imageUrl)

    fun hasValidProductUrl(): Boolean = isValidWebUrl(productUrl)

    fun canSendAsImage(): Boolean = productName.trim().isNotEmpty() && hasValidImageUrl()

    /**
     * Exact grounded RTL-safe WhatsApp caption format:
     *
     * [اسم المتجر] - [اسم المنتج]
     *
     * السعر: [السعر] [العملة]
     *
     * [الوصف المختصر]
     *
     * للمشاهدة والطلب:
     * [رابط المنتج]
     *
     * Blank or unverified fields are cleanly omitted without ever inventing data.
     */
    fun buildCaption(maxDescriptionLength: Int = DEFAULT_MAX_DESCRIPTION_CHARS): String {
        val cleanStore = normalizeInlineText(storeName)
        val cleanName = normalizeInlineText(productName)
        val header = when {
            cleanStore.isNotEmpty() && cleanName.isNotEmpty() -> "$cleanStore - $cleanName"
            cleanName.isNotEmpty() -> cleanName
            cleanStore.isNotEmpty() -> cleanStore
            else -> ""
        }

        val priceBlock = formatPriceLine(price, currency)?.let { "السعر: $it" }

        val descBlock = truncateDescription(
            raw = normalizeBlockText(shortDescription.orEmpty()),
            maxChars = maxDescriptionLength
        )

        val urlBlock = productUrl?.trim()
            ?.takeIf { isValidWebUrl(it) }
            ?.let { "للمشاهدة والطلب:\n$it" }

        return listOfNotNull(
            header.takeIf { it.isNotEmpty() },
            priceBlock,
            descBlock.takeIf { it.isNotEmpty() },
            urlBlock
        ).joinToString("\n\n")
    }

    /**
     * Clean text fallback preserving full grounded product info and URL when image delivery
     * is unavailable or fails prior to Send.
     */
    fun buildTextFallbackReply(): String = buildCaption()

    companion object {
        const val DEFAULT_MAX_DESCRIPTION_CHARS = 220

        private val PLACEHOLDER_MARKERS = listOf(
            "woocommerce-placeholder",
            "/placeholder.",
            "placeholder.png",
            "placeholder.jpg",
            "placeholder.jpeg",
            "placeholder.webp",
            "no-image",
            "no_image",
            "default-product"
        )

        private val CURRENCY_TOKENS = listOf(
            "ر.س", "ريال", "﷼", "sar", "usd", "$", "د.إ", "درهم", "aed",
            "ر.ق", "qar", "د.ك", "kwd", "د.ب", "bhd", "ر.ع", "omr",
            "ج.م", "egp", "ر.ي", "yer", "eur", "€", "£", "gbp"
        )

        fun isValidProductImageUrl(raw: String?): Boolean {
            val clean = raw?.trim().orEmpty()
            if (clean.isEmpty()) return false
            val lower = clean.lowercase()
            if (PLACEHOLDER_MARKERS.any { lower.contains(it) }) return false
            val uri = runCatching { URI(clean) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.trim().orEmpty()
            return scheme == "https" && host.isNotEmpty()
        }

        fun isValidWebUrl(raw: String?): Boolean {
            val clean = raw?.trim().orEmpty()
            if (clean.isEmpty()) return false
            val uri = runCatching { URI(clean) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.trim().orEmpty()
            return (scheme == "https" || scheme == "http") && host.isNotEmpty()
        }

        fun formatPriceLine(rawPrice: String?, rawCurrency: String?): String? {
            val cleanPrice = normalizeInlineText(rawPrice.orEmpty())
                .replace("&#8207;", "")
                .replace("\u200F", "")
                .replace("\u200E", "")
                .trim()
            if (cleanPrice.isEmpty()) return null
            val lowerPrice = cleanPrice.lowercase()
            if (lowerPrice == "0" || lowerPrice == "0.0" || lowerPrice == "0.00" || lowerPrice == "غير محدد") {
                return null
            }
            val cleanCurrency = normalizeInlineText(rawCurrency.orEmpty())
            if (cleanCurrency.isEmpty()) return cleanPrice
            val alreadyHasCurrency = lowerPrice.contains(cleanCurrency.lowercase()) ||
                CURRENCY_TOKENS.any { lowerPrice.contains(it) }
            return if (alreadyHasCurrency) cleanPrice else "$cleanPrice $cleanCurrency"
        }

        fun normalizeInlineText(raw: String): String {
            return stripHtml(raw)
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun normalizeBlockText(raw: String): String {
            return stripHtml(raw)
                .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        fun stripHtml(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#8211;", "-")
                .replace("&#8212;", "-")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-")
                .replace(Regex("&#(\\d+);")) { m ->
                    val code = m.groupValues[1].toIntOrNull()
                    if (code != null && code in 32..0x10FFFF) {
                        String(Character.toChars(code))
                    } else {
                        " "
                    }
                }
        }

        private fun truncateDescription(raw: String, maxChars: Int): String {
            if (raw.isEmpty() || raw.length <= maxChars) return raw
            val limit = (maxChars - 1).coerceAtLeast(24)
            val slice = raw.take(limit)
            val lastSpace = slice.lastIndexOf(' ')
            val clean = if (lastSpace > limit / 2) slice.substring(0, lastSpace) else slice
            return "${clean.trimEnd('.', '،', ',', ' ')}…"
        }
    }
}
