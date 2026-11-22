package com.example.engine

import android.content.Context
import com.example.network.security.OutboxCrypto
import com.example.network.security.SecretVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Encrypted local ring-buffer persistence for the latest 100 diagnostic trace records.
 * Uses AES-256-GCM via [OutboxCrypto] & [SecretVault] in private app storage (`filesDir`)
 * without altering the Room database schema or migrations.
 *
 * Strictly fail-safe: every I/O or crypto failure is caught silently so messaging and
 * replies are never delayed or broken.
 */
class EncryptedDiagnosticStore(
    private val context: Context?,
    private val secretVault: SecretVault?,
    private val maxEvents: Int = MAX_PERSISTED_EVENTS
) {
    @Serializable
    private data class PersistedEnvelope(
        val schemaVersion: Int = 1,
        val events: List<DiagnosticTraceRecord> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fileLock = Any()

    fun loadAll(): List<DiagnosticTraceRecord> {
        val ctx = context ?: return emptyList()
        val vault = secretVault ?: return emptyList()
        return synchronized(fileLock) {
            runCatching {
                val targetFile = File(ctx.filesDir, FILE_NAME)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    return@runCatching emptyList()
                }
                val encoded = targetFile.readText(Charsets.UTF_8).trim()
                if (encoded.isEmpty()) return@runCatching emptyList()
                val decrypted = OutboxCrypto.decrypt(encoded, vault) ?: return@runCatching emptyList()
                val envelope = json.decodeFromString<PersistedEnvelope>(decrypted)
                envelope.events
                    .sortedByDescending { it.timestampMs }
                    .take(maxEvents.coerceIn(1, MAX_PERSISTED_EVENTS))
            }.getOrDefault(emptyList())
        }
    }

    fun saveAll(records: List<DiagnosticTraceRecord>) {
        val ctx = context ?: return
        val vault = secretVault ?: return
        synchronized(fileLock) {
            runCatching {
                val capped = records
                    .sortedByDescending { it.timestampMs }
                    .take(maxEvents.coerceIn(1, MAX_PERSISTED_EVENTS))
                val payload = json.encodeToString(PersistedEnvelope(schemaVersion = 1, events = capped))
                val encrypted = OutboxCrypto.encrypt(payload, vault)

                val dir = ctx.filesDir ?: return@runCatching
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, FILE_NAME)
                val tempFile = File(dir, "$FILE_NAME.tmp")
                tempFile.writeText(encrypted, Charsets.UTF_8)
                if (!tempFile.renameTo(targetFile)) {
                    targetFile.writeText(encrypted, Charsets.UTF_8)
                    tempFile.delete()
                }
            }
        }
    }

    fun clear() {
        val ctx = context ?: return
        synchronized(fileLock) {
            runCatching {
                val dir = ctx.filesDir ?: return@runCatching
                File(dir, FILE_NAME).delete()
                File(dir, "$FILE_NAME.tmp").delete()
            }
        }
    }

    companion object {
        const val MAX_PERSISTED_EVENTS = 100
        const val FILE_NAME = "sa_diagnostics_ring_v1.enc"
    }
}
