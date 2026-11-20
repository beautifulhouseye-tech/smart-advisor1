package com.example.engine

import com.example.data.local.dao.ConversationContextDao
import com.example.data.local.entity.ConversationContextEntity
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationMemoryManager(
    private val contextDao: ConversationContextDao,
    private val secretVault: SecretVault,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(
        contextDao: ConversationContextDao,
        secretVault: SecretVault
    ) : this(contextDao, secretVault, { System.currentTimeMillis() })
    @Serializable
    data class StoredTurn(
        val role: String,
        val text: String,
        val timestampMs: Long
    )

    @Serializable
    data class StagedAssistantTurn(
        val msgKey: String,
        val conversationKey: String,
        val text: String,
        val timestampMs: Long,
        val isSandbox: Boolean = false
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val sandboxContexts = ConcurrentHashMap<String, ConversationContextEntity>()
    private val pendingHandoffTurns = ConcurrentHashMap<String, StagedAssistantTurn>()
    private val shownProductIdsByConversation = ConcurrentHashMap<String, MutableList<Long>>()

    suspend fun getOrCreateContext(
        conversationKey: String,
        packageName: String,
        isGroup: Boolean,
        displayName: String,
        phone: String = "",
        isSandbox: Boolean = false
    ): ConversationContextEntity {
        val existing = if (isSandbox) {
            sandboxContexts[conversationKey]
        } else {
            contextDao.getByKey(conversationKey)
        }
        if (existing != null) return existing

        val fresh = ConversationContextEntity(
            conversationKey = conversationKey,
            packageName = packageName,
            isGroup = isGroup,
            displayName = displayName,
            phone = phone,
            customerKey = ConversationKey.buildCustomerKey(phone, displayName, isGroup),
            updatedAt = clockMs()
        )
        saveContext(fresh, isSandbox)
        return fresh
    }

    suspend fun recordIncomingUserMessage(
        conversationKey: String,
        packageName: String,
        isGroup: Boolean,
        displayName: String,
        phone: String,
        messageText: String,
        contextWindowPairs: Int,
        isSandbox: Boolean = false
    ): ConversationContextEntity {
        val current = getOrCreateContext(
            conversationKey = conversationKey,
            packageName = packageName,
            isGroup = isGroup,
            displayName = displayName,
            phone = phone,
            isSandbox = isSandbox
        )
        val cleanText = SensitiveDataRedactor.scrub(messageText.trim(), secretVault)
        val now = clockMs()

        val updatedConsent = detectConsentSignal(cleanText, current.consentGranted)
        val consentTimestamp = if (updatedConsent != current.consentGranted) now else current.consentUpdatedAt

        val detectedTopicInfo = detectTopicWithCategory(cleanText)
        val detectedTopic = detectedTopicInfo?.first.orEmpty()
        val detectedIsProductTopic = detectedTopicInfo?.second == true
        val currentIsProductTopic = isProductTopic(current.currentTopic) || current.lastProductDiscussed.isNotBlank()

        val productTopicShifted = detectedIsProductTopic &&
            current.currentTopic.isNotBlank() &&
            isProductTopic(current.currentTopic) &&
            detectedTopic != current.currentTopic

        val extractedProduct = extractProductHint(cleanText, detectedTopicInfo)
        val extractedPrefs = extractTemporaryPreferences(cleanText)

        val newTopic = when {
            detectedIsProductTopic -> detectedTopic
            currentIsProductTopic -> current.currentTopic
            detectedTopic.isNotBlank() -> detectedTopic
            extractedProduct.isNotBlank() -> TOPIC_PRODUCT_GENERAL
            else -> current.currentTopic
        }

        val newProduct = when {
            productTopicShifted -> extractedProduct
            extractedProduct.isNotBlank() -> extractedProduct
            else -> refineProductWithFollowUp(current.lastProductDiscussed, cleanText, extractedPrefs)
        }

        val newTempPrefs = if (productTopicShifted) {
            extractedPrefs
        } else {
            (decodeStringList(current.temporaryPreferencesJson) + extractedPrefs)
                .distinct()
                .takeLast(8)
        }
        val newUnanswered = if (cleanText.contains("؟") || cleanText.contains("?")) {
            (decodeStringList(current.unansweredQuestionsJson) + cleanText).distinct().takeLast(3)
        } else if (productTopicShifted) {
            emptyList()
        } else {
            decodeStringList(current.unansweredQuestionsJson)
        }

        val maxMessages = (contextWindowPairs.coerceIn(6, 8) * 2)
        val turns = decodeTurns(current.turnsJson).toMutableList()
        turns.add(StoredTurn(role = "user", text = cleanText, timestampMs = now))
        val trimmedTurns = trimTurns(turns, maxMessages)

        val updated = current.copy(
            displayName = displayName.ifBlank { current.displayName },
            phone = phone.ifBlank { current.phone },
            customerKey = current.customerKey.ifBlank {
                ConversationKey.buildCustomerKey(phone, displayName, isGroup)
            },
            turnsJson = json.encodeToString(trimmedTurns),
            currentTopic = newTopic,
            lastProductDiscussed = newProduct,
            temporaryPreferencesJson = json.encodeToString(newTempPrefs),
            unansweredQuestionsJson = json.encodeToString(newUnanswered),
            consentGranted = updatedConsent,
            consentUpdatedAt = consentTimestamp,
            updatedAt = now
        )
        saveContext(updated, isSandbox)
        return updated
    }

    suspend fun stagePendingAssistantTurn(
        msgKey: String,
        conversationKey: String,
        assistantReply: String,
        isSandbox: Boolean = false
    ) {
        val cleanReply = SensitiveDataRedactor.scrub(assistantReply.trim(), secretVault)
        if (cleanReply.isBlank()) return
        val staged = StagedAssistantTurn(
            msgKey = msgKey,
            conversationKey = conversationKey,
            text = cleanReply,
            timestampMs = clockMs(),
            isSandbox = isSandbox
        )
        if (msgKey.isNotBlank()) {
            pendingHandoffTurns[msgKey] = staged
        }
        if (!isSandbox) {
            contextDao.getByKey(conversationKey)?.let { ctx ->
                contextDao.upsert(
                    ctx.copy(
                        pendingAssistantTurnJson = json.encodeToString(staged),
                        updatedAt = clockMs()
                    )
                )
            }
        }
    }

    suspend fun commitAssistantTurnImmediately(
        conversationKey: String,
        assistantReply: String,
        contextWindowPairs: Int,
        topicOverride: String = "",
        productOverride: String = "",
        isSandbox: Boolean = false
    ) {
        val cleanReply = SensitiveDataRedactor.scrub(assistantReply.trim(), secretVault)
        if (cleanReply.isBlank()) return

        val ctx = if (isSandbox) {
            sandboxContexts[conversationKey]
        } else {
            contextDao.getByKey(conversationKey)
        } ?: return

        val maxMessages = (contextWindowPairs.coerceIn(6, 8) * 2)
        val turns = decodeTurns(ctx.turnsJson).toMutableList()
        turns.add(StoredTurn(role = "assistant", text = cleanReply, timestampMs = clockMs()))
        val trimmed = trimTurns(turns, maxMessages)

        val updated = ctx.copy(
            turnsJson = json.encodeToString(trimmed),
            currentTopic = topicOverride.ifBlank { ctx.currentTopic },
            lastProductDiscussed = productOverride.ifBlank { ctx.lastProductDiscussed },
            unansweredQuestionsJson = "[]",
            pendingAssistantTurnJson = "",
            updatedAt = clockMs()
        )
        saveContext(updated, isSandbox)
    }

    suspend fun confirmHandoffTurnSent(
        msgKey: String,
        contextWindowPairs: Int = 6
    ): Boolean {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return false

        val staged = pendingHandoffTurns.remove(cleanKey)
            ?: findPersistedStagedTurn(cleanKey)
            ?: return false

        commitAssistantTurnImmediately(
            conversationKey = staged.conversationKey,
            assistantReply = staged.text,
            contextWindowPairs = contextWindowPairs,
            isSandbox = staged.isSandbox
        )
        return true
    }

    suspend fun discardPendingHandoffTurn(msgKey: String) {
        val cleanKey = msgKey.trim()
        if (cleanKey.isEmpty()) return
        val removed = pendingHandoffTurns.remove(cleanKey)
        if (removed != null && !removed.isSandbox) {
            contextDao.getByKey(removed.conversationKey)?.let { ctx ->
                contextDao.upsert(ctx.copy(pendingAssistantTurnJson = ""))
            }
            return
        }
        val persisted = contextDao.getAll().firstOrNull { ctx ->
            ctx.pendingAssistantTurnJson.isNotBlank() &&
                runCatching {
                    json.decodeFromString<StagedAssistantTurn>(ctx.pendingAssistantTurnJson).msgKey == cleanKey
                }.getOrDefault(false)
        }
        if (persisted != null) {
            contextDao.upsert(persisted.copy(pendingAssistantTurnJson = ""))
        }
    }

    suspend fun updateMetadataAfterSync(
        conversationKey: String,
        revision: Long,
        summary: String,
        isSandbox: Boolean = false
    ) {
        val ctx = (if (isSandbox) sandboxContexts[conversationKey] else contextDao.getByKey(conversationKey))
            ?: return
        val updated = ctx.copy(
            lastMemoryRevision = maxOf(ctx.lastMemoryRevision, revision),
            lastSyncedSummary = SensitiveDataRedactor.scrub(summary, secretVault),
            updatedAt = clockMs()
        )
        saveContext(updated, isSandbox)
    }

    fun buildTurnsForAi(
        context: ConversationContextEntity,
        excludeLastUserMessage: Boolean = true
    ): List<AIReplyEngine.ChatTurn> {
        val turns = decodeTurns(context.turnsJson)
        val sliced = if (excludeLastUserMessage && turns.lastOrNull()?.role == "user") {
            turns.dropLast(1)
        } else {
            turns
        }
        return sliced.map { AIReplyEngine.ChatTurn(role = it.role, text = it.text, timestampMs = it.timestampMs) }
    }

    fun buildContextSummaryBlock(context: ConversationContextEntity): String {
        val tempPrefs = decodeStringList(context.temporaryPreferencesJson)
        val unanswered = decodeStringList(context.unansweredQuestionsJson)
        val activeEntity = resolveActiveEntityHint(context)
        return buildString {
            if (context.currentTopic.isNotBlank()) {
                append("الموضوع الحالي: ").append(context.currentTopic).append('\n')
            }
            if (context.lastProductDiscussed.isNotBlank()) {
                append("آخر منتج نوقش: ").append(context.lastProductDiscussed).append('\n')
            }
            if (activeEntity.isNotBlank() && activeEntity != context.lastProductDiscussed) {
                append("الكيان النشط في الحوار: ").append(activeEntity).append('\n')
            }
            if (tempPrefs.isNotEmpty()) {
                append("تفضيلات الجلسة المؤقتة: ").append(tempPrefs.joinToString("، ")).append('\n')
            }
            if (unanswered.isNotEmpty()) {
                append("أسئلة مفتوحة: ").append(unanswered.joinToString(" | ")).append('\n')
            }
            if (context.lastSyncedSummary.isNotBlank()) {
                append("ملخص دائم للعميل: ").append(context.lastSyncedSummary).append('\n')
            }
        }.trim()
    }

    fun resolveActiveEntityHint(context: ConversationContextEntity): String {
        val base = context.lastProductDiscussed.trim()
        if (base.isBlank()) return ""
        val prefs = decodeStringList(context.temporaryPreferencesJson)
            .filter { p -> p.isNotBlank() && !base.contains(p, ignoreCase = true) }
        return if (prefs.isEmpty()) base else "$base (${prefs.joinToString("، ")})"
    }

    fun recordShownProductIds(conversationKey: String, productIds: List<Long>) {
        if (conversationKey.isBlank() || productIds.isEmpty()) return
        val list = shownProductIdsByConversation.getOrPut(conversationKey) { mutableListOf() }
        synchronized(list) {
            productIds.forEach { id ->
                if (id > 0L && id !in list) {
                    list.add(id)
                }
            }
            if (list.size > 60) {
                val trimCount = list.size - 60
                repeat(trimCount) { list.removeAt(0) }
            }
        }
    }

    fun getShownProductIds(conversationKey: String): List<Long> {
        if (conversationKey.isBlank()) return emptyList()
        val list = shownProductIdsByConversation[conversationKey] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    fun clearSandboxConversation(conversationKey: String) {
        sandboxContexts.remove(conversationKey)
        shownProductIdsByConversation.remove(conversationKey)
        pendingHandoffTurns.entries.removeIf { it.value.isSandbox && it.value.conversationKey == conversationKey }
    }

    suspend fun getContext(conversationKey: String, isSandbox: Boolean = false): ConversationContextEntity? {
        return if (isSandbox) sandboxContexts[conversationKey] else contextDao.getByKey(conversationKey)
    }

    private suspend fun findPersistedStagedTurn(msgKey: String): StagedAssistantTurn? {
        return contextDao.getAll().firstNotNullOfOrNull { ctx ->
            if (ctx.pendingAssistantTurnJson.isBlank()) null
            else runCatching {
                json.decodeFromString<StagedAssistantTurn>(ctx.pendingAssistantTurnJson)
            }.getOrNull()?.takeIf { it.msgKey == msgKey }
        }
    }

    private suspend fun saveContext(entity: ConversationContextEntity, isSandbox: Boolean) {
        if (isSandbox) {
            sandboxContexts[entity.conversationKey] = entity
        } else {
            contextDao.upsert(entity)
        }
    }

    private fun decodeTurns(rawJson: String): List<StoredTurn> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredTurn>>(rawJson) }.getOrDefault(emptyList())
    }

    private fun decodeStringList(rawJson: String): List<String> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(rawJson) }.getOrDefault(emptyList())
    }

    private fun trimTurns(turns: List<StoredTurn>, maxItems: Int): List<StoredTurn> {
        return if (turns.size > maxItems) turns.takeLast(maxItems) else turns
    }

    private fun detectConsentSignal(text: String, currentConsent: Boolean): Boolean {
        val lower = text.lowercase()
        val revokeSignals = listOf("لا أوافق", "لا تحفظ", "امسح ذاكرتي", "الغ الموافقة", "إلغاء الحفظ")
        if (revokeSignals.any { lower.contains(it) }) return false

        val grantSignals = listOf("أوافق", "موافق", "نعم احفظ", "احفظ تفضيلاتي", "سجل تفضيلاتي", "consent confirmed", "i agree")
        if (grantSignals.any { lower.contains(it) }) return true

        return currentConsent
    }

    companion object {
        private const val TOPIC_PRODUCT_GENERAL = "استفسار منتج"

        // Triple: (topicName, isProductCatalogDomain, keywords)
        private val TOPIC_CATALOG_ENTRIES: List<Triple<String, Boolean, List<String>>> = listOf(
            Triple("عطور", true, listOf("عطر", "عطور", "عود", "مسك", "فواح", "perfume")),
            Triple("أجهزة المطبخ", true, listOf("خلاط", "عصارة", "مطحنة", "غلاية", "مقلاة", "مايكرويف", "مطبخ", "blender", "mixer", "coffee maker")),
            Triple("أحذية", true, listOf("حذاء", "أحذية", "احذية", "جزمة", "سنيكرز", "كوتشي", "shoe")),
            Triple("ملابس", true, listOf("ثوب", "قميص", "جاكيت", "ملابس", "فستان", "تيشيرت")),
            Triple("إلكترونيات", true, listOf("جوال", "هاتف", "لابتوب", "سماعة", "ساعة ذكية", "شاحن", "كاميرا")),
            Triple("ساعات", true, listOf("ساعة", "ساعات", "watch")),
            Triple("العناية والتجميل", true, listOf("كريم", "سيروم", "شامبو", "تجميل", "بشرة")),
            Triple("الأثاث والمنزل", true, listOf("طاولة", "كرسي", "مفرش", "أثاث", "مصباح")),
            Triple("الشحن والتوصيل", false, listOf("شحن", "توصيل", "شركة الشحن", "مدة التوصيل")),
            Triple("الاسترجاع والاستبدال", false, listOf("استرجاع", "استبدال", "ارجاع", "مرتجع")),
            Triple("العروض والخصومات", false, listOf("عرض", "عروض", "خصم", "تخفيض", "كوبون")),
            Triple("الحساب والدخول", false, listOf("تسجيل", "حسابي", "رابط دخول", "دخول"))
        )

        private val PREF_KEYWORDS = listOf(
            "رجالي", "نسائي", "أطفالي", "رياضي", "رسمي",
            "أسود", "اسود", "أبيض", "ابيض", "أزرق", "ازرق", "أحمر", "احمر", "ذهبي", "فضي",
            "سريع", "اقتصادي", "بسعر مناسب", "رخيص", "فاخر", "عود", "فواح", "جلد", "مناسب"
        )

        private val ATTRIBUTE_ONLY_WORDS = setOf(
            "رجالي", "نسائي", "أطفالي", "رياضي", "رسمي",
            "أسود", "اسود", "أبيض", "ابيض", "أزرق", "ازرق", "أحمر", "احمر", "ذهبي", "فضي"
        )

        private val PRODUCT_INTENT_PREFIX_REGEX = Regex(
            """^(?:أبحث عن|ابحث عن|أريد|اريد|أبي|ابي|ابغى|أبغى|عندكم|هل يوجد|بكم|كم سعر)\s+(.+)$"""
        )

        private val NON_PRODUCT_FOLLOWUPS = setOf(
            "كم سعره", "كم سعره؟", "كم السعر", "كم السعر؟", "بكم", "بكم؟",
            "هل متوفر", "هل متوفر؟", "السعر", "التوصيل", "الشحن"
        )

        fun detectTopicCategory(text: String): String {
            return detectTopicWithCategory(text)?.first.orEmpty()
        }

        private fun detectTopicWithCategory(text: String): Pair<String, Boolean>? {
            val lower = text.lowercase()
            val hit = TOPIC_CATALOG_ENTRIES.firstOrNull { (_, _, keywords) ->
                keywords.any { kw -> lower.contains(kw) }
            } ?: return null
            return hit.first to hit.second
        }

        private fun isProductTopic(topic: String): Boolean {
            if (topic == TOPIC_PRODUCT_GENERAL) return true
            return TOPIC_CATALOG_ENTRIES.any { it.first == topic && it.second }
        }

        private fun extractProductHint(text: String, topicInfo: Pair<String, Boolean>?): String {
            val clean = text.trim()
            if (clean.isEmpty() || clean.length > 70) return ""
            val normLower = clean.lowercase()
            if (normLower in NON_PRODUCT_FOLLOWUPS) return ""
            if (clean.contains("بسعر مناسب") || clean.startsWith("هل يوجد لون") || clean.startsWith("أريده")) {
                if (topicInfo?.second != true) return ""
            }
            if (topicInfo != null && topicInfo.second) {
                return PRODUCT_INTENT_PREFIX_REGEX.find(clean)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() } ?: clean
            }
            // Dynamic product noun extraction for arbitrary catalog queries ("أبحث عن خلاط للمطبخ")
            if (topicInfo == null) {
                val match = PRODUCT_INTENT_PREFIX_REGEX.find(clean)
                val extracted = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (extracted.length in 2..45 && !extracted.startsWith("سعره")) {
                    return extracted
                }
            }
            return ""
        }

        private fun refineProductWithFollowUp(
            previousProduct: String,
            currentText: String,
            extractedPrefs: List<String>
        ): String {
            if (previousProduct.isBlank()) return ""
            val clean = currentText.trim().trimEnd('؟', '?', '.', '!')
            // If customer replies with a short attribute modifier (e.g. "رجالي" after "أريد عطر"),
            // refine "عطر" -> "عطر رجالي" cleanly without discarding the base product!
            if (clean.length <= 24 && ATTRIBUTE_ONLY_WORDS.any { clean.equals(it, ignoreCase = true) }) {
                return if (previousProduct.contains(clean, ignoreCase = true)) {
                    previousProduct
                } else {
                    "$previousProduct $clean".trim()
                }
            }
            if (extractedPrefs.size == 1 && clean.split(Regex("\\s+")).size <= 2) {
                val pref = extractedPrefs.first()
                if (pref in ATTRIBUTE_ONLY_WORDS && !previousProduct.contains(pref, ignoreCase = true)) {
                    return "$previousProduct $pref".trim()
                }
            }
            return previousProduct
        }

        private val SIZE_REGEX = Regex("""(?i)(?:مقاس|size)\s*[:\-]?\s*\d{1,3}""")

        private fun extractTemporaryPreferences(text: String): List<String> {
            val lower = text.lowercase()
            val keywordPrefs = PREF_KEYWORDS.filter { lower.contains(it) }
            val sizePrefs = SIZE_REGEX.findAll(text).map { it.value.trim() }.toList()
            return (keywordPrefs + sizePrefs).distinct()
        }
    }
}
