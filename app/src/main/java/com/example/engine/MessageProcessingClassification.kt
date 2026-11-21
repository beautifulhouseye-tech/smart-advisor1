package com.example.engine

import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.UnunderstoodFallbackMode

enum class MessageProcessingStatus(val labelAr: String) {
    UNDERSTOOD("تم فهم الطلب"),
    UNKNOWN_INTENT("نية غير مفهومة"),
    LOW_CONFIDENCE("ثقة غير كافية"),
    NEEDS_CLARIFICATION("يحتاج توضيحاً"),
    SERVICE_ERROR("خطأ تقني في الخدمة"),
    TIMEOUT("انتهاء مهلة الاتصال"),
    INVALID_RESPONSE("استجابة غير صالحة"),
    DUPLICATE_MESSAGE("رسالة مكررة");

    fun isUnunderstoodCategory(): Boolean =
        this == UNKNOWN_INTENT || this == LOW_CONFIDENCE || this == NEEDS_CLARIFICATION

    fun isTechnicalFailureCategory(): Boolean =
        this == SERVICE_ERROR || this == TIMEOUT || this == INVALID_RESPONSE
}

enum class EngineFailureCategory {
    NONE,
    SERVICE_ERROR,
    TIMEOUT,
    INVALID_RESPONSE;

    fun toProcessingStatus(): MessageProcessingStatus = when (this) {
        TIMEOUT -> MessageProcessingStatus.TIMEOUT
        INVALID_RESPONSE -> MessageProcessingStatus.INVALID_RESPONSE
        SERVICE_ERROR -> MessageProcessingStatus.SERVICE_ERROR
        NONE -> MessageProcessingStatus.SERVICE_ERROR
    }
}

data class MessageProcessingClassification(
    val status: MessageProcessingStatus,
    val confidence: Double = 0.0,
    val detectedIntent: String = "",
    val finalReply: String? = null,
    val source: String = "",
    val failureReason: String = "",
    val suppressedBySetting: Boolean = false,
    val fallbackModeApplied: UnunderstoodFallbackMode? = null
)

object UnunderstoodIntentPolicy {

    const val MIN_CONFIDENCE_THRESHOLD = 0.45
    const val AMBIGUOUS_CONFIDENCE_THRESHOLD = 0.55

    private val UNKNOWN_INTENT_TOKENS = setOf(
        "unknown",
        "unknown_intent",
        "ununderstood",
        "unclear",
        "nonsense",
        "noise",
        "invalid_intent",
        "out_of_scope",
        "other_unknown"
    )

    private val LOW_CONFIDENCE_INTENT_TOKENS = setOf(
        "low_confidence",
        "uncertain"
    )

    private val CLARIFICATION_INTENT_TOKENS = setOf(
        "needs_clarification",
        "clarification_needed",
        "ambiguous"
    )

    private val CONTEXTUAL_FOLLOWUP_TOKENS = setOf(
        "نعم", "اي", "إي", "ايوه", "أيوه", "لا", "كلا",
        "أكثر", "اكثر", "المزيد", "غيره", "غيرها", "ثاني",
        "الأول", "الاول", "الثاني", "الثالث", "قارن", "مقارنة",
        "المنعش", "الهادئ", "الرسمي", "الرياضي", "موافق", "تم",
        "yes", "no", "more", "compare", "first", "second"
    )

    private val NON_INFORMATIVE_UTTERANCES = setOf(
        "مم", "ممم", "مممم", "ههه", "هههه", "مدري", "ما ادري",
        "مدري ايش", "هذا هو", "مممم هذا هو", "مدري شقول", "؟؟", "؟؟؟", "??", "???"
    )

    fun classifyIntentToken(rawIntent: String): MessageProcessingStatus? {
        val norm = rawIntent.trim().lowercase().replace('-', '_')
        if (norm.isBlank()) return null
        return when {
            norm in UNKNOWN_INTENT_TOKENS -> MessageProcessingStatus.UNKNOWN_INTENT
            norm in LOW_CONFIDENCE_INTENT_TOKENS -> MessageProcessingStatus.LOW_CONFIDENCE
            norm in CLARIFICATION_INTENT_TOKENS -> MessageProcessingStatus.NEEDS_CLARIFICATION
            else -> null
        }
    }

    fun isExplicitUnknownDecision(
        intent: String,
        confidence: Double,
        isStructuredJson: Boolean,
        hasToolRequest: Boolean,
        reply: String
    ): Boolean {
        if (classifyIntentToken(intent) != null) return true
        if (isStructuredJson &&
            !hasToolRequest &&
            confidence in 0.0..0.44 &&
            reply.isBlank()
        ) {
            return true
        }
        return false
    }

    fun isMeaninglessNoiseInput(
        messageText: String,
        hasActiveContext: Boolean
    ): Boolean {
        val cleaned = messageText.trim()
        if (cleaned.isEmpty()) return true
        val stripped = cleaned
            .replace(Regex("""[\p{Punct}؟،؛…\s]+"""), " ")
            .trim()
            .lowercase()
        if (stripped.isEmpty()) return true
        if (hasActiveContext && isContextualFollowUp(cleaned)) return false
        if (stripped in NON_INFORMATIVE_UTTERANCES) return true
        // Repeated single character like "مممممم" or "اااا"
        if (stripped.length >= 2 && stripped.toSet().size == 1) return true
        return false
    }

    fun isContextualFollowUp(messageText: String): Boolean {
        val normalized = messageText.trim()
            .trimEnd('؟', '?', '.', '!', '،')
            .lowercase()
        if (normalized.isEmpty()) return false
        return normalized in CONTEXTUAL_FOLLOWUP_TOKENS ||
            CONTEXTUAL_FOLLOWUP_TOKENS.any { token ->
                normalized == token ||
                    normalized.startsWith("$token ") ||
                    normalized.endsWith(" $token")
            }
    }

    fun resolveUnunderstoodReply(
        aiSettings: AiSettingsState,
        status: MessageProcessingStatus,
        suppressForLoop: Boolean
    ): Pair<String?, UnunderstoodFallbackMode?> {
        if (aiSettings.suppressReplyWhenUnunderstood || suppressForLoop) {
            return null to UnunderstoodFallbackMode.NO_REPLY
        }
        return when (aiSettings.ununderstoodFallbackMode) {
            UnunderstoodFallbackMode.NO_REPLY -> null to UnunderstoodFallbackMode.NO_REPLY
            UnunderstoodFallbackMode.ASK_CLARIFICATION ->
                AiSettingsState.DEFAULT_CLARIFICATION_QUESTION to UnunderstoodFallbackMode.ASK_CLARIFICATION
            UnunderstoodFallbackMode.CUSTOM_REPLY ->
                aiSettings.effectiveUnunderstoodCustomReply() to UnunderstoodFallbackMode.CUSTOM_REPLY
        }
    }
}
