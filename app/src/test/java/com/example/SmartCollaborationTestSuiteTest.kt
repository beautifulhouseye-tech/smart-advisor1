package com.example

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ConversationContextEntity
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.MemoryOutboxEntity
import com.example.data.local.entity.MenuReplyEntity
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.ConversationKey
import com.example.engine.ConversationMemoryManager
import com.example.engine.IdempotencyGuard
import com.example.engine.IncomingNotificationMessage
import com.example.engine.LocalOutcome
import com.example.engine.MenuOption
import com.example.engine.MenuReplyManager
import com.example.engine.PendingAccessibilityReply
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import com.example.engine.ReplySourceCategory
import com.example.engine.SmartCollaborationCoordinator
import com.example.engine.UnifiedReplyTester
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.OutboxCrypto
import com.example.network.security.SecretVault
import com.example.network.security.SensitiveDataRedactor
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.service.AutoReplyAccessibilityService
import com.example.ui.viewmodel.LogsViewModel
import com.example.worker.FollowUpScheduler
import com.example.worker.MemoryOutboxCoordinator
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartCollaborationTestSuiteTest {

    private class RecordingTransport(
        var handler: suspend (url: String, method: String, headers: Map<String, String>, body: String?) -> Result =
            { _, _, _, _ -> Result.Failure("not stubbed", 500) }
    ) : HttpTransport() {
        data class Call(
            val url: String,
            val method: String,
            val headers: Map<String, String>,
            val body: String?
        )

        val calls = mutableListOf<Call>()

        override suspend fun execute(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
            contentType: String,
            maxAttempts: Int,
            timeoutSeconds: Int,
            idempotencyKey: String?
        ): Result {
            calls += Call(url, method, headers, body)
            return handler(url, method, headers, body)
        }
    }

    private lateinit var context: Context
    private lateinit var db: AutoMateDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var repository: AutoMateRepository
    private lateinit var serverConfigRepository: ServerConfigRepository
    private lateinit var menuReplyManager: MenuReplyManager
    private lateinit var secretVault: SecretVault
    private lateinit var aiSettingsPrefs: AiSettingsPrefs
    private lateinit var smartAdvisorPrefs: SmartAdvisorPrefs
    private lateinit var transport: RecordingTransport
    private lateinit var aiReplyEngine: AIReplyEngine
    private lateinit var webhookClient: WebhookClient
    private lateinit var smartAdvisorClient: SmartAdvisorClient
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var outboxCoordinator: MemoryOutboxCoordinator
    private lateinit var coordinator: SmartCollaborationCoordinator
    private lateinit var idempotencyGuard: IdempotencyGuard
    private lateinit var pendingReplyCoordinator: PendingReplyCoordinator
    private lateinit var replyDispatcher: ReplyDispatcher
    private lateinit var autoReplyEngine: AutoReplyEngine
    private lateinit var tester: UnifiedReplyTester

    private var fakeNowMs: Long = 1_763_400_000_000L
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsDataStore = SettingsDataStore(context)
        resetSharedSettings()

        repository = AutoMateRepository(
            replyRuleDao = db.replyRuleDao(),
            contactFilterDao = db.contactFilterDao(),
            replyLogDao = db.replyLogDao(),
            settingsDataStore = settingsDataStore,
            backupManager = BackupManager(context, json)
        )
        serverConfigRepository = ServerConfigRepositoryImpl(db.serverConfigDao())
        menuReplyManager = MenuReplyManager(db.menuReplyDao(), json)

        val suffix = System.nanoTime()
        secretVault = SecretVault(
            context,
            context.getSharedPreferences("collab_vault_$suffix", Context.MODE_PRIVATE)
        )
        aiSettingsPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("collab_ai_prefs_$suffix", Context.MODE_PRIVATE)
        )
        smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("collab_sa_prefs_$suffix", Context.MODE_PRIVATE)
        )

        transport = RecordingTransport()
        aiReplyEngine = AIReplyEngine(transport, secretVault)
        webhookClient = WebhookClient(transport, secretVault)
        smartAdvisorClient = SmartAdvisorClient(
            transport = transport,
            vault = secretVault,
            baseUrlProvider = { smartAdvisorPrefs.baseUrl },
            apiKeyRefProvider = { smartAdvisorPrefs.apiKeyRef },
            timeoutSecondsProvider = { smartAdvisorPrefs.timeoutSeconds },
            maxAttemptsProvider = { smartAdvisorPrefs.maxAttempts },
            clockMs = { fakeNowMs }
        )

        memoryManager = ConversationMemoryManager(
            contextDao = db.conversationContextDao(),
            secretVault = secretVault,
            clockMs = { fakeNowMs }
        )
        outboxCoordinator = MemoryOutboxCoordinator(
            outboxDao = db.memoryOutboxDao(),
            smartAdvisorClient = smartAdvisorClient,
            conversationMemoryManager = memoryManager,
            secretVault = secretVault,
            workManager = null,
            clockMs = { fakeNowMs }
        )
        coordinator = SmartCollaborationCoordinator(
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiReplyEngine,
            smartAdvisorClient = smartAdvisorClient,
            smartAdvisorPrefs = smartAdvisorPrefs,
            conversationMemoryManager = memoryManager,
            memoryOutboxCoordinator = outboxCoordinator,
            secretVault = secretVault,
            clockMs = { fakeNowMs }
        )

        idempotencyGuard = IdempotencyGuard(db.dedupKeyDao(), clockMs = { fakeNowMs })
        pendingReplyCoordinator = PendingReplyCoordinator(clockMs = { fakeNowMs })
        replyDispatcher = ReplyDispatcher(context, pendingReplyCoordinator, idempotencyGuard)
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(
                context,
                Configuration.Builder().setExecutor { r -> r.run() }.build()
            )
            WorkManager.getInstance(context)
        }
        autoReplyEngine = AutoReplyEngine(
            repository = repository,
            pendingReplyCoordinator = pendingReplyCoordinator,
            menuReplyManager = menuReplyManager,
            followUpScheduler = FollowUpScheduler(workManager, db.followUpDao()),
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiReplyEngine,
            secretVault = secretVault,
            replyDispatcher = replyDispatcher,
            conversationMemoryManager = memoryManager,
            smartCollaborationCoordinator = coordinator
        )
        tester = UnifiedReplyTester(
            repository = repository,
            menuReplyManager = menuReplyManager,
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiReplyEngine,
            secretVault = secretVault,
            serverRepo = serverConfigRepository,
            webhookClient = webhookClient,
            smartAdvisorClient = smartAdvisorClient,
            smartAdvisorPrefs = smartAdvisorPrefs,
            conversationMemoryManager = memoryManager,
            smartCollaborationCoordinator = coordinator,
            clockMs = { fakeNowMs }
        )
    }

    @After
    fun tearDown() = runBlocking {
        tester.resetSession()
        resetSharedSettings()
        db.close()
    }

    private suspend fun resetSharedSettings() {
        settingsDataStore.setAutoReplyEnabled(false)
        settingsDataStore.setDrivingModeEnabled(false)
        settingsDataStore.setReplyInGroups(false)
        settingsDataStore.setCooldownMinutes(0)
        settingsDataStore.setUseAccessibilityFallback(true)
    }

    private fun configureCollaboration(
        saEnabled: Boolean = true,
        primaryUrl: String = "https://gateway.example.com",
        primaryModel: String = "gpt-4o-mini",
        ollamaUrl: String = "https://ollama.example.com",
        ollamaModel: String = "qwen2.5:1.5b",
        syncMemory: Boolean = false,
        requireConsent: Boolean = true,
        safeFallbackReply: String = ""
    ) {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "sk-new-api-secret-999")
        secretVault.put(AiSettingsPrefs.KEY_REF_OLLAMA, "sk-ollama-secret-777")
        secretVault.put("sa_api_key_ref", "sa-secret-key-888")
        smartAdvisorPrefs.saveCredentials(
            baseUrl = "https://store.example.com/wp-json/smart-advisor/v1/whatsapp/reply",
            apiKeyRef = "sa_api_key_ref",
            enabled = saEnabled
        )
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                model = primaryModel,
                primaryBaseUrl = primaryUrl,
                primaryModel = primaryModel,
                fallbackEnabled = true,
                ollamaBaseUrl = ollamaUrl,
                ollamaModel = ollamaModel,
                contextWindowPairs = 6,
                syncCustomerMemory = syncMemory,
                requireCustomerConsent = requireConsent,
                saCollaborationEnabled = saEnabled,
                safeFallbackReply = safeFallbackReply
            )
        )
    }

    private fun openAiJsonResponse(contentJson: String): HttpTransport.Result.Success {
        val body = buildJsonObject {
            put(
                "choices",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject {
                                    put("content", contentJson)
                                }
                            )
                        }
                    )
                }
            )
        }.toString()
        return HttpTransport.Result.Success(body = body, code = 200, ms = 15L)
    }

    private fun ollamaJsonResponse(contentJson: String): HttpTransport.Result.Success {
        val body = buildJsonObject {
            put(
                "message",
                buildJsonObject {
                    put("content", contentJson)
                }
            )
        }.toString()
        return HttpTransport.Result.Success(body = body, code = 200, ms = 18L)
    }

    private fun buildDirectReplyAction(): Notification.Action {
        val remoteInput = RemoteInput.Builder("key_text_reply").setLabel("رد").build()
        val pi = PendingIntent.getBroadcast(
            context,
            9001,
            Intent("com.example.ACTION_REPLY"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(0, "رد", pi).addRemoteInput(remoteInput).build()
    }

    // 1. Conversational non-store message answered by New API without calling Smart Advisor /knowledge
    @Test
    fun scenario_01_conversational_message_uses_new_api_without_calling_smart_advisor_knowledge() = runTest {
        configureCollaboration()
        transport.handler = { url, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge":true,"memory":false,"account_actions":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.contains("gateway.example.com") -> openAiJsonResponse(
                    """{"reply":"أهلاً وسهلاً بك! كيف يمكنني مساعدتك اليوم؟","intent":"greeting","confidence":0.96,"needs_store_data":false}"""
                )
                else -> HttpTransport.Result.Failure("unexpected call $url", 500)
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "خالد",
                messageText = "أهلاً كيف حالك؟"
            )
        )

        assertEquals("أهلاً وسهلاً بك! كيف يمكنني مساعدتك اليوم؟", outcome.replyText)
        assertTrue(outcome.usedNewApi)
        assertFalse(outcome.calledSmartAdvisor)
        assertFalse(outcome.verifiedStoreDataUsed)
        assertFalse(transport.calls.any { it.url.contains("/knowledge") })
    }

    // 2. Store inquiry routes New API -> Smart Advisor /knowledge -> New API grounded reply
    @Test
    fun scenario_02_store_inquiry_queries_smart_advisor_knowledge_and_grounds_final_reply() = runTest {
        configureCollaboration()
        var aiCallCount = 0
        transport.handler = { url, _, headers, body ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge":true,"memory":false,"account_actions":false,"consent_required":true}""",
                    200,
                    4L
                )
                url.contains("gateway.example.com") -> {
                    aiCallCount++
                    if (aiCallCount == 1) {
                        openAiJsonResponse(
                            """{"reply":"","intent":"product_details","confidence":0.9,"needs_store_data":true,"store_query":"عطر العود الملكي","store_intent":"product_details"}"""
                        )
                    } else {
                        assertTrue("Pass 2 must contain verified price", body?.contains("199 ريال") == true)
                        openAiJsonResponse(
                            """{"reply":"عطر العود الملكي متوفر لدينا بسعر 199 ريال شامل الضريبة.","intent":"product_details","confidence":0.95,"needs_store_data":false}"""
                        )
                    }
                }
                url.endsWith("/knowledge") -> {
                    assertEquals("sa-secret-key-888", headers["X-SA-API-Key"])
                    HttpTransport.Result.Success(
                        """{"has_verified_data":true,"intent":"product_details","summary":"عطر العود الملكي بسعر 199 ريال ومتوفر في المخزون.","facts":["السعر: 199 ريال","الحالة: متوفر"]}""",
                        200,
                        9L
                    )
                }
                else -> HttpTransport.Result.Failure("unexpected $url", 500)
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "فهد",
                messageText = "كم سعر عطر العود؟"
            )
        )

        assertEquals(2, aiCallCount)
        assertTrue(outcome.calledSmartAdvisor)
        assertTrue(outcome.verifiedStoreDataUsed)
        assertEquals("product_details", outcome.saIntent)
        assertEquals("عطر العود الملكي متوفر لدينا بسعر 199 ريال شامل الضريبة.", outcome.replyText)
        assertTrue(outcome.replyText!!.contains("199 ريال"))
    }

    // 3. New API fails -> automatic failover to Qwen / Ollama
    @Test
    fun scenario_03_primary_failure_automatically_fails_over_to_qwen_fallback() = runTest {
        configureCollaboration()
        transport.handler = { url, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge":true,"memory":false,"account_actions":false}""",
                    200,
                    3L
                )
                url.contains("gateway.example.com") -> HttpTransport.Result.Failure("HTTP 503 Gateway Down", 503)
                url.contains("ollama.example.com") -> ollamaJsonResponse(
                    """{"reply":"أهلاً بك، خدمة الطلبات متاحة الآن عبر Qwen.","intent":"general","confidence":0.85,"needs_store_data":false}"""
                )
                else -> HttpTransport.Result.Failure("unexpected", 500)
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "سالم",
                messageText = "مرحباً"
            )
        )

        assertTrue(outcome.switchedToQwen)
        assertTrue(outcome.activeEngine.contains("qwen2.5:1.5b"))
        assertEquals("أهلاً بك، خدمة الطلبات متاحة الآن عبر Qwen.", outcome.replyText)
        assertEquals(1, transport.calls.count { it.url.contains("ollama.example.com") })
    }

    // 4. Both New API & Qwen fail -> applies safe policy & never alters WhatsApp send guarantees / DELIVERY_UNKNOWN
    @Test
    fun scenario_04_both_engines_fail_applies_safe_policy_and_preserves_delivery_unknown() = runTest {
        settingsDataStore.setAutoReplyEnabled(true)
        configureCollaboration(safeFallbackReply = "")
        repository.upsertRule(
            ReplyRuleEntity(
                keyword = "*",
                replyText = "تم استلام رسالتك وسنعود إليك قريباً.",
                matchType = MatchType.EXACT,
                isEnabled = true,
                priority = 1
            )
        )
        transport.handler = { url, _, _, _ ->
            if (url.endsWith("/capabilities")) {
                HttpTransport.Result.Success("""{"knowledge":true}""", 200, 2L)
            } else {
                HttpTransport.Result.Failure("timeout", -1)
            }
        }

        val unknownKey = "msg-unknown-immutable"
        db.dedupKeyDao().insertIgnore(
            DedupKeyEntity(
                key = unknownKey,
                createdAt = fakeNowMs,
                state = DedupKeyEntity.STATE_DELIVERY_UNKNOWN,
                sendState = DedupKeyEntity.SEND_DELIVERY_UNKNOWN
            )
        )

        var directSendCount = 0
        val res = autoReplyEngine.processIncomingMessage(
            IncomingNotificationMessage(
                packageName = "com.whatsapp",
                senderName = "طارق",
                messageText = "هل يوجد خصم اليوم؟",
                isGroup = false,
                replyAction = buildDirectReplyAction(),
                contentIntent = null,
                msgKey = "msg-wildcard-after-fail",
                directSendInvoker = { _, _ -> directSendCount++ }
            )
        )

        assertTrue(res is LocalOutcome.Handled)
        assertEquals("تم استلام رسالتك وسنعود إليك قريباً.", (res as LocalOutcome.Handled).replyText)
        assertEquals(1, directSendCount)

        // DELIVERY_UNKNOWN entry remains untouched even after fallback dispatch failures
        val unknownEntry = db.dedupKeyDao().getByKey(unknownKey)
        assertEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, unknownEntry?.state)
        assertEquals(DedupKeyEntity.SEND_DELIVERY_UNKNOWN, unknownEntry?.sendState)
    }

    // 5. Smart Advisor returns has_verified_data=false or 403 disabled -> refuses invention + marks needsHuman
    @Test
    fun scenario_05_unverified_or_disabled_store_data_refuses_fabrication_and_marks_needs_human() = runTest {
        configureCollaboration()
        transport.handler = { url, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success("""{"knowledge":true}""", 200, 3L)
                url.contains("gateway.example.com") -> openAiJsonResponse(
                    """{"reply":"قد يكون السعر 100 ريال","intent":"product_details","needs_store_data":true,"store_query":"ساعة نادرة"}"""
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Failure("Forbidden skill disabled", 403)
                else -> HttpTransport.Result.Failure("err", 500)
            }
        }

        val outcomeDisabled = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "نورة",
                messageText = "كم سعر الساعة النادرة؟"
            )
        )
        assertTrue(outcomeDisabled.needsHuman)
        assertFalse(outcomeDisabled.verifiedStoreDataUsed)
        assertFalse(outcomeDisabled.replyText!!.contains("100 ريال"))

        // Case B: 200 OK with has_verified_data=false
        transport.handler = { url, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success("""{"knowledge":true}""", 200, 3L)
                url.contains("gateway.example.com") -> openAiJsonResponse(
                    """{"reply":"السعر التقريبي 250","intent":"product_details","needs_store_data":true,"store_query":"حقيبة غير موجودة"}"""
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Success(
                    """{"has_verified_data":false,"intent":"product_details","summary":""}""",
                    200,
                    4L
                )
                else -> HttpTransport.Result.Failure("err", 500)
            }
        }
        val outcomeUnverified = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "نورة",
                messageText = "كم سعر الحقيبة؟"
            )
        )
        assertTrue(outcomeUnverified.needsHuman)
        assertFalse(outcomeUnverified.verifiedStoreDataUsed)
        assertFalse(outcomeUnverified.replyText!!.contains("250"))
    }

    // 6. Local context tracks multi-turn & isolates topic shifts cleanly
    @Test
    fun scenario_06_local_memory_retains_multi_turn_and_clears_old_topic_on_shift() = runTest {
        val convKey = "com.whatsapp|dm|سلطان"
        memoryManager.recordIncomingUserMessage(convKey, "com.whatsapp", false, "سلطان", "", "أريد عطر للشتاء", 6)
        memoryManager.commitAssistantTurnImmediately(convKey, "هل تفضله رجالي أم نسائي؟", 6)
        val afterSecondTurn = memoryManager.recordIncomingUserMessage(convKey, "com.whatsapp", false, "سلطان", "", "رجالي", 6)

        assertEquals("عطور", afterSecondTurn.currentTopic)
        assertTrue(afterSecondTurn.temporaryPreferencesJson.contains("رجالي"))
        assertTrue(memoryManager.buildTurnsForAi(afterSecondTurn, excludeLastUserMessage = true).size == 2)

        // Topic shifts cleanly to shoes -> old perfume preference / product wiped!
        val afterShift = memoryManager.recordIncomingUserMessage(convKey, "com.whatsapp", false, "سلطان", "", "أريد حذاء مقاس 42", 6)
        assertEquals("أحذية", afterShift.currentTopic)
        assertFalse(afterShift.temporaryPreferencesJson.contains("رجالي"))
        assertTrue(afterShift.temporaryPreferencesJson.contains("مقاس 42"))
    }

    // 7. SEND_HANDOFF defers assistant turn commit until Accessibility confirms Send click; TTL expiry discards it
    @Test
    fun scenario_07_deferred_turn_commits_only_on_accessibility_send_and_discards_on_ttl_expiry() = runTest {
        settingsDataStore.setAutoReplyEnabled(true)
        repository.upsertRule(
            ReplyRuleEntity(keyword = "العنوان", replyText = "الرياض - حي الملقا", matchType = MatchType.EXACT, isEnabled = true, priority = 10)
        )

        val handoffMsgKey = "handoff-turn-1"
        val launchPi = PendingIntent.getActivity(
            context,
            801,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val convKey = ConversationKey.build("com.whatsapp", "ليلى", "", null, false)

        val outcome = autoReplyEngine.processIncomingMessage(
            IncomingNotificationMessage(
                packageName = "com.whatsapp",
                senderName = "ليلى",
                messageText = "العنوان",
                isGroup = false,
                replyAction = null,
                contentIntent = launchPi,
                msgKey = handoffMsgKey,
                contentSendInvoker = { /* handoff opened app */ }
            )
        )
        assertTrue(outcome is LocalOutcome.Handled)
        assertTrue((outcome as LocalOutcome.Handled).isHandoff)

        // BEFORE Accessibility confirms Send, assistant turn MUST NOT be committed in turnsJson!
        val stagedCtx = db.conversationContextDao().getByKey(convKey)
        assertNotNull(stagedCtx)
        assertTrue(stagedCtx!!.pendingAssistantTurnJson.contains("الرياض - حي الملقا"))
        assertFalse(stagedCtx.turnsJson.contains("الرياض - حي الملقا"))

        // Accessibility finds matching open chat, input & Send button and clicks Send -> commits assistant turn!
        val serviceController = Robolectric.buildService(AutoReplyAccessibilityService::class.java).create()
        val service = serviceController.get()
        val rootNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.FrameLayout"
            isVisibleToUser = true
        }
        val titleNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.TextView"
            viewIdResourceName = "com.whatsapp:id/conversation_contact_name"
            text = "ليلى"
            isVisibleToUser = true
        }
        val editNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = EditText::class.java.name
            isVisibleToUser = true
            isEditable = true
        }
        Shadows.shadowOf(editNode).setOnPerformActionListener { _, _ -> true }
        val sendNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.ImageButton"
            viewIdResourceName = "com.whatsapp:id/send"
            contentDescription = "Send"
            isVisibleToUser = true
            isClickable = true
        }
        Shadows.shadowOf(sendNode).setOnPerformActionListener { _, _ -> true }
        Shadows.shadowOf(rootNode).addChild(titleNode)
        Shadows.shadowOf(rootNode).addChild(editNode)
        Shadows.shadowOf(rootNode).addChild(sendNode)

        val sent = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = rootNode,
            coordinatorOverride = pendingReplyCoordinator,
            idempotencyGuardOverride = idempotencyGuard,
            clockMs = { fakeNowMs },
            sendButtonDelayMs = 0L,
            memoryManagerOverride = memoryManager
        )
        assertTrue(sent)

        val committedCtx = db.conversationContextDao().getByKey(convKey)!!
        assertTrue(committedCtx.turnsJson.contains("الرياض - حي الملقا"))
        assertEquals("", committedCtx.pendingAssistantTurnJson)

        // Case B: TTL expiry discards staged turn without committing!
        val expiredKey = "handoff-turn-expired"
        memoryManager.stagePendingAssistantTurn(expiredKey, convKey, "رد منتهي الصلاحية")
        pendingReplyCoordinator.enqueue(
            PendingAccessibilityReply(
                id = 555L,
                msgKey = expiredKey,
                targetPackage = "com.whatsapp",
                senderName = "ليلى",
                replyText = "رد منتهي الصلاحية",
                createdAtMs = fakeNowMs
            )
        )
        val expiredSent = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = rootNode,
            coordinatorOverride = pendingReplyCoordinator,
            idempotencyGuardOverride = idempotencyGuard,
            clockMs = { fakeNowMs + PendingReplyCoordinator.REPLY_EXPIRY_MS + 1000L },
            sendButtonDelayMs = 0L,
            memoryManagerOverride = memoryManager
        )
        assertFalse(expiredSent)
        val afterExpiryCtx = db.conversationContextDao().getByKey(convKey)!!
        assertFalse(afterExpiryCtx.turnsJson.contains("رد منتهي الصلاحية"))
        assertEquals("", afterExpiryCtx.pendingAssistantTurnJson)

        serviceController.destroy()
    }

    // 8. Durable memory NEVER saved without explicit customer consent
    @Test
    fun scenario_08_durable_memory_blocked_without_customer_consent() = runTest {
        configureCollaboration(syncMemory = true, requireConsent = true)
        transport.handler = { url, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge":true,"memory":true,"consent_required":true}""",
                    200,
                    2L
                )
                url.contains("gateway.example.com") -> openAiJsonResponse(
                    """{"reply":"تم فهم تفضيلك للعطور الخشبية.","intent":"preference","needs_store_data":false,"memory_update":{"summary":"يفضل العطور الخشبية","preferences":["عطور خشبية"],"consent_confirmed":false}}"""
                )
                else -> HttpTransport.Result.Failure("unexpected $url", 500)
            }
        }

        val noConsentOutcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "عمر",
                phone = "+966501112233",
                messageText = "أفضل العطور الخشبية"
            )
        )

        assertFalse(noConsentOutcome.memoryUpdated)
        assertTrue(noConsentOutcome.consentRequiredForMemory)
        assertEquals(0, db.memoryOutboxDao().getPending().size)
        assertFalse(transport.calls.any { it.url.endsWith("/memory") })

        // Customer grants explicit consent -> now encrypted outbox queues cleanly
        val withConsentOutcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "عمر",
                phone = "+966501112233",
                messageText = "نعم موافق على حفظ تفضيلاتي"
            )
        )
        assertTrue(withConsentOutcome.memoryUpdated)
        assertEquals(1, db.memoryOutboxDao().getPending().size)
    }

    // 9. Outbox stores encrypted payload & never transmits raw turns, passwords, cards, or secrets
    @Test
    fun scenario_09_outbox_encrypts_payload_and_blocks_sensitive_credentials_or_raw_turns() = runTest {
        configureCollaboration(syncMemory = true, requireConsent = true)
        val convKey = "com.whatsapp|dm|عمر"
        memoryManager.recordIncomingUserMessage(convKey, "com.whatsapp", false, "عمر", "", "نعم موافق على الحفظ", 6)

        // Attempt with sensitive card/password must reject sensitive list items or block payload
        val blockedResult = outboxCoordinator.enqueueDurableMemoryUpdate(
            conversationKey = convKey,
            customerKey = "cust_omar",
            phone = "+966501112233",
            summary = "كلمة المرور 123456 ورمز التحقق OTP 9988 وبطاقة 4532111122223333",
            preferences = listOf("CVV 123"),
            interests = emptyList(),
            constraints = emptyList(),
            consentGranted = true,
            scheduleWorker = false
        )
        assertFalse(blockedResult.enqueued)

        val validResult = outboxCoordinator.enqueueDurableMemoryUpdate(
            conversationKey = convKey,
            customerKey = "cust_omar",
            phone = "+966501112233",
            summary = "يفضل التغليف الفاخر والتوصيل المسائي sk-new-api-secret-999",
            preferences = listOf("تغليف فاخر"),
            interests = emptyList(),
            constraints = emptyList(),
            consentGranted = true,
            scheduleWorker = false
        )
        assertTrue(validResult.enqueued)

        val row = db.memoryOutboxDao().getPending().single()
        // Payload is encrypted -> must NOT contain plaintext Arabic summary in DB column!
        assertFalse(row.encryptedPayload.contains("التوصيل المسائي"))
        assertFalse(row.encryptedPayload.contains("sk-new-api-secret-999"))
        val decrypted = OutboxCrypto.decrypt(row.encryptedPayload, secretVault)
        assertNotNull(decrypted)
        assertTrue(decrypted!!.contains("التوصيل المسائي"))
        assertFalse(decrypted.contains("sk-new-api-secret-999"))

        // Flush outbox -> verify Smart Advisor /memory request never has "turns" or vault keys
        var capturedMemoryBody = ""
        transport.handler = { url, _, _, body ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge":true,"memory":true,"consent_required":true}""",
                    200,
                    2L
                )
                url.endsWith("/memory") -> {
                    capturedMemoryBody = body.orEmpty()
                    HttpTransport.Result.Success("""{"ok":true,"revision":1}""", 200, 3L)
                }
                else -> HttpTransport.Result.Failure("unexpected", 500)
            }
        }
        assertEquals(1, outboxCoordinator.flushPending())
        assertFalse(capturedMemoryBody.contains("\"turns\""))
        assertFalse(capturedMemoryBody.contains("sk-new-api-secret-999"))
        assertTrue(capturedMemoryBody.contains("يفضل التغليف الفاخر"))
    }

    // 10. Monotonic revision rejects stale revisions & HTTP 409 conflict merges at serverRevision + 1
    @Test
    fun scenario_10_monotonic_revision_and_409_conflict_merge() = runTest {
        configureCollaboration(syncMemory = true, requireConsent = true)
        val convKey = "com.whatsapp|dm|مها"
        memoryManager.recordIncomingUserMessage(convKey, "com.whatsapp", false, "مها", "", "نعم موافق على الحفظ", 6)
        memoryManager.updateMetadataAfterSync(convKey, revision = 3L, summary = "ملخص سابق")

        // Stale revision <= 3 must be rejected cleanly
        val stale = outboxCoordinator.enqueueDurableMemoryUpdate(
            conversationKey = convKey,
            customerKey = "cust_maha",
            phone = "+966509998877",
            summary = "ملخص قديم",
            preferences = listOf("تغليف عادي"),
            interests = emptyList(),
            constraints = emptyList(),
            consentGranted = true,
            proposedRevision = 2L,
            scheduleWorker = false
        )
        assertFalse(stale.enqueued)
        assertEquals("stale_revision_rejected", stale.reason)

        // Fresh revision=4 hits HTTP 409 with server revision=5 -> merges and resends with revision=6
        val fresh = outboxCoordinator.enqueueDurableMemoryUpdate(
            conversationKey = convKey,
            customerKey = "cust_maha",
            phone = "+966509998877",
            summary = "ملخص محلي أحدث",
            preferences = listOf("تغليف هدايا"),
            interests = emptyList(),
            constraints = emptyList(),
            consentGranted = true,
            proposedRevision = 4L,
            scheduleWorker = false
        )
        assertTrue(fresh.enqueued)

        var saveAttempts = 0
        var finalRevisionSent = 0L
        transport.handler = { url, method, _, body ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success("""{"memory":true,"consent_required":true}""", 200, 2L)
                url.endsWith("/memory") && method == "POST" -> {
                    saveAttempts++
                    if (saveAttempts == 1) {
                        HttpTransport.Result.Conflict(
                            body = """{"revision":5,"memory":{"summary":"من الخادم","preferences":["توصيل صباحي"],"interests":[],"constraints":[]}}""",
                            code = 409,
                            ms = 4L
                        )
                    } else {
                        assertTrue(body!!.contains("\"revision\":6"))
                        assertTrue(body.contains("تغليف هدايا"))
                        assertTrue(body.contains("توصيل صباحي"))
                        finalRevisionSent = 6L
                        HttpTransport.Result.Success("""{"ok":true,"revision":6}""", 200, 4L)
                    }
                }
                else -> HttpTransport.Result.Failure("unexpected", 500)
            }
        }

        val flushed = outboxCoordinator.flushPending()
        assertEquals(1, flushed)
        assertEquals(6L, finalRevisionSent)
        val mergedRow = db.memoryOutboxDao().getLatestForConversation(convKey)!!
        assertEquals(MemoryOutboxEntity.STATE_CONFLICT_MERGED, mergedRow.state)
        assertEquals(6L, mergedRow.revision)
    }

    // 11. UnifiedReplyTester sandbox verifies priority chain & writes zero production DB rows
    @Test
    fun scenario_11_unified_tester_sandbox_priorities_and_zero_production_side_effects() = runTest {
        configureCollaboration(syncMemory = true)
        transport.handler = { url, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success("""{"knowledge":true,"memory":false}""", 200, 2L)
                url.contains("gateway.example.com") -> openAiJsonResponse(
                    """{"reply":"رد تعاون ذكي تجريبي","intent":"general","confidence":0.9,"needs_store_data":false}"""
                )
                else -> HttpTransport.Result.Failure("err", 500)
            }
        }

        db.menuReplyDao().insert(
            MenuReplyEntity(
                trigger = "القائمة",
                title = "اختر",
                optionsJson = json.encodeToString(listOf(MenuOption(1, "منتجات", "قائمة المنتجات"))),
                enabled = true
            )
        )
        repository.upsertRule(
            ReplyRuleEntity(keyword = "ساعات", replyText = "نعمل من 9 إلى 9", matchType = MatchType.CONTAINS, isEnabled = true, priority = 10)
        )
        repository.upsertRule(
            ReplyRuleEntity(keyword = "*", replyText = "قاعدة عامة", matchType = MatchType.EXACT, isEnabled = true, priority = 1)
        )

        // Order check: Menu > Explicit rule > Smart Collaboration AI
        assertEquals(ReplySourceCategory.MENU_REPLY, tester.evaluate("القائمة").sourceCategory)
        assertEquals(ReplySourceCategory.LOCAL_RULE, tester.evaluate("ما هي ساعات العمل؟").sourceCategory)
        val aiRes = tester.evaluate("أهلاً بك")
        assertEquals(ReplySourceCategory.AI_ENGINE, aiRes.sourceCategory)
        assertTrue(aiRes.usedNewApi)
        assertTrue(aiRes.stageLatenciesMs.isNotEmpty())

        // Zero production writes!
        assertEquals(0, db.replyLogDao().getLogsSince(0L).size)
        assertEquals(0, db.memoryOutboxDao().getPending().size)
        assertEquals(0, db.conversationContextDao().getAll().size)
    }

    // 12. Account actions allowed in DM (and login links scrubbed from DB/UI), blocked in Groups
    @Test
    fun scenario_12_account_actions_allowed_in_dm_scrubbed_in_logs_and_blocked_in_group() = runTest {
        settingsDataStore.setAutoReplyEnabled(true)
        settingsDataStore.setReplyInGroups(true)
        configureCollaboration(saEnabled = true)
        transport.handler = { url, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge":true,"memory":false,"account_actions":true}""",
                    200,
                    2L
                )
                url.contains("gateway.example.com") -> openAiJsonResponse(
                    """{"reply":"سأرسل لك رابط الدخول","intent":"account","account_action":"magic_login","needs_store_data":false}"""
                )
                url.endsWith("/account-action") -> HttpTransport.Result.Success(
                    """{"ok":true,"status":"link_sent","link_created":true,"ephemeral_login_url":"https://store.example.com/magic-login?token=TOP_SECRET_JWT_999"}""",
                    200,
                    5L
                )
                else -> HttpTransport.Result.Failure("unexpected", 500)
            }
        }

        // Group chat must block account actions safely
        val groupOutcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "زياد",
                groupName = "عملاء VIP",
                isGroup = true,
                messageText = "أرسل رابط تسجيل الدخول"
            )
        )
        assertEquals("account_action_blocked_group", groupOutcome.saIntent)
        assertFalse(transport.calls.any { it.url.endsWith("/account-action") })

        // Private DM executes magic_login & delivers link via notification reply while scrubbing log & context
        var deliveredReply = ""
        val dmOutcome = autoReplyEngine.processIncomingMessage(
            IncomingNotificationMessage(
                packageName = "com.whatsapp",
                senderName = "زياد",
                phone = "+966500112233",
                messageText = "أرسل لي رابط الدخول السريع",
                isGroup = false,
                replyAction = buildDirectReplyAction(),
                contentIntent = null,
                msgKey = "dm-magic-1",
                directSendInvoker = { _, msg -> deliveredReply = msg }
            )
        )
        assertTrue(dmOutcome is LocalOutcome.Handled)
        assertTrue(deliveredReply.contains("magic-login"))

        val logs = db.replyLogDao().getLogsSince(0L)
        assertEquals(1, logs.size)
        assertFalse(logs.single().sentReply.contains("TOP_SECRET_JWT_999"))
        assertFalse(SensitiveDataRedactor.scrub(deliveredReply, secretVault).contains("TOP_SECRET_JWT_999"))
    }

    // 13. HTTPS & loopback URL validation + secret redaction in health checks & errors
    @Test
    fun scenario_13_https_and_loopback_validation_and_secret_redaction() = runTest {
        configureCollaboration()
        val cleartextPrimary = AIReplyEngine.validateExternalUrl("http://gateway.example.com", "/v1/chat/completions")
        assertTrue(cleartextPrimary is AIReplyEngine.UrlValidationResult.Invalid)
        assertTrue((cleartextPrimary as AIReplyEngine.UrlValidationResult.Invalid).errorAr.contains("HTTPS"))

        val loopbackOllama = AIReplyEngine.validateOllamaEndpoint("https://localhost:11434")
        assertTrue(loopbackOllama is AIReplyEngine.UrlValidationResult.Invalid)
        assertTrue((loopbackOllama as AIReplyEngine.UrlValidationResult.Invalid).errorAr.contains("localhost"))

        val validOllama = AIReplyEngine.validateOllamaEndpoint("https://ollama.example.com")
        assertTrue(validOllama is AIReplyEngine.UrlValidationResult.Valid)
        assertEquals("https://ollama.example.com/api/chat", (validOllama as AIReplyEngine.UrlValidationResult.Valid).resolvedUrl)

        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("Unauthorized Bearer sk-new-api-secret-999 header X-SA-API-Key: sa-secret-key-888", 401)
        }
        val testRes = aiReplyEngine.testPrimaryConnection(aiSettingsPrefs.current())
        assertFalse(testRes.success)
        assertFalse(testRes.redactedError.orEmpty().contains("sk-new-api-secret-999"))
        assertFalse(testRes.redactedError.orEmpty().contains("sa-secret-key-888"))
    }

    // 14. Group vs Private ConversationKey isolation
    @Test
    fun scenario_14_group_and_private_conversation_keys_remain_strictly_isolated() = runTest {
        val dmKey = ConversationKey.build("com.whatsapp", "ياسر", phone = "+966555000111", isGroup = false)
        val groupKey = ConversationKey.build("com.whatsapp", "ياسر", groupName = "مجموعة العروض", isGroup = true)
        assertFalse(dmKey == groupKey)

        memoryManager.recordIncomingUserMessage(dmKey, "com.whatsapp", false, "ياسر", "+966555000111", "سؤال خاص عن طلبي", 6)
        memoryManager.recordIncomingUserMessage(groupKey, "com.whatsapp", true, "مجموعة العروض", "", "رسالة عامة بالمجموعة", 6)

        val dmCtx = db.conversationContextDao().getByKey(dmKey)!!
        val groupCtx = db.conversationContextDao().getByKey(groupKey)!!
        assertFalse(dmCtx.turnsJson.contains("بالمجموعة"))
        assertFalse(groupCtx.turnsJson.contains("طلبي"))
    }

    // 15. MessageQueueWorker recovery routes strictly through ReplyDispatcher and blocks DELIVERY_UNKNOWN
    @Test
    fun scenario_15_recovery_workers_route_through_dispatcher_and_respect_delivery_unknown() = runTest {
        val unknownKey = "worker-unknown-1"
        val failedConfirmedKey = "worker-failed-2"

        db.dedupKeyDao().insertIgnore(
            DedupKeyEntity(
                key = unknownKey,
                createdAt = fakeNowMs,
                state = DedupKeyEntity.STATE_DELIVERY_UNKNOWN,
                sendState = DedupKeyEntity.SEND_DELIVERY_UNKNOWN
            )
        )
        db.dedupKeyDao().insertIgnore(
            DedupKeyEntity(
                key = failedConfirmedKey,
                createdAt = fakeNowMs,
                state = DedupKeyEntity.STATE_FAILED_CONFIRMED,
                sendState = DedupKeyEntity.SEND_NONE
            )
        )

        db.replyLogDao().insertLog(
            ReplyLogEntity(
                id = 101L,
                senderName = "ناصر",
                packageName = "com.whatsapp",
                incomingMessage = "استفسار 1",
                sentReply = "",
                isGroup = false,
                deliveryMethod = MessageQueueWorker.STATUS_FAILED,
                timestamp = fakeNowMs,
                msgKey = unknownKey
            )
        )
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                id = 102L,
                senderName = "ريم",
                packageName = "com.whatsapp",
                incomingMessage = "استفسار 2",
                sentReply = "",
                isGroup = false,
                deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED,
                timestamp = fakeNowMs,
                msgKey = failedConfirmedKey
            )
        )

        configureCollaboration(saEnabled = true)
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success("""{"reply":"تمت المعالجة بأمان"}""", 200, 3L)
        }

        pendingReplyCoordinator.setAccessibilityConnected(false)

        MessageQueueWorker.executeQueueRetryWithDispatcher(
            logDao = db.replyLogDao(),
            dedupKeyDao = db.dedupKeyDao(),
            idempotencyGuard = idempotencyGuard,
            serverConfigRepository = serverConfigRepository,
            webhookClient = webhookClient,
            smartAdvisorClient = smartAdvisorClient,
            saEnabled = true,
            replyDispatcher = replyDispatcher,
            context = context
        )

        // unknownKey must NOT be retried over HTTP at all
        assertEquals(1, transport.calls.size)
        assertTrue(transport.calls.single().body!!.contains("استفسار 2"))

        // Because Accessibility was disconnected, ReplyDispatcher returned FailedConfirmed & updated dedup safely!
        val log2 = db.replyLogDao().getById(102L)!!
        assertEquals(MessageQueueWorker.STATUS_FAILED_CONFIRMED, log2.deliveryMethod)
        assertEquals(DedupKeyEntity.STATE_FAILED_CONFIRMED, db.dedupKeyDao().getByKey(failedConfirmedKey)?.state)
        assertEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, db.dedupKeyDao().getByKey(unknownKey)?.state)
    }
}
