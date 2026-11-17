package com.example.engine

object ConversationKey {
    fun build(
        packageName: String,
        senderName: String,
        phone: String = "",
        groupName: String? = null,
        isGroup: Boolean = false
    ): String {
        val cleanPkg = packageName.trim().lowercase().ifBlank { "unknown" }
        return if (isGroup) {
            val cleanGroup = (groupName?.takeIf { it.isNotBlank() } ?: senderName)
                .let(::normalizeToken)
                .ifBlank { "unknown_group" }
            "$cleanPkg|group|$cleanGroup"
        } else {
            val normalizedPhone = normalizePhone(phone)
            val peerId = normalizedPhone.ifBlank { normalizeToken(senderName) }
                .ifBlank { "unknown_peer" }
            "$cleanPkg|dm|$peerId"
        }
    }

    fun buildCustomerKey(
        phone: String = "",
        senderName: String = "",
        isGroup: Boolean = false
    ): String {
        if (isGroup) return ""
        val cleanPhone = normalizePhone(phone)
        if (cleanPhone.isNotBlank()) return "phone:$cleanPhone"
        val cleanName = normalizeToken(senderName)
        return if (cleanName.isNotBlank()) "name:$cleanName" else ""
    }

    fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }.trim()
        return if (digits.count { it.isDigit() } >= 6) digits else ""
    }

    private fun normalizeToken(raw: String): String {
        return raw.replace(BIDI_REGEX, "")
            .trim()
            .replace(WHITESPACE_REGEX, " ")
            .lowercase()
    }

    private val BIDI_REGEX = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
