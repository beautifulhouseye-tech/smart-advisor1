package com.example.engine

import android.util.Log
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class DiagnosticEventTag {
    UNKNOWN_INTENT,
    LOW_CONFIDENCE,
    SERVICE_ERROR,
    TIMEOUT,
    FALLBACK_USED,
    REPLY_SUPPRESSED,
    DUPLICATE_BLOCKED
}

data class DiagnosticEvent(
    val timestampMs: Long,
    val tag: DiagnosticEventTag,
    val conversationId: String = "",
    val messageId: String = "",
    val detectedStatus: String = "",
    val decision: String = "",
    val errorSource: String = "",
    val detail: String = ""
)

@Singleton
class SafeDiagnosticLogger(
    private val secretVault: SecretVault? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(secretVault: SecretVault) : this(secretVault = secretVault, clockMs = { System.currentTimeMillis() })

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(MAX_EVENTS)
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun log(
        tag: DiagnosticEventTag,
        conversationId: String = "",
        messageId: String = "",
        detectedStatus: String = "",
        decision: String = "",
        errorSource: String = "",
        detail: String = ""
    ): DiagnosticEvent {
        val safeConv = scrubSafe(conversationId, maskDigits = false)
        val safeMsgId = scrubSafe(messageId, maskDigits = false)
        val safeStatus = scrubSafe(detectedStatus, maskDigits = false)
        val safeDecision = scrubSafe(decision, maskDigits = true)
        val safeSource = scrubSafe(errorSource, maskDigits = true)
        val safeDetail = scrubSafe(detail, maskDigits = true)

        val event = DiagnosticEvent(
            timestampMs = clockMs(),
            tag = tag,
            conversationId = safeConv,
            messageId = safeMsgId,
            detectedStatus = safeStatus,
            decision = safeDecision,
            errorSource = safeSource,
            detail = safeDetail
        )

        synchronized(lock) {
            if (buffer.size >= MAX_EVENTS) {
                buffer.removeFirst()
            }
            buffer.addLast(event)
            _events.value = buffer.toList()
        }

        val logLine = buildString {
            append('[').append(tag.name).append("] ")
            if (safeConv.isNotBlank()) append("conv=").append(safeConv).append(' ')
            if (safeMsgId.isNotBlank()) append("msgId=").append(safeMsgId).append(' ')
            if (safeStatus.isNotBlank()) append("status=").append(safeStatus).append(' ')
            if (safeDecision.isNotBlank()) append("decision=").append(safeDecision).append(' ')
            if (safeSource.isNotBlank()) append("source=").append(safeSource).append(' ')
            if (safeDetail.isNotBlank()) append("detail=").append(safeDetail)
        }.trim()

        runCatching { Log.i(LOG_TAG, logLine) }
        return event
    }

    fun recentEvents(): List<DiagnosticEvent> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _events.value = emptyList()
        }
    }

    private fun scrubSafe(raw: String, maskDigits: Boolean): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        var clean = SensitiveDataRedactor.scrub(trimmed, secretVault)
        clean = EXTRA_SECRET_REGEX.replace(clean) { mr -> "${mr.groupValues[1]}=***REDACTED***" }
        clean = EMAIL_REGEX.replace(clean, "***@***")
        if (maskDigits) {
            clean = LONG_NUMBER_REGEX.replace(clean, "***")
        }
        return clean.take(MAX_FIELD_LENGTH)
    }

    companion object {
        private const val LOG_TAG = "AutoMateDiag"
        private const val MAX_EVENTS = 200
        private const val MAX_FIELD_LENGTH = 240

        private val EXTRA_SECRET_REGEX = Regex(
            """(?i)(password|passwd|passcode|otp|pin|secret_key|access_token|refresh_token|api_key|bearer)\s*[:=]\s*[^\s,;]+"""
        )
        private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{7,16}\b""")
    }
}
