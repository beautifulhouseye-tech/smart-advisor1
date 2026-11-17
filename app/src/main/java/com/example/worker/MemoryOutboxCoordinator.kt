package com.example.worker

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.local.dao.MemoryOutboxDao
import com.example.data.local.entity.MemoryOutboxEntity
import com.example.engine.ConversationMemoryManager
import com.example.network.security.OutboxCrypto
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import com.example.network.smartadvisor.SmartAdvisorClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryOutboxCoordinator(
    private val outboxDao: MemoryOutboxDao,
    private val smartAdvisorClient: SmartAdvisorClient,
    private val conversationMemoryManager: ConversationMemoryManager,
    private val secretVault: SecretVault,
    private val workManager: WorkManager?,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(
        outboxDao: MemoryOutboxDao,
        smartAdvisorClient: SmartAdvisorClient,
        conversationMemoryManager: ConversationMemoryManager,
        secretVault: SecretVault,
        workManager: WorkManager
    ) : this(
        outboxDao = outboxDao,
        smartAdvisorClient = smartAdvisorClient,
        conversationMemoryManager = conversationMemoryManager,
        secretVault = secretVault,
        workManager = workManager,
        clockMs = { System.currentTimeMillis() }
    )
    @Serializable
    data class OutboxMemoryPayload(
        val summary: String = "",
        val preferences: List<String> = emptyList(),
        val interests: List<String> = emptyList(),
        val constraints: List<String> = emptyList(),
        val phone: String = "",
        val consentConfirmed: Boolean = true
    )

    data class EnqueueResult(
        val enqueued: Boolean,
        val reason: String,
        val eventId: String = "",
        val revision: Long = 0L
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enqueueDurableMemoryUpdate(
        conversationKey: String,
        customerKey: String,
        phone: String,
        summary: String,
        preferences: List<String>,
        interests: List<String>,
        constraints: List<String>,
        consentGranted: Boolean,
        proposedRevision: Long? = null,
        scheduleWorker: Boolean = true
    ): EnqueueResult {
        if (!consentGranted) {
            return EnqueueResult(enqueued = false, reason = "consent_required")
        }
        if (customerKey.isBlank()) {
            return EnqueueResult(enqueued = false, reason = "missing_customer_key")
        }

        val cleanSummary = SensitiveDataRedactor.scrub(summary.trim(), secretVault)
        val cleanPrefs = filterSafeList(preferences)
        val cleanInterests = filterSafeList(interests)
        val cleanConstraints = filterSafeList(constraints)

        if (SensitiveDataRedactor.containsSensitivePaymentOrCredential(cleanSummary)) {
            return EnqueueResult(enqueued = false, reason = "sensitive_data_blocked")
        }
        if (cleanSummary.isBlank() && cleanPrefs.isEmpty() && cleanInterests.isEmpty() && cleanConstraints.isEmpty()) {
            return EnqueueResult(enqueued = false, reason = "empty_memory_update")
        }

        val ctx = conversationMemoryManager.getContext(conversationKey)
        val latestOutbox = outboxDao.getLatestForConversation(conversationKey)
        val highestKnownRevision = maxOf(ctx?.lastMemoryRevision ?: 0L, latestOutbox?.revision ?: 0L)

        val targetRevision = if (proposedRevision != null) {
            if (proposedRevision <= highestKnownRevision) {
                return EnqueueResult(enqueued = false, reason = "stale_revision_rejected")
            }
            proposedRevision
        } else {
            highestKnownRevision + 1L
        }

        val payload = OutboxMemoryPayload(
            summary = cleanSummary,
            preferences = cleanPrefs,
            interests = cleanInterests,
            constraints = cleanConstraints,
            phone = phone.trim(),
            consentConfirmed = true
        )
        val rawJson = json.encodeToString(payload)
        val encrypted = OutboxCrypto.encrypt(rawJson, secretVault)
        val eventId = deriveEventId(conversationKey, targetRevision, rawJson)
        val now = clockMs()

        val inserted = outboxDao.insertIgnore(
            MemoryOutboxEntity(
                eventId = eventId,
                conversationKey = conversationKey,
                customerKey = customerKey,
                encryptedPayload = encrypted,
                revision = targetRevision,
                state = MemoryOutboxEntity.STATE_PENDING,
                attempts = 0,
                createdAt = now,
                updatedAt = now
            )
        )

        if (inserted == -1L) {
            return EnqueueResult(enqueued = false, reason = "duplicate_event_id", eventId = eventId)
        }

        if (scheduleWorker) {
            enqueueWorker()
        }
        return EnqueueResult(
            enqueued = true,
            reason = "queued",
            eventId = eventId,
            revision = targetRevision
        )
    }

    suspend fun flushPending(): Int {
        val pendingItems = outboxDao.getPending()
        if (pendingItems.isEmpty()) return 0

        val caps = smartAdvisorClient.fetchCapabilities()
        if (!caps.memoryEnabled) {
            return 0
        }

        var sentCount = 0
        for (item in pendingItems) {
            val decryptedJson = OutboxCrypto.decrypt(item.encryptedPayload, secretVault)
            if (decryptedJson.isNullOrBlank()) {
                outboxDao.update(
                    item.copy(
                        state = MemoryOutboxEntity.STATE_FAILED_PERMANENT,
                        lastError = "تعذر فك تشفير حمولة الذاكرة",
                        updatedAt = clockMs()
                    )
                )
                continue
            }

            val payload = runCatching {
                json.decodeFromString<OutboxMemoryPayload>(decryptedJson)
            }.getOrNull()
            if (payload == null || !payload.consentConfirmed || (caps.consentRequired && !payload.consentConfirmed)) {
                outboxDao.update(
                    item.copy(
                        state = MemoryOutboxEntity.STATE_FAILED_PERMANENT,
                        lastError = "موافقة العميل غير مؤكدة",
                        updatedAt = clockMs()
                    )
                )
                continue
            }

            val res = smartAdvisorClient.saveCustomerMemory(
                customerKey = item.customerKey,
                eventId = item.eventId,
                revision = item.revision,
                memory = SmartAdvisorClient.SaMemoryPayload(
                    summary = payload.summary,
                    preferences = payload.preferences,
                    interests = payload.interests,
                    constraints = payload.constraints,
                    confirmed = true
                ),
                consentConfirmed = payload.consentConfirmed,
                phone = payload.phone
            )

            when {
                res.ok -> {
                    sentCount++
                    outboxDao.update(
                        item.copy(
                            state = MemoryOutboxEntity.STATE_SENT,
                            attempts = item.attempts + 1,
                            lastError = "",
                            updatedAt = clockMs()
                        )
                    )
                    conversationMemoryManager.updateMetadataAfterSync(
                        conversationKey = item.conversationKey,
                        revision = maxOf(item.revision, res.revision),
                        summary = payload.summary
                    )
                }
                res.conflict -> {
                    // Merge remote memory with local payload cleanly at revision = serverRevision + 1
                    val remote = res.memory
                    val merged = OutboxMemoryPayload(
                        summary = listOfNotNull(
                            remote?.summary?.takeIf { it.isNotBlank() },
                            payload.summary.takeIf { it.isNotBlank() && it != remote?.summary }
                        ).distinct().joinToString(" | "),
                        preferences = ((remote?.preferences.orEmpty()) + payload.preferences).distinct(),
                        interests = ((remote?.interests.orEmpty()) + payload.interests).distinct(),
                        constraints = ((remote?.constraints.orEmpty()) + payload.constraints).distinct(),
                        phone = payload.phone,
                        consentConfirmed = payload.consentConfirmed
                    )
                    val nextRevision = maxOf(item.revision, res.revision) + 1L
                    val mergedPayload = OutboxCrypto.encrypt(json.encodeToString(merged), secretVault)
                    val mergeRes = smartAdvisorClient.saveCustomerMemory(
                        customerKey = item.customerKey,
                        eventId = "${item.eventId}-m$nextRevision",
                        revision = nextRevision,
                        memory = SmartAdvisorClient.SaMemoryPayload(
                            summary = merged.summary,
                            preferences = merged.preferences,
                            interests = merged.interests,
                            constraints = merged.constraints,
                            confirmed = true
                        ),
                        consentConfirmed = true,
                        phone = merged.phone
                    )
                    if (mergeRes.ok) {
                        sentCount++
                        outboxDao.update(
                            item.copy(
                                encryptedPayload = mergedPayload,
                                revision = nextRevision,
                                state = MemoryOutboxEntity.STATE_CONFLICT_MERGED,
                                attempts = item.attempts + 1,
                                lastError = "",
                                updatedAt = clockMs()
                            )
                        )
                        conversationMemoryManager.updateMetadataAfterSync(
                            conversationKey = item.conversationKey,
                            revision = maxOf(nextRevision, mergeRes.revision),
                            summary = merged.summary
                        )
                    } else {
                        outboxDao.update(
                            item.copy(
                                encryptedPayload = mergedPayload,
                                revision = nextRevision,
                                attempts = item.attempts + 1,
                                lastError = res.error ?: "تعارض في النسخة",
                                updatedAt = clockMs()
                            )
                        )
                    }
                }
                res.consentRequired || res.skillDisabled || res.statusCode in 400..499 -> {
                    outboxDao.update(
                        item.copy(
                            state = MemoryOutboxEntity.STATE_FAILED_PERMANENT,
                            attempts = item.attempts + 1,
                            lastError = res.error ?: "تم الرفض من الخادم (${res.statusCode})",
                            updatedAt = clockMs()
                        )
                    )
                }
                else -> {
                    val nextAttempts = item.attempts + 1
                    val nextState = if (nextAttempts >= MAX_ATTEMPTS) {
                        MemoryOutboxEntity.STATE_FAILED_PERMANENT
                    } else {
                        MemoryOutboxEntity.STATE_PENDING
                    }
                    outboxDao.update(
                        item.copy(
                            state = nextState,
                            attempts = nextAttempts,
                            lastError = res.error ?: "فشل شبكة مؤقت",
                            updatedAt = clockMs()
                        )
                    )
                }
            }
        }
        return sentCount
    }

    private fun filterSafeList(items: List<String>): List<String> {
        return items.asSequence()
            .map { SensitiveDataRedactor.scrub(it.trim(), secretVault) }
            .filter { it.isNotEmpty() && !SensitiveDataRedactor.containsSensitivePaymentOrCredential(it) }
            .distinct()
            .toList()
    }

    private fun enqueueWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<MemoryOutboxWorker>()
            .setConstraints(constraints)
            .build()
        workManager?.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun deriveEventId(conversationKey: String, revision: Long, payloadJson: String): String {
        val raw = "$conversationKey|$revision|$payloadJson"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "mem_" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    companion object {
        const val WORK_NAME = "automate_memory_outbox_sync"
        const val MAX_ATTEMPTS = 5
    }
}
