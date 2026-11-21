package com.example

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.SettingsDataStore
import com.example.data.preferences.UnunderstoodFallbackMode
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.ConversationMemoryManager
import com.example.engine.DiagnosticEventTag
import com.example.engine.IdempotencyGuard
import com.example.engine.MenuReplyManager
import com.example.engine.MessageProcessingStatus
import com.example.engine.NotificationCoordinator
import com.example.engine.NotificationPayload
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import com.example.engine.SafeDiagnosticLogger
import com.example.engine.SmartCollaborationCoordinator
import com.example.engine.UnifiedReplyTester
import com.example.engine.UnifiedSmartReplyService
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.ui.screens.AISettingsScreen
import com.example.ui.theme.AutoMateTheme
import com.example.ui.viewmodel.AISettingsViewModel
import com.example.worker.FollowUpScheduler
import com.example.worker.MemoryOutboxCoordinator
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnunderstoodControlSuiteTest {

    @get:Rule
    val composeRule = createComposeRule()

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
    private lateinit var aiSharedPrefs: android.content.SharedPreferences
    private lateinit var aiSettingsPrefs: AiSettingsPrefs
    private lateinit var smartAdvisorPrefs: SmartAdvisorPrefs
    private lateinit var transport: RecordingTransport
    private lateinit var aiReplyEngine: AIReplyEngine
    private lateinit var webhookClient: WebhookClient
    private lateinit var smartAdvisorClient: SmartAdvisorClient
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var outboxCoordinator: MemoryOutboxCoordinator
    private lateinit var diagnosticLogger: SafeDiagnosticLogger
    private lateinit var coordinator: SmartCollaborationCoordinator
    private lateinit var idempotencyGuard: IdempotencyGuard
    private lateinit var pendingReplyCoordinator: PendingReplyCoordinator
    private lateinit var replyDispatcher: ReplyDispatcher
    private lateinit var unifiedService: UnifiedSmartReplyService
    private lateinit var autoReplyEngine: AutoReplyEngine
    private lateinit var notificationCoordinator: NotificationCoordinator
    private lateinit var tester: UnifiedReplyTester

    private var fakeNowMs: Long = 1_764_500_000_000L
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runCatching {
            WorkManager.initialize(
                context,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build()
            )
        }
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
            context.getSharedPreferences("uu_vault_$suffix", Context.MODE_PRIVATE)
        )
        aiSharedPrefs = context.getSharedPreferences("uu_ai_prefs_$suffix", Context.MODE_PRIVATE)
        aiSettingsPrefs = AiSettingsPrefs(context, aiSharedPrefs)
        smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("uu_sa_prefs_$suffix", Context.MODE_PRIVATE)
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
        diagnosticLogger = SafeDiagnosticLogger(secretVault, clockMs = { fakeNowMs })
        coordinator = SmartCollaborationCoordinator(
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiReplyEngine,
            smartAdvisorClient = smartAdvisorClient,
            smartAdvisorPrefs = smartAdvisorPrefs,
            conversationMemoryManager = memoryManager,
            memoryOutboxCoordinator = outboxCoordinator,
            secretVault = secretVault,
            serverRepo = serverConfigRepository,
            webhookClient = webhookClient,
            clockMs = { fakeNowMs },
            diagnosticLogger = diagnosticLogger
        )
        unifiedService = UnifiedSmartReplyService(
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
            clockMs = { fakeNowMs },
            diagnosticLogger = diagnosticLogger
        )

        idempotencyGuard = IdempotencyGuard(db.dedupKeyDao(), clockMs = { fakeNowMs })
        pendingReplyCoordinator = PendingReplyCoordinator(clockMs = { fakeNowMs })
        replyDispatcher = ReplyDispatcher(context, pendingReplyCoordinator, idempotencyGuard)
        val workManager = WorkManager.getInstance(context)
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
            smartCollaborationCoordinator = coordinator,
            injectedUnifiedService = unifiedService,
            clockMs = { fakeNowMs },
            diagnosticLogger = diagnosticLogger
        )
        notificationCoordinator = NotificationCoordinator(
            autoReplyEngine = autoReplyEngine,
            replyDispatcher = replyDispatcher,
            repository = repository,
            serverRepo = serverConfigRepository,
            webhookClient = webhookClient,
            smartAdvisorClient = smartAdvisorClient,
            smartAdvisorPrefs = smartAdvisorPrefs,
            idempotencyGuard = idempotencyGuard,
            rateLimiter = TokenBucketRateLimiter(10, 10) { fakeNowMs },
            workManager = WorkManager.getInstance(context),
            aiSettingsPrefs = aiSettingsPrefs,
            clockMs = { fakeNowMs },
            queueScheduler = {},
            conversationMemoryManager = memoryManager,
            diagnosticLogger = diagnosticLogger
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
            clockMs = { fakeNowMs },
            injectedUnifiedService = unifiedService
        )
    }

    @After
    fun tearDown() = runBlocking {
        resetSharedSettings()
        db.close()
    }

    private suspend fun resetSharedSettings() {
        settingsDataStore.setAutoReplyEnabled(true)
        settingsDataStore.setDrivingModeEnabled(false)
        settingsDataStore.setReplyDelaySeconds(0)
        settingsDataStore.setCooldownMinutes(0)
        settingsDataStore.setReplyInGroups(false)
    }

    private fun configureAiPrimary(
        suppressWhenUnunderstood: Boolean = true,
        fallbackMode: UnunderstoodFallbackMode = UnunderstoodFallbackMode.NO_REPLY,
        customUnunderstoodReply: String = AiSettingsState.DEFAULT_UNUNDERSTOOD_CUSTOM_REPLY,
        fallbackEnabled: Boolean = false,
        safeFallbackReply: String = "",
        saEnabled: Boolean = false
    ) {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "sk-test-newapi-secret-999")
        secretVault.put(AiSettingsPrefs.KEY_REF_OLLAMA, "sk-test-ollama-secret-888")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://newapi.example.com",
                primaryModel = "gpt-4o-mini",
                fallbackEnabled = fallbackEnabled,
                ollamaBaseUrl = "https://ollama.example.com",
                ollamaModel = "qwen2.5:1.5b",
                saCollaborationEnabled = saEnabled,
                safeFallbackReply = safeFallbackReply,
                useConversationContext = true,
                useKeywordRules = false,
                suppressReplyWhenUnunderstood = suppressWhenUnunderstood,
                ununderstoodFallbackMode = fallbackMode,
                ununderstoodCustomReply = customUnunderstoodReply
            )
        )
        if (saEnabled) {
            val saKeyRef = "sa_test_api_key_ref"
            secretVault.put(saKeyRef, "sa-secret-token-777")
            smartAdvisorPrefs.saveCredentials(
                baseUrl = "https://store.example.com/wp-json/smart-advisor/v1/whatsapp/reply",
                apiKeyRef = saKeyRef,
                enabled = true
            )
        }
    }

    private fun openAiJsonEnvelope(innerJsonString: String): String {
        return buildJsonObject {
            put("choices", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", innerJsonString)
                    })
                })
            })
        }.toString()
    }

    private fun dummyReplyAction(): Notification.Action {
        val remoteInput = RemoteInput.Builder("key_text_reply").setLabel("Reply").build()
        val pi = PendingIntent.getBroadcast(
            context,
            501,
            Intent("com.example.TEST_DIRECT_REPLY"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(0, "Reply", pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun waPayload(
        sbnKey: String,
        senderName: String,
        messageText: String,
        sendCounter: AtomicInteger,
        sentTexts: MutableList<String>
    ): NotificationPayload {
        return NotificationPayload(
            packageName = "com.whatsapp",
            sbnKey = sbnKey,
            notificationId = sbnKey,
            senderName = senderName,
            rawTitle = senderName,
            messageText = messageText,
            replyAction = dummyReplyAction(),
            directSendInvoker = { _, text ->
                sendCounter.incrementAndGet()
                sentTexts.add(text)
            }
        )
    }

    // 1. Understood message -> normal reply sent
    @Test
    fun scenario1_understoodMessage_sendsReplyNormally() = runTest {
        configureAiPrimary(suppressWhenUnunderstood = true)
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                body = openAiJsonEnvelope(
                    """{"intent":"store_hours","confidence":0.95,"needs_store_data":false,"reply":"نعمل يومياً من 9 صباحاً حتى 10 مساءً."}"""
                ),
                code = 200,
                ms = 25L
            )
        }

        val sentCount = AtomicInteger(0)
        val sentTexts = mutableListOf<String>()
        val result = notificationCoordinator.process(
            waPayload("sbn-1", "سارة", "ما هي ساعات العمل؟", sentCount, sentTexts)
        )

        assertTrue(result.isHandled)
        assertEquals(1, sentCount.get())
        assertEquals(listOf("نعمل يومياً من 9 صباحاً حتى 10 مساءً."), sentTexts)
    }

    // 2. Unknown message with suppress switch ON -> NO reply sent + diagnostic log
    @Test
    fun scenario2_unknownMessage_suppressOn_sendsZeroRepliesAndLogsSuppressed() = runTest {
        configureAiPrimary(suppressWhenUnunderstood = true, saEnabled = true)
        transport.handler = { url, _, _, _ ->
            if (url.contains("smart-advisor")) {
                HttpTransport.Result.Success(
                    body = """{"ok":true,"knowledge_enabled":true,"products":[]}""",
                    code = 200,
                    ms = 10L
                )
            } else {
                HttpTransport.Result.Success(
                    body = openAiJsonEnvelope(
                        """{"intent":"UNKNOWN_INTENT","confidence":0.18,"needs_store_data":true,"reply":""}"""
                    ),
                    code = 200,
                    ms = 20L
                )
            }
        }

        val sentCount = AtomicInteger(0)
        val sentTexts = mutableListOf<String>()
        val result = notificationCoordinator.process(
            waPayload("sbn-2", "عميل", "مممم هذا هو", sentCount, sentTexts)
        )

        assertFalse(result.isHandled)
        assertEquals("REPLY_SUPPRESSED", result.deliveryMethod)
        assertEquals(0, sentCount.get())
        assertTrue(sentTexts.isEmpty())
        assertFalse(transport.calls.any { it.url.contains("knowledge") })

        val events = diagnosticLogger.recentEvents()
        assertTrue(events.any { it.tag == DiagnosticEventTag.UNKNOWN_INTENT })
        assertTrue(events.any { it.tag == DiagnosticEventTag.REPLY_SUPPRESSED })

        val logs = db.replyLogDao().getLogsSince(0L)
        assertTrue(logs.any { it.deliveryMethod == AutoReplyEngine.DELIVERY_SKIPPED_UNKNOWN_INTENT })
    }

    // 3. Unknown message with suppress OFF + ASK_CLARIFICATION -> 1 clarification then loop prevention
    @Test
    fun scenario3_unknownMessage_suppressOff_askClarification_andLoopPrevention() = runTest {
        configureAiPrimary(
            suppressWhenUnunderstood = false,
            fallbackMode = UnunderstoodFallbackMode.ASK_CLARIFICATION
        )
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                body = openAiJsonEnvelope(
                    """{"intent":"UNKNOWN_INTENT","confidence":0.22,"needs_store_data":false,"reply":""}"""
                ),
                code = 200,
                ms = 15L
            )
        }

        val sentCount = AtomicInteger(0)
        val sentTexts = mutableListOf<String>()
        val first = notificationCoordinator.process(
            waPayload("sbn-3a", "فهد", "؟؟؟", sentCount, sentTexts)
        )
        assertTrue(first.isHandled)
        assertEquals(1, sentCount.get())
        assertEquals(AiSettingsState.DEFAULT_CLARIFICATION_QUESTION, sentTexts.single())

        val second = notificationCoordinator.process(
            waPayload("sbn-3b", "فهد", "ههه", sentCount, sentTexts)
        )
        assertFalse(second.isHandled)
        assertEquals("REPLY_SUPPRESSED", second.deliveryMethod)
        assertEquals(1, sentCount.get())
        assertTrue(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.FALLBACK_USED })
        assertTrue(
            diagnosticLogger.recentEvents().any {
                it.tag == DiagnosticEventTag.REPLY_SUPPRESSED &&
                    it.decision.contains("LOOP_PREVENTION")
            }
        )
    }

    // 4. Unknown message with suppress OFF + CUSTOM_REPLY -> sends custom message
    @Test
    fun scenario4_unknownMessage_suppressOff_customReply_sendsCustomText() = runTest {
        val customText = "أهلاً بك! لم يتضح طلبك، فضلاً اكتب اسم المنتج أو الخدمة."
        configureAiPrimary(
            suppressWhenUnunderstood = false,
            fallbackMode = UnunderstoodFallbackMode.CUSTOM_REPLY,
            customUnunderstoodReply = customText
        )
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                body = openAiJsonEnvelope(
                    """{"intent":"unclear","confidence":0.20,"needs_store_data":false,"reply":""}"""
                ),
                code = 200,
                ms = 15L
            )
        }

        val sentCount = AtomicInteger(0)
        val sentTexts = mutableListOf<String>()
        val result = notificationCoordinator.process(
            waPayload("sbn-4", "ريم", "ششيييبب", sentCount, sentTexts)
        )

        assertTrue(result.isHandled)
        assertEquals(1, sentCount.get())
        assertEquals(customText, sentTexts.single())
    }

    // 5. NewAPI failure (HTTP 502 and Timeout) with fallback OFF -> SERVICE_ERROR / TIMEOUT (never UNKNOWN_INTENT)
    @Test
    fun scenario5_newApiFailure_classifiedTechnicalError_notUnknownIntent() = runTest {
        configureAiPrimary(
            suppressWhenUnunderstood = false,
            fallbackMode = UnunderstoodFallbackMode.ASK_CLARIFICATION,
            fallbackEnabled = false
        )

        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("HTTP 502 Bad Gateway", code = 502, ms = 12L)
        }
        val res502 = tester.evaluate("كم سعر هذا المنتج؟")
        assertEquals(MessageProcessingStatus.SERVICE_ERROR, res502.processingClassification?.status)
        assertFalse(res502.suppressedByUnknownIntent)
        assertFalse(res502.replyText == AiSettingsState.DEFAULT_CLARIFICATION_QUESTION)

        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("SocketTimeoutException: timeout", code = 504, ms = 30000L)
        }
        val resTimeout = tester.evaluate("كم سعر هذا المنتج؟")
        assertEquals(MessageProcessingStatus.TIMEOUT, resTimeout.processingClassification?.status)
        assertFalse(resTimeout.suppressedByUnknownIntent)
        assertTrue(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.SERVICE_ERROR })
        assertTrue(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.TIMEOUT })
        assertFalse(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.UNKNOWN_INTENT })
    }

    // 6. Ollama failure -> treated as technical error, never UNKNOWN_INTENT
    @Test
    fun scenario6_ollamaFailure_notClassifiedUnknownIntent() = runTest {
        secretVault.put(AiSettingsPrefs.KEY_REF_OLLAMA, "sk-ollama-secret")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OLLAMA,
                ollamaBaseUrl = "https://ollama.example.com",
                ollamaModel = "qwen2.5:1.5b",
                fallbackEnabled = false,
                suppressReplyWhenUnunderstood = false,
                ununderstoodFallbackMode = UnunderstoodFallbackMode.ASK_CLARIFICATION
            )
        )
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("Connection refused", code = 503, ms = 20L)
        }

        val res = tester.evaluate("مرحباً")
        assertEquals(MessageProcessingStatus.SERVICE_ERROR, res.processingClassification?.status)
        assertFalse(res.suppressedByUnknownIntent)
        assertFalse(res.replyText == AiSettingsState.DEFAULT_CLARIFICATION_QUESTION)
        assertFalse(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.UNKNOWN_INTENT })
    }

    // 7. Both NewAPI and Ollama fail -> technical error / safeFallbackReply without loop or double send
    @Test
    fun scenario7_bothEnginesFail_usesTechnicalFallbackOnceWithoutUnknownClarification() = runTest {
        val safeTechnicalMsg = "نواجه ضغطاً مؤقتاً، سيتواصل معك فريقنا خلال دقائق."
        configureAiPrimary(
            suppressWhenUnunderstood = false,
            fallbackMode = UnunderstoodFallbackMode.ASK_CLARIFICATION,
            fallbackEnabled = true,
            safeFallbackReply = safeTechnicalMsg
        )
        transport.handler = { url, _, _, _ ->
            if (url.contains("newapi")) {
                HttpTransport.Result.Failure("Read timed out", code = 504, ms = 100L)
            } else {
                HttpTransport.Result.Failure("Ollama down", code = 503, ms = 50L)
            }
        }

        val sentCount = AtomicInteger(0)
        val sentTexts = mutableListOf<String>()
        val result = notificationCoordinator.process(
            waPayload("sbn-7", "خالد", "أين طلبي؟", sentCount, sentTexts)
        )

        assertTrue(result.isHandled)
        assertEquals(1, sentCount.get())
        assertEquals(safeTechnicalMsg, sentTexts.single())
        assertTrue(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.TIMEOUT })
        assertTrue(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.FALLBACK_USED })
        assertFalse(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.UNKNOWN_INTENT })
    }

    // 8. Empty HTTP 200 response -> INVALID_RESPONSE, never blindly UNKNOWN_INTENT
    @Test
    fun scenario8_emptyHttp200Response_classifiedInvalidResponse() = runTest {
        configureAiPrimary(
            suppressWhenUnunderstood = false,
            fallbackMode = UnunderstoodFallbackMode.ASK_CLARIFICATION,
            fallbackEnabled = false
        )
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                body = """{"choices":[{"message":{"role":"assistant","content":""}}]}""",
                code = 200,
                ms = 10L
            )
        }

        val res = tester.evaluate("كم سعر العطر؟")
        assertEquals(MessageProcessingStatus.INVALID_RESPONSE, res.processingClassification?.status)
        assertFalse(res.suppressedByUnknownIntent)
        assertFalse(res.replyText == AiSettingsState.DEFAULT_CLARIFICATION_QUESTION)
        assertFalse(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.UNKNOWN_INTENT })
    }

    // 9. Customer replies "نعم" after assistant question -> understood via memory context
    @Test
    fun scenario9_shortYesAfterQuestion_understoodViaContext() = runTest {
        configureAiPrimary(suppressWhenUnunderstood = true)
        var turn = 0
        transport.handler = { _, _, _, body ->
            turn++
            if (turn == 1) {
                HttpTransport.Result.Success(
                    body = openAiJsonEnvelope(
                        """{"intent":"product_inquiry","confidence":0.9,"reply":"لدينا عطر رجالي فاخر، هل ترغب بمعرفة السعر؟"}"""
                    ),
                    code = 200,
                    ms = 10L
                )
            } else {
                // Verify previous assistant question was included in messages sent to AI
                assertTrue(body.orEmpty().contains("هل ترغب بمعرفة السعر؟"))
                HttpTransport.Result.Success(
                    body = openAiJsonEnvelope(
                        """{"intent":"confirm_price_request","confidence":0.93,"reply":"سعر العطر الرجالي الفاخر هو 190 ريال."}"""
                    ),
                    code = 200,
                    ms = 10L
                )
            }
        }

        val convKey = "test_ctx_yes"
        val r1 = tester.evaluate("أريد عطر رجالي", conversationKey = convKey)
        assertTrue(r1.matched)

        val r2 = tester.evaluate("نعم", conversationKey = convKey)
        assertTrue(r2.matched)
        assertFalse(r2.suppressedByUnknownIntent)
        assertEquals(MessageProcessingStatus.UNDERSTOOD, r2.processingClassification?.status)
        assertTrue(r2.replyText.contains("190"))
    }

    // 10. Customer sends "أكثر" after product listing -> preserves context and exclude_ids
    @Test
    fun scenario10_moreAfterProductListing_preservesContextAndExcludeIds() = runTest {
        configureAiPrimary(suppressWhenUnunderstood = true, saEnabled = true)
        var saKnowledgeCalls = 0
        var lastSaBody = ""
        transport.handler = { url, _, _, body ->
            when {
                url.contains("capabilities") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"knowledge_enabled":true,"memory_enabled":false,"store_name":"متجر العطور"}""",
                    code = 200,
                    ms = 5L
                )
                url.contains("knowledge") -> {
                    saKnowledgeCalls++
                    lastSaBody = body.orEmpty()
                    if (saKnowledgeCalls == 1) {
                        HttpTransport.Result.Success(
                            body = """{"ok":true,"has_verified_data":true,"summary":"عطر العود الملكي 250 ريال","items":[{"id":101,"name":"عطر العود الملكي","price":"250","currency":"ريال","url":"https://store.example.com/p/101"}]}""",
                            code = 200,
                            ms = 15L
                        )
                    } else {
                        HttpTransport.Result.Success(
                            body = """{"ok":true,"has_verified_data":true,"summary":"عطر المسك الأبيض 180 ريال","items":[{"id":202,"name":"عطر المسك الأبيض","price":"180","currency":"ريال","url":"https://store.example.com/p/202"}]}""",
                            code = 200,
                            ms = 15L
                        )
                    }
                }
                else -> {
                    val userJson = json.parseToJsonElement(body!!).jsonObject
                    val messages = userJson["messages"]!!.jsonArray
                    val lastUser = messages.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }
                        .jsonObject["content"]!!.jsonPrimitive.content
                    if (messages.any { it.jsonObject["role"]!!.jsonPrimitive.content == "system" &&
                            it.jsonObject["content"]!!.jsonPrimitive.content.contains("نتائج الأدوات المسترجعة") }
                    ) {
                        HttpTransport.Result.Success(
                            body = openAiJsonEnvelope(
                                if (saKnowledgeCalls == 1) {
                                    """{"intent":"product_search","confidence":0.95,"reply":"عطر العود الملكي بسعر 250 ريال."}"""
                                } else {
                                    """{"intent":"product_search","confidence":0.94,"reply":"لدينا أيضاً عطر المسك الأبيض بسعر 180 ريال."}"""
                                }
                            ),
                            code = 200,
                            ms = 10L
                        )
                    } else {
                        HttpTransport.Result.Success(
                            body = openAiJsonEnvelope(
                                """{"intent":"product_search","confidence":0.92,"needs_store_data":true,"store_query":"${lastUser}","store_intent":"product_search","reply":""}"""
                            ),
                            code = 200,
                            ms = 10L
                        )
                    }
                }
            }
        }

        val convKey = "test_ctx_more"
        val t1 = tester.evaluate("أرني عطور", conversationKey = convKey)
        assertTrue(t1.matched)

        val t2 = tester.evaluate("أكثر", conversationKey = convKey)
        assertTrue(t2.matched)
        assertFalse(t2.suppressedByUnknownIntent)
        assertEquals(MessageProcessingStatus.UNDERSTOOD, t2.processingClassification?.status)
        assertTrue("Expected exclude_product_ids to contain 101, got: $lastSaBody", lastSaBody.contains("101"))
    }

    // 11. Duplicate message/notification -> blocked by IdempotencyGuard without re-running AI
    @Test
    fun scenario11_duplicateNotification_blockedAfterSuppressed() = runTest {
        configureAiPrimary(suppressWhenUnunderstood = true)
        var aiCalls = 0
        transport.handler = { _, _, _, _ ->
            aiCalls++
            HttpTransport.Result.Success(
                body = openAiJsonEnvelope(
                    """{"intent":"UNKNOWN_INTENT","confidence":0.15,"needs_store_data":false,"reply":""}"""
                ),
                code = 200,
                ms = 10L
            )
        }

        val sentCount = AtomicInteger(0)
        val sentTexts = mutableListOf<String>()
        val payload = waPayload("sbn-dup-1", "نور", "؟؟؟؟", sentCount, sentTexts)

        val first = notificationCoordinator.process(payload)
        assertEquals("REPLY_SUPPRESSED", first.deliveryMethod)

        val second = notificationCoordinator.process(payload)
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_DEDUP, second.deliveryMethod)
        assertEquals(1, aiCalls)
        assertEquals(0, sentCount.get())
        assertTrue(diagnosticLogger.recentEvents().any { it.tag == DiagnosticEventTag.DUPLICATE_BLOCKED })
    }

    // 12. Settings persistence across simulated restart
    @Test
    fun scenario12_settingsPersistAcrossRestart() {
        val vm1 = AISettingsViewModel(aiSettingsPrefs, secretVault, aiReplyEngine)
        vm1.setSuppressReplyWhenUnunderstood(false)
        vm1.selectUnunderstoodFallbackMode(UnunderstoodFallbackMode.CUSTOM_REPLY)
        vm1.updateUnunderstoodCustomReply("الرجاء توضيح اسم المنتج المطلوب.")
        vm1.saveSettings()

        val freshPrefs = AiSettingsPrefs(context, aiSharedPrefs)
        val vm2 = AISettingsViewModel(freshPrefs, secretVault, aiReplyEngine)
        val restored = vm2.uiState.value

        assertFalse(restored.suppressReplyWhenUnunderstood)
        assertEquals(UnunderstoodFallbackMode.CUSTOM_REPLY, restored.ununderstoodFallbackMode)
        assertEquals("الرجاء توضيح اسم المنتج المطلوب.", restored.ununderstoodCustomReply)
    }

    // 13. Runtime mode change applies immediately on next message
    @Test
    fun scenario13_runtimeModeChange_appliesImmediatelyOnNextMessage() = runTest {
        configureAiPrimary(
            suppressWhenUnunderstood = true,
            fallbackMode = UnunderstoodFallbackMode.NO_REPLY,
            customUnunderstoodReply = "رسالة مخصصة فورية"
        )
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                body = openAiJsonEnvelope(
                    """{"intent":"UNKNOWN_INTENT","confidence":0.1,"needs_store_data":false,"reply":""}"""
                ),
                code = 200,
                ms = 10L
            )
        }

        val r1 = tester.evaluate("ممم1", conversationKey = "conv_runtime")
        assertTrue(r1.suppressedByUnknownIntent)
        assertFalse(r1.matched)

        aiSettingsPrefs.save(
            aiSettingsPrefs.current().copy(
                suppressReplyWhenUnunderstood = false,
                ununderstoodFallbackMode = UnunderstoodFallbackMode.ASK_CLARIFICATION
            )
        )
        val r2 = tester.evaluate("ممم2", conversationKey = "conv_runtime")
        assertTrue(r2.matched)
        assertEquals(AiSettingsState.DEFAULT_CLARIFICATION_QUESTION, r2.replyText)

        aiSettingsPrefs.save(
            aiSettingsPrefs.current().copy(
                suppressReplyWhenUnunderstood = false,
                ununderstoodFallbackMode = UnunderstoodFallbackMode.CUSTOM_REPLY,
                ununderstoodCustomReply = "رد مخصص بعد التبديل"
            )
        )
        val r3 = tester.evaluate("ممم3", conversationKey = "conv_runtime")
        assertTrue(r3.matched)
        assertEquals("رد مخصص بعد التبديل", r3.replyText)
    }

    // 14. WhatsApp single-send guarantee + PII/secret scrubbing in diagnostics + Compose UI controls
    @Test
    fun scenario14_singleSend_diagnosticRedaction_andComposeUiControls() = runTest {
        configureAiPrimary(suppressWhenUnunderstood = true)
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                body = openAiJsonEnvelope(
                    """{"intent":"UNKNOWN_INTENT","confidence":0.12,"needs_store_data":false,"reply":""}"""
                ),
                code = 200,
                ms = 10L
            )
        }

        val sentCount = AtomicInteger(0)
        val sentTexts = mutableListOf<String>()
        val suppressedRes = notificationCoordinator.process(
            waPayload("sbn-14a", "سالم", "؟؟", sentCount, sentTexts)
        )
        assertEquals("REPLY_SUPPRESSED", suppressedRes.deliveryMethod)
        assertEquals(0, sentCount.get())

        // Verify all 7 diagnostic tags scrub secrets and PII
        DiagnosticEventTag.entries.forEach { tag ->
            diagnosticLogger.log(
                tag = tag,
                conversationId = "conv-ui",
                messageId = "msg-ui",
                detectedStatus = tag.name,
                decision = "sk-test-newapi-secret-999 password=mySecret99 user@example.com 0551234567",
                errorSource = "api_key=sk-test-newapi-secret-999",
                detail = "bearer sk-test-ollama-secret-888 phone 966501234567"
            )
        }
        val logged = diagnosticLogger.recentEvents()
        DiagnosticEventTag.entries.forEach { tag ->
            assertTrue("Missing tag $tag", logged.any { it.tag == tag })
        }
        logged.forEach { ev ->
            val combined = "${ev.decision} ${ev.errorSource} ${ev.detail}"
            assertFalse("Secret leaked: $combined", combined.contains("sk-test-newapi-secret-999"))
            assertFalse("Ollama secret leaked: $combined", combined.contains("sk-test-ollama-secret-888"))
            assertFalse("Password leaked: $combined", combined.contains("mySecret99"))
            assertFalse("Email leaked: $combined", combined.contains("user@example.com"))
            assertFalse("Phone leaked: $combined", combined.contains("0551234567"))
        }

        // Compose UI test for the new Ununderstood section in AISettingsScreen
        val vm = AISettingsViewModel(aiSettingsPrefs, secretVault, aiReplyEngine)
        composeRule.setContent {
            AutoMateTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AISettingsScreen(
                        onBack = {},
                        viewModel = vm
                    )
                }
            }
        }

        composeRule.onNodeWithTag("ai_settings_list")
            .performScrollToNode(hasTestTag("ai_ununderstood_suppress_switch"))
        composeRule.onNodeWithTag("ai_ununderstood_suppress_switch")
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()
            .assertIsOff()

        composeRule.onNodeWithTag("ai_settings_list")
            .performScrollToNode(hasTestTag("ai_ununderstood_mode_ASK_CLARIFICATION"))
        composeRule.onNodeWithTag("ai_ununderstood_mode_ASK_CLARIFICATION")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("ai_settings_list")
            .performScrollToNode(hasTestTag("ai_ununderstood_mode_CUSTOM_REPLY"))
        composeRule.onNodeWithTag("ai_ununderstood_mode_CUSTOM_REPLY")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("رد احتياطي مخصص").assertIsDisplayed()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_settings_list")
            .performScrollToNode(hasTestTag("ai_ununderstood_custom_reply_input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_ununderstood_custom_reply_input")
            .assertExists()
            .performTextReplacement("أهلاً بك، يرجى توضيح استفسارك.")
        composeRule.waitForIdle()

        assertFalse(aiSettingsPrefs.current().suppressReplyWhenUnunderstood)
        assertEquals(
            UnunderstoodFallbackMode.CUSTOM_REPLY,
            aiSettingsPrefs.current().ununderstoodFallbackMode
        )
    }
}
