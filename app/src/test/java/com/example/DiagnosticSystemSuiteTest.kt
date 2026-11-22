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
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ConversationContextEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.AutoMateSettings
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.ConversationKey
import com.example.engine.ConversationMemoryManager
import com.example.engine.DiagnosticAnomaly
import com.example.engine.DiagnosticAuthState
import com.example.engine.DiagnosticCategory
import com.example.engine.DiagnosticNetworkRecorder
import com.example.engine.DiagnosticOutcome
import com.example.engine.DiagnosticSanitizer
import com.example.engine.DiagnosticStage
import com.example.engine.DiagnosticTraceRecord
import com.example.engine.EncryptedDiagnosticStore
import com.example.engine.IdempotencyGuard
import com.example.engine.IncomingMessage
import com.example.engine.IncomingNotificationMessage
import com.example.engine.MenuReplyManager
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
import com.example.ui.screens.DiagnosticsScreenContent
import com.example.ui.screens.SaSettingsScreen
import com.example.ui.screens.ServerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AutoMateTheme
import com.example.ui.viewmodel.DiagnosticsUiState
import com.example.ui.viewmodel.SaSettingsViewModel
import com.example.worker.FollowUpScheduler
import com.example.worker.MemoryOutboxCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagnosticSystemSuiteTest {

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
    private lateinit var aiSettingsPrefs: AiSettingsPrefs
    private lateinit var smartAdvisorPrefs: SmartAdvisorPrefs
    private lateinit var transport: RecordingTransport
    private lateinit var diagnosticLogger: SafeDiagnosticLogger
    private lateinit var aiReplyEngine: AIReplyEngine
    private lateinit var webhookClient: WebhookClient
    private lateinit var smartAdvisorClient: SmartAdvisorClient
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var outboxCoordinator: MemoryOutboxCoordinator
    private lateinit var coordinator: SmartCollaborationCoordinator
    private lateinit var idempotencyGuard: IdempotencyGuard
    private lateinit var pendingReplyCoordinator: PendingReplyCoordinator
    private lateinit var replyDispatcher: ReplyDispatcher
    private lateinit var unifiedService: UnifiedSmartReplyService
    private lateinit var autoReplyEngine: AutoReplyEngine
    private lateinit var notificationCoordinator: NotificationCoordinator

    private var fakeNowMs: Long = 1_765_000_000_000L
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
        settingsDataStore.setAutoReplyEnabled(true)
        settingsDataStore.setDiagnosticLoggingEnabled(true)
        settingsDataStore.setUseAccessibilityFallback(false)

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
            context.getSharedPreferences("diag_vault_$suffix", Context.MODE_PRIVATE)
        )
        aiSettingsPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("diag_ai_$suffix", Context.MODE_PRIVATE)
        )
        smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("diag_sa_$suffix", Context.MODE_PRIVATE),
            useRuntimeDefaultUrl = true
        )

        File(context.filesDir, EncryptedDiagnosticStore.FILE_NAME).delete()

        diagnosticLogger = SafeDiagnosticLogger(
            secretVault = secretVault,
            clockMs = { fakeNowMs++ },
            context = context,
            initialDetailedEnabled = true,
            settingsDataStore = null
        )
        diagnosticLogger.clear()

        transport = RecordingTransport()
        aiReplyEngine = AIReplyEngine(transport, secretVault, diagnosticLogger)
        webhookClient = WebhookClient(transport, secretVault, smartAdvisorPrefs, diagnosticLogger)
        smartAdvisorClient = SmartAdvisorClient(
            transport = transport,
            vault = secretVault,
            baseUrlProvider = { smartAdvisorPrefs.baseUrl },
            apiKeyRefProvider = { smartAdvisorPrefs.apiKeyRef },
            timeoutSecondsProvider = { smartAdvisorPrefs.timeoutSeconds },
            maxAttemptsProvider = { smartAdvisorPrefs.maxAttempts },
            clockMs = { fakeNowMs++ },
            diagnosticLogger = diagnosticLogger
        )

        memoryManager = ConversationMemoryManager(
            contextDao = db.conversationContextDao(),
            secretVault = secretVault,
            clockMs = { fakeNowMs++ }
        )
        outboxCoordinator = MemoryOutboxCoordinator(
            outboxDao = db.memoryOutboxDao(),
            smartAdvisorClient = smartAdvisorClient,
            conversationMemoryManager = memoryManager,
            secretVault = secretVault,
            workManager = null,
            clockMs = { fakeNowMs++ }
        )
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
            clockMs = { fakeNowMs++ },
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
            clockMs = { fakeNowMs++ },
            diagnosticLogger = diagnosticLogger
        )

        idempotencyGuard = IdempotencyGuard(db.dedupKeyDao(), clockMs = { fakeNowMs++ })
        pendingReplyCoordinator = PendingReplyCoordinator(clockMs = { fakeNowMs++ })
        replyDispatcher = ReplyDispatcher(context, pendingReplyCoordinator, idempotencyGuard)
        autoReplyEngine = AutoReplyEngine(
            repository = repository,
            pendingReplyCoordinator = pendingReplyCoordinator,
            menuReplyManager = menuReplyManager,
            followUpScheduler = FollowUpScheduler(WorkManager.getInstance(context), db.followUpDao()),
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiReplyEngine,
            secretVault = secretVault,
            replyDispatcher = replyDispatcher,
            conversationMemoryManager = memoryManager,
            smartCollaborationCoordinator = coordinator,
            injectedUnifiedService = unifiedService,
            clockMs = { fakeNowMs++ },
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
            rateLimiter = TokenBucketRateLimiter(20, 20) { fakeNowMs++ },
            workManager = WorkManager.getInstance(context),
            aiSettingsPrefs = aiSettingsPrefs,
            clockMs = { fakeNowMs++ },
            queueScheduler = {},
            conversationMemoryManager = memoryManager,
            diagnosticLogger = diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        File(context.filesDir, EncryptedDiagnosticStore.FILE_NAME).delete()
        db.close()
    }

    private fun openAiChatReply(jsonContent: String): String =
        buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", jsonContent)
                    })
                })
            })
        }.toString()

    // Scenario 1: Missing key -> KEY_NOT_CONFIGURED, never marked SERVER_ACCEPTED
    @Test
    fun `01 missing key records KEY_NOT_CONFIGURED and never SERVER_ACCEPTED`() = runBlocking {
        smartAdvisorPrefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = "",
            enabled = true
        )
        val vm = SaSettingsViewModel(smartAdvisorPrefs, smartAdvisorClient, secretVault, diagnosticLogger)
        vm.testConnection()
        repeat(100) {
            ShadowLooper.idleMainLooper()
            if (!vm.isTesting.value && diagnosticLogger.traceRecordsSnapshot().isNotEmpty()) return@repeat
            Thread.sleep(10L)
        }
        ShadowLooper.idleMainLooper()

        val records = diagnosticLogger.traceRecordsSnapshot()
        assertTrue(records.any { it.authState == DiagnosticAuthState.KEY_NOT_CONFIGURED && it.outcome == DiagnosticOutcome.FAILURE })
        assertFalse(records.any { it.authState == DiagnosticAuthState.SERVER_ACCEPTED })
    }

    // Scenario 2: Valid key -> HEADER_ATTACHED locally -> SERVER_ACCEPTED only on server acceptance
    @Test
    fun `02 valid key records HEADER_ATTACHED then SERVER_ACCEPTED on server response`() = runTest {
        val secretKey = "SA_WA_ValidDiagKey_2026"
        secretVault.put(SaSettingsViewModel.SA_API_KEY_REF, secretKey)
        smartAdvisorPrefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = SaSettingsViewModel.SA_API_KEY_REF,
            enabled = true
        )
        transport.handler = { _, _, headers, _ ->
            assertEquals(secretKey, headers["X-SA-API-Key"])
            assertTrue(!headers["X-SA-Trace-Id"].isNullOrBlank())
            HttpTransport.Result.Success(
                """{"ok":true,"auth_status":"accepted","reply":"أهلاً بك","diagnostic_trace":{"trace_id":"${headers["X-SA-Trace-Id"]}","auth_status":"accepted","stage":"reply_composed","steps":["wp_received","auth_verified","reply_composed"]}}""",
                200,
                25L
            )
        }

        smartAdvisorClient.test(traceId = "tr-valid-auth-1")
        val traceRecords = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == "tr-valid-auth-1" }
        assertTrue(traceRecords.any { it.authState == DiagnosticAuthState.HEADER_ATTACHED })
        assertTrue(traceRecords.any { it.authState == DiagnosticAuthState.SERVER_ACCEPTED && it.outcome == DiagnosticOutcome.SUCCESS })
        assertFalse(diagnosticLogger.exportSanitizedReportJson().contains(secretKey))
    }

    // Scenario 3 & 4: Invalid key (403 sa_bad_key), 401 sa_missing_key, 503 sa_key_not_configured -> AUTH_REJECTED
    @Test
    fun `03 and 04 rejected server auth 403 401 503 record AUTH_REJECTED with redacted keys`() = runTest {
        val secretKey = "SA_WA_BadLeakTest_77"
        secretVault.put(SaSettingsViewModel.SA_API_KEY_REF, secretKey)
        smartAdvisorPrefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = SaSettingsViewModel.SA_API_KEY_REF,
            enabled = true
        )

        // 403 sa_bad_key
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("""{"code":"sa_bad_key","message":"bad key $secretKey"}""", 403, 18L)
        }
        smartAdvisorClient.test(traceId = "tr-403")
        val r403 = diagnosticLogger.traceRecordsSnapshot().first { it.traceId == "tr-403" && it.errorCode == "sa_bad_key" }
        assertEquals(DiagnosticAuthState.AUTH_REJECTED, r403.authState)
        assertEquals(DiagnosticOutcome.FAILURE, r403.outcome)
        assertFalse(r403.shortDescription.contains(secretKey))

        // 401 sa_missing_key
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("""{"code":"sa_missing_key","message":"مفتاح API مطلوب"}""", 401, 12L)
        }
        smartAdvisorClient.test(traceId = "tr-401")
        val r401 = diagnosticLogger.traceRecordsSnapshot().first { it.traceId == "tr-401" && it.errorCode == "sa_missing_key" }
        assertEquals(DiagnosticAuthState.AUTH_REJECTED, r401.authState)

        // 503 sa_key_not_configured
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("""{"code":"sa_key_not_configured","message":"غير مضبوط"}""", 503, 14L)
        }
        smartAdvisorClient.test(traceId = "tr-503")
        val r503 = diagnosticLogger.traceRecordsSnapshot().first { it.traceId == "tr-503" && it.errorCode == "sa_key_not_configured" }
        assertEquals(DiagnosticAuthState.AUTH_REJECTED, r503.authState)
    }

    // Scenario 5: 404 ENDPOINT_NOT_FOUND, Connection failure CONNECTION_FAILED, Timeout REQUEST_TIMEOUT
    @Test
    fun `05 endpoint 404, connection failure, and timeout map to exact DiagnosticAuthState values`() = runTest {
        val secretKey = "SA_WA_NetKey"
        secretVault.put(SaSettingsViewModel.SA_API_KEY_REF, secretKey)
        smartAdvisorPrefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = SaSettingsViewModel.SA_API_KEY_REF,
            enabled = true
        )

        transport.handler = { _, _, _, _ -> HttpTransport.Result.Failure("Not Found", 404, 15L) }
        smartAdvisorClient.test(traceId = "tr-net-404")
        val r404 = diagnosticLogger.traceRecordsSnapshot().first { it.traceId == "tr-net-404" && it.outcome == DiagnosticOutcome.FAILURE }
        assertEquals(DiagnosticAuthState.ENDPOINT_NOT_FOUND, r404.authState)

        transport.handler = { _, _, _, _ -> HttpTransport.Result.Failure("Connection refused", -1, 11L) }
        smartAdvisorClient.test(traceId = "tr-net-conn")
        val rConn = diagnosticLogger.traceRecordsSnapshot().first { it.traceId == "tr-net-conn" && it.outcome == DiagnosticOutcome.FAILURE }
        assertEquals(DiagnosticAuthState.CONNECTION_FAILED, rConn.authState)

        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("Read timed out", -1, 10_000L, cause = SocketTimeoutException("timeout"))
        }
        smartAdvisorClient.test(traceId = "tr-net-timeout")
        val rTimeout = diagnosticLogger.traceRecordsSnapshot().first { it.traceId == "tr-net-timeout" && it.outcome == DiagnosticOutcome.FAILURE }
        assertEquals(DiagnosticAuthState.REQUEST_TIMEOUT, rTimeout.authState)
    }

    // Scenario 6 & 7: NewAPI failure -> fallback to Qwen 2.5:1.5b; fallback disabled; both engines failing
    @Test
    fun `06 and 07 AI engine fallback transitions and dual failures record exact stages and anomalies`() = runTest {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "newapi-secret-key-xyz")
        val baseSettings = AiSettingsState(
            enabled = true,
            provider = AIReplyEngine.Provider.NEW_API,
            primaryBaseUrl = "https://primary.ai.example.com/v1/chat/completions",
            primaryModel = "gpt-4o-mini",
            fallbackEnabled = true,
            ollamaBaseUrl = "https://ollama.example.com/api/chat",
            ollamaModel = "qwen2.5:1.5b"
        )

        // Case A: Primary NewAPI fails 500 -> Fallback Qwen 2.5:1.5b succeeds
        transport.handler = { url, _, _, _ ->
            if (url.contains("primary.ai.example.com")) {
                HttpTransport.Result.Failure("502 Bad Gateway", 502, 120L)
            } else {
                HttpTransport.Result.Success(
                    openAiChatReply("""{"reply":"أهلاً! الشحن متاح اليوم.","intent":"shipping_inquiry","confidence":0.92}"""),
                    200,
                    90L
                )
            }
        }
        val resA = aiReplyEngine.runCollaborationTurn(
            settings = baseSettings,
            userMessage = "كم مدة الشحن؟",
            systemInstruction = "system",
            traceId = "tr-ai-fallback-ok"
        )
        assertTrue(resA.switchedToFallback)
        val recordsA = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == "tr-ai-fallback-ok" }
        assertTrue(recordsA.any {
            it.stage == DiagnosticStage.STAGE_07_NEWAPI_DISPATCHED &&
                it.outcome == DiagnosticOutcome.FAILURE &&
                DiagnosticAnomaly.PRIMARY_ENGINE_FAILED in it.anomalies
        })
        assertTrue(recordsA.any {
            it.stage == DiagnosticStage.STAGE_08_QWEN_FALLBACK_DISPATCHED &&
                it.fallbackTriggered &&
                it.engineUsed == "Qwen 2.5:1.5b" &&
                it.outcome == DiagnosticOutcome.SUCCESS
        })

        // Case B: Primary fails with fallback disabled -> FALLBACK_NOT_TRIGGERED
        transport.handler = { _, _, _, _ -> HttpTransport.Result.Failure("503 Unavailable", 503, 50L) }
        aiReplyEngine.runCollaborationTurn(
            settings = baseSettings.copy(fallbackEnabled = false),
            userMessage = "مرحبا",
            systemInstruction = "system",
            traceId = "tr-ai-no-fb"
        )
        val recordsB = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == "tr-ai-no-fb" }
        assertTrue(recordsB.any { DiagnosticAnomaly.FALLBACK_NOT_TRIGGERED in it.anomalies })

        // Case C: Both NewAPI & Qwen fail -> PRIMARY_ENGINE_FAILED + FALLBACK_ENGINE_FAILED
        transport.handler = { _, _, _, _ -> HttpTransport.Result.Failure("Server Down", 500, 30L) }
        aiReplyEngine.runCollaborationTurn(
            settings = baseSettings.copy(fallbackEnabled = true),
            userMessage = "مرحبا",
            systemInstruction = "system",
            traceId = "tr-ai-both-fail"
        )
        val recordsC = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == "tr-ai-both-fail" }
        assertTrue(recordsC.any { DiagnosticAnomaly.PRIMARY_ENGINE_FAILED in it.anomalies })
        assertTrue(recordsC.any { DiagnosticAnomaly.FALLBACK_ENGINE_FAILED in it.anomalies })
    }

    // Scenario 8: Empty reply & Invalid reply
    @Test
    fun `08 empty and invalid AI replies trigger EMPTY_REPLY and INVALID_REPLY anomalies`() = runTest {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "newapi-secret-key-8")
        val settings = AiSettingsState(
            enabled = true,
            provider = AIReplyEngine.Provider.NEW_API,
            primaryBaseUrl = "https://primary.ai.example.com/v1/chat/completions",
            fallbackEnabled = false
        )

        // Empty text from engine
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(openAiChatReply("   "), 200, 20L)
        }
        aiReplyEngine.runCollaborationTurn(settings, "مرحبا", "sys", traceId = "tr-empty-ai")
        val emptyRecords = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == "tr-empty-ai" }
        assertTrue(emptyRecords.any { DiagnosticAnomaly.EMPTY_REPLY in it.anomalies })

        // Invalid structured response (high-confidence blank reply without tools)
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(openAiChatReply("""{"reply":"","intent":"general","confidence":0.95}"""), 200, 20L)
        }
        aiReplyEngine.runCollaborationTurn(settings, "مرحبا", "sys", traceId = "tr-invalid-ai")
        val invalidRecords = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == "tr-invalid-ai" }
        assertTrue(invalidRecords.any { DiagnosticAnomaly.INVALID_REPLY in it.anomalies })
    }

    // Scenario 9: HTTP 200 with logical failure -> HTTP_200_LOGICAL_FAILURE
    @Test
    fun `09 HTTP 200 logical failure records HTTP_200_LOGICAL_FAILURE and non-SUCCESS outcome`() = runTest {
        val secretKey = "SA_WA_200Err"
        secretVault.put(SaSettingsViewModel.SA_API_KEY_REF, secretKey)
        smartAdvisorPrefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = SaSettingsViewModel.SA_API_KEY_REF,
            enabled = true
        )
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                """{"ok":false,"status":"error","error_code":"sa_offers_disabled","reply":"عذراً، أواجه مشكلة مؤقتة في الخدمة. حاول مرة أخرى بعد قليل."}""",
                200,
                40L
            )
        }
        smartAdvisorClient.test(traceId = "tr-http200-logical")
        val rec = diagnosticLogger.traceRecordsSnapshot().first {
            it.traceId == "tr-http200-logical" && it.httpStatusCode == 200
        }
        assertNotEquals(DiagnosticOutcome.SUCCESS, rec.outcome)
        assertTrue(DiagnosticAnomaly.HTTP_200_LOGICAL_FAILURE in rec.anomalies)
    }

    // Scenario 10: Memory retrieval failure & Context lost
    @Test
    fun `10 memory retrieval failure and context lost record MEMORY_RETRIEVAL_FAILED and CONTEXT_LOST`() = runTest {
        val secretKey = "SA_WA_MemKey"
        secretVault.put(SaSettingsViewModel.SA_API_KEY_REF, secretKey)
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "newapi-mem-key")
        smartAdvisorPrefs.saveCredentials(
            baseUrl = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            apiKeyRef = SaSettingsViewModel.SA_API_KEY_REF,
            enabled = true
        )
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://primary.ai.example.com/v1/chat/completions",
                fallbackEnabled = false,
                saCollaborationEnabled = true,
                syncCustomerMemory = true,
                useConversationContext = true
            )
        )

        // Seed corrupted JSON in Room conversation context so buildTurnsForAi returns empty despite non-empty turnsJson
        val convKey = ConversationKey.build("com.whatsapp", "966501112233", "966501112233", null, false)
        db.conversationContextDao().upsert(
            ConversationContextEntity(
                conversationKey = convKey,
                packageName = "com.whatsapp",
                displayName = "عميل اختبار",
                phone = "966501112233",
                isGroup = false,
                turnsJson = "{CORRUPTED_NON_ARRAY_HISTORY}",
                consentGranted = true,
                updatedAt = fakeNowMs
            )
        )

        transport.handler = { url, _, _, _ ->
            when {
                url.contains("assistant/capabilities") -> HttpTransport.Result.Success(
                    """{"ok":true,"knowledge":false,"memory":true,"account_actions":false,"consent_required":false}""",
                    200,
                    10L
                )
                url.contains("assistant/memory") -> HttpTransport.Result.Failure("DB timeout", 500, 15L)
                else -> HttpTransport.Result.Success(
                    openAiChatReply("""{"reply":"مرحباً! كيف أساعدك اليوم؟","intent":"general","confidence":0.9}"""),
                    200,
                    20L
                )
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "966501112233",
                phone = "966501112233",
                messageText = "ما هو طلبي السابق؟"
            )
        )
        val records = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == outcome.traceId }
        assertTrue(records.any { DiagnosticAnomaly.MEMORY_RETRIEVAL_FAILED in it.anomalies })
        assertTrue(records.any { DiagnosticAnomaly.CONTEXT_LOST in it.anomalies })
    }

    // Scenario 11 & 12: Duplicate message/reply, Reply loop, and Reply-question mismatch
    @Test
    fun `11 and 12 duplicate echo, reply loop, and reply-question mismatch trigger respective anomalies`() = runTest {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "newapi-anom-key")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://primary.ai.example.com/v1/chat/completions",
                fallbackEnabled = false,
                saCollaborationEnabled = false,
                useConversationContext = true
            )
        )

        // Seed previous assistant turn to trigger REPLY_LOOP_DETECTED
        val convKey = ConversationKey.build("com.whatsapp", "سارة", "", null, false)
        val repeatedReply = "أهلاً بك، التوصيل متاح إلى عنوانك داخل الرياض خلال يومين."
        db.conversationContextDao().upsert(
            ConversationContextEntity(
                conversationKey = convKey,
                packageName = "com.whatsapp",
                displayName = "سارة",
                turnsJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ConversationMemoryManager.StoredTurn.serializer()),
                    listOf(
                        ConversationMemoryManager.StoredTurn("user", "هل التوصيل متاح؟", fakeNowMs - 5000),
                        ConversationMemoryManager.StoredTurn("assistant", repeatedReply, fakeNowMs - 4000)
                    )
                ),
                updatedAt = fakeNowMs - 4000
            )
        )

        // Loop + Reply-Question Mismatch (user asks about استرجاع, assistant talks about التوصيل/العنوان loop)
        transport.handler = { _, _, _, _ ->
            HttpTransport.Result.Success(
                openAiChatReply("""{"reply":"$repeatedReply","intent":"return_policy","confidence":0.9}"""),
                200,
                20L
            )
        }
        val loopOutcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "سارة",
                messageText = "ما هي شروط الاسترجاع لديكم؟",
                messageId = "msg-loop-1"
            )
        )
        val loopRecords = diagnosticLogger.traceRecordsSnapshot().filter { it.traceId == loopOutcome.traceId }
        assertTrue(loopRecords.any { DiagnosticAnomaly.REPLY_LOOP_DETECTED in it.anomalies })
        assertTrue(loopRecords.any { DiagnosticAnomaly.REPLY_QUESTION_MISMATCH in it.anomalies })

        // Duplicate notification blocked in NotificationCoordinator (mark completed first, then replay)
        val dupMsgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "k-dup", "ما هي شروط الاسترجاع لديكم؟")
        assertTrue(idempotencyGuard.tryAcquireReservation(dupMsgKey, leaseDurationMs = 60_000L))
        idempotencyGuard.markCompleted(dupMsgKey)
        val remoteInput = RemoteInput.Builder("reply_key").build()
        val pendingIntent = PendingIntent.getActivity(context, 1, Intent("ACTION_REPLY"), PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(null, "Reply", pendingIntent).addRemoteInput(remoteInput).build()
        notificationCoordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "k-dup",
                notificationId = "dup-msg-99",
                senderName = "سارة",
                rawTitle = "سارة",
                messageText = "ما هي شروط الاسترجاع لديكم؟",
                replyAction = action
            )
        )
        assertTrue(
            diagnosticLogger.traceRecordsSnapshot().any { DiagnosticAnomaly.DUPLICATE_MESSAGE_OR_REPLY in it.anomalies }
        )
    }

    // Scenario 13: Reply returned to app without UI/dispatch -> REPLY_NOT_DISPLAYED_IN_UI
    @Test
    fun `13 reply failure on dispatch logs Stage 12 REPLY_NOT_DISPLAYED_IN_UI`() = runTest {
        // Trigger dispatch without any accessibility or live PendingIntent failure: expired/cancelled pending intent via AutoReplyEngine?
        // Even simpler and deterministic: test NotificationCoordinator external fallback when PendingIntent fails or throws
        // OR test UnifiedReplyTester / AutoReplyEngine when reply fails dispatch
        val convOutcomeReply = "مرحباً! السعر 120 ريال."
        // Direct stage 12 check via UnifiedReplyTester with matched=true on blank rendered output OR AutoReplyEngine with bad action:
        // Create a broken PendingIntent targeting nothing with canceled flag:
        val canceledPi = PendingIntent.getBroadcast(
            context,
            999,
            Intent("com.example.NONEXISTENT_ACTION"),
            PendingIntent.FLAG_IMMUTABLE
        )
        canceledPi.cancel()
        val remoteInput = RemoteInput.Builder("key_text").build()
        val replyAction = Notification.Action.Builder(null, "Reply", canceledPi).addRemoteInput(remoteInput).build()

        repository.upsertRule(
            com.example.data.local.entity.ReplyRuleEntity(
                keyword = "سعر",
                replyText = convOutcomeReply,
                isEnabled = true
            )
        )
        autoReplyEngine.processIncomingMessage(
            IncomingNotificationMessage(
                packageName = "com.whatsapp",
                senderName = "عميل",
                messageText = "كم سعر المنتج؟",
                isGroup = false,
                replyAction = replyAction,
                contentIntent = null,
                msgKey = "notif-fail-dispatch",
                directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") }
            )
        )

        val records = diagnosticLogger.traceRecordsSnapshot()
        assertTrue(
            records.any {
                it.stage == DiagnosticStage.STAGE_12_REPLY_DISPLAYED_TO_USER &&
                    DiagnosticAnomaly.REPLY_NOT_DISPLAYED_IN_UI in it.anomalies
            }
        )
    }

    // Scenario 14: Encrypted ring buffer caps at 100 events, persists across reload, Room untouched, and scrubs all secrets/PII
    @Test
    fun `14 encrypted ring buffer enforces 100-event cap, roundtrips across restart, and scrubs secrets and PII`() {
        val apiSecret = "SA_WA_TopSecret_8811"
        for (i in 1..115) {
            diagnosticLogger.logTrace(
                traceId = "tr-ring-$i",
                operationType = "ring_buffer_op",
                stage = DiagnosticStage.STAGE_09_ENGINE_RESPONSE_RECEIVED,
                outcome = DiagnosticOutcome.FAILURE,
                category = DiagnosticCategory.NETWORK_HTTP,
                rawUrl = "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply?api_key=$apiSecret&phone=+966501234567",
                description = "Err $i X-SA-API-Key=$apiSecret Bearer tokSecret9988 email=ahmed@test.com +966501234567",
                extraSecretsToRedact = listOf(apiSecret)
            )
        }

        val current = diagnosticLogger.traceRecordsSnapshot()
        assertEquals(100, current.size)
        assertEquals("tr-ring-115", current.first().traceId)

        // Confirm encrypted file on disk does not contain plaintext PII or API key
        val ringFile = File(context.filesDir, EncryptedDiagnosticStore.FILE_NAME)
        assertTrue(ringFile.exists())
        val rawEncrypted = ringFile.readText()
        assertFalse(rawEncrypted.contains("tr-ring-115"))
        assertFalse(rawEncrypted.contains(apiSecret))

        // Reload via fresh SafeDiagnosticLogger instance (simulating app restart)
        val reloadedLogger = SafeDiagnosticLogger(
            secretVault = secretVault,
            clockMs = { fakeNowMs++ },
            context = context,
            initialDetailedEnabled = false,
            settingsDataStore = null
        )
        val reloaded = reloadedLogger.traceRecordsSnapshot()
        assertEquals(100, reloaded.size)
        assertEquals("tr-ring-115", reloaded.first().traceId)

        // Export sanitized JSON contains zero secrets or PII
        val exported = reloadedLogger.exportSanitizedReportJson()
        assertFalse(exported.contains(apiSecret))
        assertFalse(exported.contains("tokSecret9988"))
        assertFalse(exported.contains("ahmed@test.com"))
        assertFalse(exported.contains("501234567"))
        val parsed = json.parseToJsonElement(exported).jsonObject
        assertEquals(100, parsed["totalEvents"]?.jsonPrimitive?.content?.toInt())
    }

    // Scenario 15: Concurrent multi-conversation processing uses independent traceIds without mixing
    @Test
    fun `15 concurrent multi-conversation flows maintain isolated traceIds with zero cross-contamination`() = runBlocking {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "newapi-conc-key")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://primary.ai.example.com/v1/chat/completions",
                fallbackEnabled = false,
                saCollaborationEnabled = false
            )
        )
        transport.handler = { _, _, _, body ->
            val reply = if (body.orEmpty().contains("أحمد")) {
                """{"reply":"مرحباً أحمد، شحنتك قيد التجهيز.","intent":"order_status","confidence":0.95}"""
            } else {
                """{"reply":"مرحباً ليلى، الاسترجاع خلال 14 يوماً.","intent":"return_policy","confidence":0.95}"""
            }
            HttpTransport.Result.Success(openAiChatReply(reply), 200, 15L)
        }

        val job1 = async {
            coordinator.collaborate(
                SmartCollaborationCoordinator.CollaborationInput(
                    packageName = "com.whatsapp",
                    senderName = "أحمد",
                    messageText = "أنا أحمد، أين شحنتي؟"
                )
            )
        }
        val job2 = async {
            coordinator.collaborate(
                SmartCollaborationCoordinator.CollaborationInput(
                    packageName = "com.whatsapp",
                    senderName = "ليلى",
                    messageText = "أنا ليلى، ما هي شروط الاسترجاع؟"
                )
            )
        }
        val out1 = job1.await()
        val out2 = job2.await()

        assertNotEquals(out1.traceId, out2.traceId)
        val traces = diagnosticLogger.traceRecordsSnapshot()
        val t1Ops = traces.filter { it.traceId == out1.traceId }.map { it.stage }
        val t2Ops = traces.filter { it.traceId == out2.traceId }.map { it.stage }
        assertTrue(t1Ops.contains(DiagnosticStage.STAGE_01_USER_MESSAGE_SENT))
        assertTrue(t1Ops.contains(DiagnosticStage.STAGE_10_REPLY_COMPOSED))
        assertTrue(t2Ops.contains(DiagnosticStage.STAGE_01_USER_MESSAGE_SENT))
        assertTrue(t2Ops.contains(DiagnosticStage.STAGE_10_REPLY_COMPOSED))
    }

    // Scenario 16a: Compose UI verification of SettingsScreen Section 8 ONLY
    @Test
    fun `16a SettingsScreen exposes dedicated diagnostics section toggle, navigation, and clear`() {
        var toggledState = false
        var openedDiagnostics = false
        var clearedDiagnostics = false

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AutoMateTheme {
                    SettingsScreen(
                        settings = AutoMateSettings(diagnosticLoggingEnabled = toggledState),
                        contactFilterCount = 0,
                        onNavigateToContactsFilter = {},
                        onSetReplyInGroups = {},
                        onSetDelaySeconds = {},
                        onSetCooldownMinutes = {},
                        onSetScheduleEnabled = {},
                        onSetScheduleWindow = { _, _, _, _ -> },
                        onSetAutoDetectDriving = {},
                        onUpdateDrivingReplyText = {},
                        onTogglePackage = { _, _ -> },
                        onSetAccessibilityFallback = {},
                        onExportJson = {},
                        onImportJson = { _, _ -> },
                        diagnosticEventsCount = 4,
                        diagnosticErrorsCount = 2,
                        onSetDiagnosticLogging = { toggledState = it },
                        onNavigateToDiagnostics = { openedDiagnostics = true },
                        onClearDiagnosticLogs = { clearedDiagnostics = true }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("clear_diagnostics_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostic_logging_switch", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("تفعيل التسجيل التشخيصي التفصيلي").assertExists()
        composeRule.onNodeWithTag("open_diagnostics_row").performClick()
        assertTrue(openedDiagnostics)
        composeRule.onNodeWithTag("clear_diagnostics_btn").performClick()
        assertTrue(clearedDiagnostics)
    }

    // Scenario 16b: Compose UI verification of DiagnosticsScreenContent filtering & event cards
    @Test
    fun `16b DiagnosticsScreenContent renders event cards and outcome filters`() {
        val sampleRecords = listOf(
            DiagnosticTraceRecord(
                id = "r1",
                traceId = "tr-ui-fail",
                timestampMs = fakeNowMs,
                operationType = "sa_test",
                httpMethod = "POST",
                sanitizedUrlPath = "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply",
                latencyMs = 120L,
                httpStatusCode = 403,
                stage = DiagnosticStage.STAGE_03_AUTH_VERIFIED,
                authState = DiagnosticAuthState.AUTH_REJECTED,
                errorCode = "sa_bad_key",
                shortDescription = "مفتاح API مرفوض من الخادم",
                engineUsed = "NewAPI",
                fallbackTriggered = false,
                outcome = DiagnosticOutcome.FAILURE,
                category = DiagnosticCategory.AUTH
            ),
            DiagnosticTraceRecord(
                id = "r2",
                traceId = "tr-ui-ok",
                timestampMs = fakeNowMs - 1000L,
                operationType = "ai_collaboration_turn",
                httpMethod = "POST",
                sanitizedUrlPath = "https://ollama.example.com/api/chat",
                latencyMs = 210L,
                httpStatusCode = 200,
                stage = DiagnosticStage.STAGE_08_QWEN_FALLBACK_DISPATCHED,
                authState = DiagnosticAuthState.NONE,
                shortDescription = "تم التحويل للاحتياطي بنجاح",
                engineUsed = "Qwen 2.5:1.5b",
                fallbackTriggered = true,
                outcome = DiagnosticOutcome.SUCCESS,
                category = DiagnosticCategory.AI_ENGINE
            )
        )

        var selectedOutcome: DiagnosticOutcome? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AutoMateTheme {
                    DiagnosticsScreenContent(
                        uiState = DiagnosticsUiState(
                            detailedLoggingEnabled = false,
                            allRecords = sampleRecords,
                            filteredRecords = sampleRecords,
                            availableEngines = listOf("NewAPI", "Qwen 2.5:1.5b"),
                            totalCount = 2,
                            failureCount = 1,
                            unknownCount = 0,
                            successCount = 1,
                            countsByCategory = mapOf(
                                DiagnosticCategory.AUTH to 1,
                                DiagnosticCategory.AI_ENGINE to 1
                            )
                        ),
                        onBack = {},
                        onOutcomeFilter = { selectedOutcome = it },
                        onCategoryFilter = {},
                        onEngineFilter = {},
                        onSelectRecord = {},
                        onClearLogs = {},
                        onCopyReport = {},
                        onCopySingleRecord = {},
                        onExportReport = {},
                        onConsumeStatusMessage = {}
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("سجل التشخيص والأخطاء").assertIsDisplayed()
        composeRule.onNodeWithTag("diag_filter_outcome_FAILURE").performClick()
        assertEquals(DiagnosticOutcome.FAILURE, selectedOutcome)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("diag_record_card_r1"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diag_record_card_r1").assertIsDisplayed()
    }

    // Scenario 16c: Confirm SaSettingsScreen does NOT display any diagnostics shortcut
    @Test
    fun `16c SaSettingsScreen does not display any diagnostics shortcut`() {
        val saVm = SaSettingsViewModel(smartAdvisorPrefs, smartAdvisorClient, secretVault, diagnosticLogger)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AutoMateTheme {
                    SaSettingsScreen(onBack = {}, viewModel = saVm)
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("سجل التشخيص والأخطاء").fetchSemanticsNodes().isEmpty())
    }
}
