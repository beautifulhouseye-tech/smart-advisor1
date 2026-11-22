package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.preferences.SettingsDataStore
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
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
class SafeDiagnosticLogger internal constructor(
    private val secretVault: SecretVault? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    context: Context? = null,
    initialDetailedEnabled: Boolean = true,
    private val settingsDataStore: SettingsDataStore? = null
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        secretVault: SecretVault,
        settingsDataStore: SettingsDataStore
    ) : this(
        secretVault = secretVault,
        clockMs = { System.currentTimeMillis() },
        context = context,
        initialDetailedEnabled = false,
        settingsDataStore = settingsDataStore
    )

    constructor(secretVault: SecretVault) : this(
        secretVault = secretVault,
        clockMs = { System.currentTimeMillis() },
        context = null,
        initialDetailedEnabled = true,
        settingsDataStore = null
    )

    constructor(
        secretVault: SecretVault?,
        clockMs: () -> Long
    ) : this(
        secretVault = secretVault,
        clockMs = clockMs,
        context = null,
        initialDetailedEnabled = true,
        settingsDataStore = null
    )

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(MAX_EVENTS)
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    private val encryptedStore = EncryptedDiagnosticStore(
        context = context,
        secretVault = secretVault,
        maxEvents = MAX_TRACE_EVENTS
    )
    private val traceBuffer = ArrayDeque<DiagnosticTraceRecord>(MAX_TRACE_EVENTS)
    private val _traceRecords = MutableStateFlow<List<DiagnosticTraceRecord>>(emptyList())
    val traceRecords: StateFlow<List<DiagnosticTraceRecord>> = _traceRecords.asStateFlow()

    @Volatile
    private var detailedLoggingEnabled: Boolean = initialDetailedEnabled

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        runCatching {
            val loaded = encryptedStore.loadAll()
            if (loaded.isNotEmpty()) {
                synchronized(lock) {
                    loaded.take(MAX_TRACE_EVENTS).forEach { traceBuffer.addLast(it) }
                    _traceRecords.value = traceBuffer.toList()
                }
            }
        }
        settingsDataStore?.let { store ->
            observerScope.launch {
                runCatching {
                    store.settingsFlow
                        .map { it.diagnosticLoggingEnabled }
                        .distinctUntilChanged()
                        .collect { enabled ->
                            detailedLoggingEnabled = enabled
                        }
                }
            }
        }
    }

    fun isDetailedLoggingEnabled(): Boolean = detailedLoggingEnabled

    fun setDetailedLoggingEnabled(enabled: Boolean) {
        detailedLoggingEnabled = enabled
    }

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

    /**
     * Records a structured, strictly allowlisted & scrubbed diagnostic trace entry.
     *
     * Respects [detailedLoggingEnabled]: when disabled, routine verbose trace steps & pure
     * successes are omitted while critical failures and anomalies remain inspectable.
     * Fail-safe: any exception during scrubbing or encrypted persistence is swallowed cleanly.
     */
    fun logTrace(
        traceId: String,
        operationType: String,
        stage: DiagnosticStage,
        outcome: DiagnosticOutcome,
        category: DiagnosticCategory = DiagnosticCategory.STAGE_PIPELINE,
        httpMethod: String = "LOCAL",
        rawUrl: String = "",
        latencyMs: Long = 0L,
        httpStatusCode: Int = -1,
        stageDetailCode: String = "",
        authState: DiagnosticAuthState = DiagnosticAuthState.NONE,
        errorCode: String = "",
        description: String = "",
        engineUsed: String = "",
        fallbackTriggered: Boolean = false,
        anomalies: List<DiagnosticAnomaly> = emptyList(),
        wpServerTraceSteps: List<String> = emptyList(),
        sourceSide: String = DiagnosticTraceRecord.SIDE_APP,
        extraSecretsToRedact: Collection<String> = emptyList(),
        isVerboseStep: Boolean = false
    ): DiagnosticTraceRecord? {
        return runCatching {
            val isFailureOrAnomaly = outcome == DiagnosticOutcome.FAILURE ||
                outcome == DiagnosticOutcome.UNKNOWN ||
                anomalies.isNotEmpty()
            if (!detailedLoggingEnabled && (isVerboseStep || !isFailureOrAnomaly)) {
                return@runCatching null
            }

            val record = DiagnosticTraceRecord(
                id = "diag-${UUID.randomUUID().toString().replace("-", "").take(10)}",
                traceId = DiagnosticSanitizer.sanitizeTraceId(traceId),
                timestampMs = clockMs(),
                sourceSide = if (sourceSide == DiagnosticTraceRecord.SIDE_WORDPRESS) {
                    DiagnosticTraceRecord.SIDE_WORDPRESS
                } else {
                    DiagnosticTraceRecord.SIDE_APP
                },
                operationType = DiagnosticSanitizer.sanitizeOperationType(operationType),
                httpMethod = DiagnosticSanitizer.sanitizeHttpMethod(httpMethod),
                sanitizedUrlPath = DiagnosticSanitizer.sanitizeUrlAndPath(rawUrl),
                latencyMs = latencyMs.coerceAtLeast(0L),
                httpStatusCode = httpStatusCode,
                stage = stage,
                stageDetailCode = DiagnosticSanitizer.sanitizeErrorCode(stageDetailCode),
                authState = authState,
                errorCode = DiagnosticSanitizer.sanitizeErrorCode(errorCode),
                shortDescription = DiagnosticSanitizer.scrubDiagnosticText(
                    raw = description,
                    vault = secretVault,
                    extraSecrets = extraSecretsToRedact
                ),
                engineUsed = DiagnosticSanitizer.normalizeEngineLabel(engineUsed),
                fallbackTriggered = fallbackTriggered,
                outcome = outcome,
                category = category,
                anomalies = anomalies.distinct(),
                wpServerTraceSteps = DiagnosticSanitizer.sanitizeWpTraceSteps(wpServerTraceSteps)
            )

            val snapshot: List<DiagnosticTraceRecord>
            synchronized(lock) {
                while (traceBuffer.size >= MAX_TRACE_EVENTS) {
                    traceBuffer.removeLast()
                }
                traceBuffer.addFirst(record)
                snapshot = traceBuffer.toList()
                _traceRecords.value = snapshot
            }

            encryptedStore.saveAll(snapshot)
            runCatching {
                Log.i(
                    LOG_TAG,
                    "[TRACE ${record.traceId}] stage=${record.stage.code} auth=${record.authState.name} outcome=${record.outcome.name} code=${record.errorCode}"
                )
            }
            record
        }.getOrNull()
    }

    fun recentEvents(): List<DiagnosticEvent> = synchronized(lock) { buffer.toList() }

    fun traceRecordsSnapshot(): List<DiagnosticTraceRecord> = synchronized(lock) { traceBuffer.toList() }

    fun exportSanitizedReportJson(): String =
        runCatching {
            DiagnosticSanitizer.exportSanitizedReportJson(
                records = traceRecordsSnapshot(),
                vault = secretVault,
                exportedAtMs = clockMs()
            )
        }.getOrDefault("{}")

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _events.value = emptyList()
            traceBuffer.clear()
            _traceRecords.value = emptyList()
        }
        runCatching { encryptedStore.clear() }
    }

    fun clearTraceLogsOnly() {
        synchronized(lock) {
            traceBuffer.clear()
            _traceRecords.value = emptyList()
        }
        runCatching { encryptedStore.clear() }
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
        const val MAX_TRACE_EVENTS = 100
        private const val MAX_FIELD_LENGTH = 240

        private val EXTRA_SECRET_REGEX = Regex(
            """(?i)(password|passwd|passcode|otp|pin|secret_key|access_token|refresh_token|api_key|bearer)\s*[:=]\s*[^\s,;]+"""
        )
        private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{7,16}\b""")
    }
}
