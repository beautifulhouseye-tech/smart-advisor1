package com.example

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.RequestField
import com.example.data.local.entity.FieldType
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.local.entity.WebhookMode
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.IdempotencyGuard
import com.example.engine.MenuReplyManager
import com.example.engine.NotificationCoordinator
import com.example.engine.NotificationPayload
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import com.example.engine.ReplySourceCategory
import com.example.engine.SmartCollaborationCoordinator
import com.example.engine.ConversationMemoryManager
import com.example.engine.UnifiedReplyTester
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.ui.viewmodel.ServerViewModel
import com.example.worker.FollowUpScheduler
import com.example.worker.MemoryOutboxCoordinator
import com.example.worker.MessageQueueWorker
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiOrchestratorToolsTest {

    private class RecordingTransport(
        var handler: suspend (
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
            idempotencyKey: String?
        ) -> Result = { _, _, _, _, _ -> Result.Failure("not stubbed", 500) }
    ) : HttpTransport() {
        data class Call(
            val url: String,
            val method: String,
            val headers: Map<String, String>,
            val body: String?,
            val idempotencyKey: String?
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
            calls += Call(url, method, headers, body, idempotencyKey)
            return handler(url, method, headers, body, idempotencyKey)
        }
    }

    private lateinit var context: Context
    private lateinit var db: AutoMateDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var repository: AutoMateRepository
    private lateinit var serverRepo: ServerConfigRepository
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
    private lateinit var notificationCoordinator: NotificationCoordinator
    private lateinit var tester: UnifiedReplyTester

    private var fakeNowMs: Long = 1_763_500_000_000L
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
        serverRepo = ServerConfigRepositoryImpl(db.serverConfigDao())
        menuReplyManager = MenuReplyManager(db.menuReplyDao(), json)

        val suffix = System.nanoTime()
        secretVault = SecretVault(
            context,
            context.getSharedPreferences("orch_vault_$suffix", Context.MODE_PRIVATE)
        )
        aiSettingsPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("orch_ai_$suffix", Context.MODE_PRIVATE)
        )
        smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("orch_sa_$suffix", Context.MODE_PRIVATE)
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
            serverRepo = serverRepo,
            webhookClient = webhookClient,
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
        notificationCoordinator = NotificationCoordinator(
            autoReplyEngine = autoReplyEngine,
            replyDispatcher = replyDispatcher,
            repository = repository,
            serverRepo = serverRepo,
            webhookClient = webhookClient,
            smartAdvisorClient = smartAdvisorClient,
            smartAdvisorPrefs = smartAdvisorPrefs,
            idempotencyGuard = idempotencyGuard,
            rateLimiter = TokenBucketRateLimiter(capacity = 50, refillRatePerMinute = 50),
            workManager = workManager,
            aiSettingsPrefs = aiSettingsPrefs,
            clockMs = { fakeNowMs },
            queueScheduler = null
        )
        tester = UnifiedReplyTester(
            repository = repository,
            menuReplyManager = menuReplyManager,
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiReplyEngine,
            secretVault = secretVault,
            serverRepo = serverRepo,
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
        settingsDataStore.setDrivingModeReplyText("")
        settingsDataStore.setReplyInGroups(false)
        settingsDataStore.setCooldownMinutes(0)
        settingsDataStore.toggleSupportedPackage("com.whatsapp", true)
    }

    private fun openAiPayload(jsonContent: String): String {
        return buildJsonObject {
            put("choices", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("content", jsonContent)
                    })
                })
            })
        }.toString()
    }

    private suspend fun configureOrchestratorEnvironment(
        webhookMode: WebhookMode = WebhookMode.AI_TOOL,
        webhookEnabled: Boolean = true,
        saEnabled: Boolean = true,
        aiEnabled: Boolean = true,
        safeFallbackReply: String = ""
    ) {
        settingsDataStore.setAutoReplyEnabled(true)
        settingsDataStore.toggleSupportedPackage("com.whatsapp", true)
        settingsDataStore.setCooldownMinutes(0)
        settingsDataStore.setReplyInGroups(true)

        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.NEW_API), "sk-newapi-secret")
        secretVault.put("sa_api_key_ref", "sa-secret-token")
        secretVault.put("wh_secret_ref", "wh-secret-999")

        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = aiEnabled,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://gateway.example.com",
                primaryModel = "gpt-4o-mini",
                fallbackEnabled = true,
                ollamaBaseUrl = "https://qwen.example.com/v1",
                ollamaModel = "qwen2.5:7b",
                saCollaborationEnabled = saEnabled,
                syncCustomerMemory = false,
                requireCustomerConsent = true,
                safeFallbackReply = safeFallbackReply
            )
        )

        smartAdvisorPrefs.saveCredentials(
            baseUrl = "https://store.example.com/wp-json/smart-advisor/v1/whatsapp/reply",
            apiKeyRef = "sa_api_key_ref",
            enabled = saEnabled
        )

        val headers = listOf(
            HeaderItem(key = "Authorization", valueRef = "wh_secret_ref", isSecret = true),
            HeaderItem(key = "X-Client", valueRef = "AutoMate-AI", isSecret = false)
        )
        val fields = listOf(
            RequestField(key = "sender", valueTemplate = "{{sender}}", type = FieldType.STRING, enabled = true),
            RequestField(key = "message", valueTemplate = "{{message}}", type = FieldType.STRING, enabled = true),
            RequestField(key = "query", valueTemplate = "{{ai_query}}", type = FieldType.STRING, enabled = true)
        )
        serverRepo.save(
            ServerConfigEntity(
                id = 1,
                isEnabled = webhookEnabled,
                url = "https://erp.example.com/webhook",
                method = "POST",
                contentType = "JSON",
                headersJson = json.encodeToString(headers),
                requestFieldsJson = json.encodeToString(fields),
                responseReplyKey = "reply",
                timeoutSeconds = 20,
                retryCount = 2,
                sendOnGroup = "KEYWORD_ONLY",
                sendOnPrivate = true,
                webhookMode = webhookMode.name,
                toolDescription = "الاستعلام عن حالة الطلبات والشحنات في نظام ERP",
                toolMaxResponseChars = 2000
            )
        )
    }

    // 1. General greeting -> AI replies directly without calling Smart Advisor or Webhook
    @Test
    fun scenario_01_general_greeting_replies_directly_without_calling_tools() = runTest {
        configureOrchestratorEnvironment()

        transport.handler = { url, _, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.contains("gateway.example.com") -> HttpTransport.Result.Success(
                    openAiPayload(
                        """{"intent":"greeting","confidence":0.95,"needs_store_data":false,"needs_webhook_data":false,"reply":"وعليكم السلام ورحمة الله، أهلاً بك! كيف أقدر أساعدك اليوم؟"}"""
                    ),
                    200,
                    10L
                )
                else -> HttpTransport.Result.Failure("unexpected tool call $url", 500)
            }
        }

        val res = tester.evaluate("السلام عليكم")
        assertTrue(res.matched)
        assertFalse(res.calledSmartAdvisor)
        assertFalse(res.calledWebhook)
        assertEquals(0, res.toolRoundsUsed)
        assertTrue(res.invokedTools.isEmpty())
        assertTrue(res.replyText.contains("أهلاً بك"))
        assertFalse(
            transport.calls.any {
                it.url.endsWith("/knowledge") ||
                    it.url.endsWith("/account-action") ||
                    it.url.endsWith("/whatsapp/reply") ||
                    it.url.contains("erp.example.com")
            }
        )
    }

    // 2. Store question -> AI invokes Smart Advisor only and uses verified WooCommerce facts
    @Test
    fun scenario_02_store_question_invokes_smart_advisor_only() = runTest {
        configureOrchestratorEnvironment()
        var aiPass = 0
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Success(
                    """{"ok":true,"has_verified_data":true,"summary":"عطر العود الملكي متوفر بسعر 240 ريال في المخزون (12 قطعة).","facts":["السعر 240 ريال","الشحن مجاني فوق 200 ريال"]}""",
                    200,
                    12L
                )
                url.contains("gateway.example.com") -> {
                    aiPass++
                    if (aiPass == 1) {
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"product_details","confidence":0.9,"needs_store_data":true,"store_query":"عطر العود الملكي","store_intent":"product_details","needs_webhook_data":false,"reply":""}"""
                            ),
                            200,
                            15L
                        )
                    } else {
                        assertTrue("Synthesis prompt must receive SA verified note", body.orEmpty().contains("240 ريال"))
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"product_details","confidence":0.98,"needs_human":false,"reply":"عطر العود الملكي متوفر حالياً بسعر 240 ريال، والشحن مجاني لطلبك."}"""
                            ),
                            200,
                            18L
                        )
                    }
                }
                else -> HttpTransport.Result.Failure("unexpected call $url", 500)
            }
        }

        val res = tester.evaluate("كم سعر عطر العود الملكي وهل متوفر؟")
        assertTrue(res.matched)
        assertTrue(res.calledSmartAdvisor)
        assertFalse(res.calledWebhook)
        assertTrue(res.verifiedStoreDataUsed)
        assertEquals(1, res.toolRoundsUsed)
        assertEquals(listOf("Smart Advisor"), res.invokedTools)
        assertEquals("عطر العود الملكي متوفر حالياً بسعر 240 ريال، والشحن مجاني لطلبك.", res.replyText)
        assertFalse(transport.calls.any { it.url.contains("erp.example.com") })
    }

    // 3. External system question -> AI calls Webhook tool with custom secret headers + idempotency + {{ai_query}}
    @Test
    fun scenario_03_external_question_calls_webhook_tool_with_headers_and_idempotency() = runTest {
        configureOrchestratorEnvironment()
        var aiPass = 0
        transport.handler = { url, _, headers, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.contains("erp.example.com") -> {
                    assertEquals("wh-secret-999", headers["Authorization"])
                    assertEquals("AutoMate-AI", headers["X-Client"])
                    assertTrue(body.orEmpty().contains("ORDER-7788"))
                    HttpTransport.Result.Success(
                        """{"reply":"الشحنة #7788 في مستودع الرياض والتسليم غداً صباحاً"}""",
                        200,
                        15L
                    )
                }
                url.contains("gateway.example.com") -> {
                    aiPass++
                    if (aiPass == 1) {
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"order_status","confidence":0.9,"needs_store_data":false,"needs_webhook_data":true,"webhook_query":"ORDER-7788","webhook_reason":"التحقق من حالة الشحنة من ERP","reply":""}"""
                            ),
                            200,
                            12L
                        )
                    } else {
                        assertTrue(body.orEmpty().contains("الشحنة #7788 في مستودع الرياض"))
                        assertTrue(body.orEmpty().contains("مصدر خارجي غير متحقق منه"))
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"order_status","confidence":0.95,"needs_human":false,"reply":"حسب نظام الشحن الخارجي، الشحنة #7788 وصلت مستودع الرياض وموعد تسليمها غداً صباحاً."}"""
                            ),
                            200,
                            14L
                        )
                    }
                }
                else -> HttpTransport.Result.Failure("unexpected $url", 500)
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "خالد",
                messageText = "وين وصلت شحنتي رقم ORDER-7788؟",
                isSandbox = false,
                messageId = "msg-idemp-7788"
            )
        )
        assertFalse(outcome.calledSmartAdvisor)
        assertTrue(outcome.calledWebhook)
        assertFalse(outcome.verifiedStoreDataUsed) // Webhook alone is external unverified!
        assertEquals(200, outcome.webhookStatusCode)
        assertEquals(listOf("Webhook"), outcome.invokedTools)
        assertEquals("التحقق من حالة الشحنة من ERP", outcome.webhookToolReason)
        assertTrue(outcome.replyText.orEmpty().contains("الشحنة #7788"))
        val webhookCall = transport.calls.first { it.url.contains("erp.example.com") }
        assertEquals("msg-idemp-7788", webhookCall.idempotencyKey)
    }

    // 4. Combined question -> AI orchestrates BOTH Smart Advisor + Webhook in one cohesive reply
    @Test
    fun scenario_04_combined_question_invokes_both_smart_advisor_and_webhook_single_reply() = runTest {
        configureOrchestratorEnvironment()
        val sentReplies = mutableListOf<String>()
        var aiPass = 0

        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Success(
                    """{"ok":true,"has_verified_data":true,"summary":"سياسة الاسترجاع: خلال 7 أيام من الاستلام بحالته الأصلية."}""",
                    200,
                    10L
                )
                url.contains("erp.example.com") -> HttpTransport.Result.Success(
                    """{"reply":"حالة الطلب #501: تم التوصيل بتاريخ الأمس"}""",
                    200,
                    11L
                )
                url.contains("gateway.example.com") -> {
                    aiPass++
                    if (aiPass == 1) {
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"combined","confidence":0.92,"needs_store_data":true,"store_query":"سياسة الاسترجاع","store_intent":"returns_policy","needs_webhook_data":true,"webhook_query":"ORDER-501","webhook_reason":"فحص تاريخ توصيل الطلب #501","reply":""}"""
                            ),
                            200,
                            12L
                        )
                    } else {
                        assertTrue(body.orEmpty().contains("خلال 7 أيام"))
                        assertTrue(body.orEmpty().contains("حالة الطلب #501"))
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"combined","confidence":0.97,"reply":"طلبك #501 تم توصيله أمس، وبحسب سياسة المتجر يمكنك الاسترجاع خلال 7 أيام من الاستلام."}"""
                            ),
                            200,
                            15L
                        )
                    }
                }
                else -> HttpTransport.Result.Failure("unexpected $url", 500)
            }
        }

        val action = Notification.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            PendingIntent.getBroadcast(context, 1, Intent("ACTION_REPLY"), PendingIntent.FLAG_IMMUTABLE)
        ).addRemoteInput(android.app.RemoteInput.Builder("reply").build()).build()

        val result = notificationCoordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-combined-1",
                notificationId = "n-1",
                senderName = "فهد",
                rawTitle = "فهد",
                messageText = "هل وصل طلبي #501 وما هي مدة الاسترجاع المسموحة؟",
                replyAction = action,
                directSendInvoker = { _, txt -> sentReplies += txt }
            )
        )

        assertTrue(result.isHandled)
        assertEquals(1, sentReplies.size) // Strictly ONE unified reply sent to customer!
        assertTrue(sentReplies.single().contains("#501"))
        assertTrue(sentReplies.single().contains("7 أيام"))
        assertEquals(1, transport.calls.count { it.url.endsWith("/knowledge") })
        assertEquals(1, transport.calls.count { it.url.contains("erp.example.com") })
    }

    // 5. AI fails while Webhook is enabled (both AI_TOOL & STANDALONE_FALLBACK) -> independent Webhook MUST NOT fire!
    @Test
    fun scenario_05_ai_failure_blocks_independent_webhook_and_uses_wildcard_or_safe_fallback() = runTest {
        configureOrchestratorEnvironment(
            webhookMode = WebhookMode.STANDALONE_FALLBACK,
            webhookEnabled = true,
            saEnabled = true,
            aiEnabled = true,
            safeFallbackReply = "تم استلام رسالتك وسيتم الرد عليك قريباً."
        )

        // All AI calls fail
        transport.handler = { url, _, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.contains("erp.example.com") -> HttpTransport.Result.Success(
                    """{"reply":"رد webhook مستقل ممنوع عند تفعيل AI"}""",
                    200,
                    10L
                )
                else -> HttpTransport.Result.Failure("AI network timeout", 504)
            }
        }

        // Case A: Safe fallback applies; Webhook & standalone SA are NEVER called independently
        val resSafe = tester.evaluate("متى يفتح المتجر؟")
        assertTrue(resSafe.matched)
        assertEquals("تم استلام رسالتك وسيتم الرد عليك قريباً.", resSafe.replyText)
        assertFalse(resSafe.calledWebhook)
        assertFalse(transport.calls.any { it.url.contains("erp.example.com") })
        assertFalse(transport.calls.any { it.url.endsWith("/whatsapp/reply") })

        // Case B: Wildcard rule "*" wins over standalone Webhook
        repository.upsertRule(
            ReplyRuleEntity(
                keyword = "*",
                replyText = "رد القاعدة الافتراضية الآمن",
                matchType = MatchType.CONTAINS,
                isEnabled = true,
                priority = 1
            )
        )
        val resWildcard = tester.evaluate("استفسار عام آخر")
        assertTrue(resWildcard.matched)
        assertEquals(ReplySourceCategory.WILDCARD_RULE, resWildcard.sourceCategory)
        assertEquals("رد القاعدة الافتراضية الآمن", resWildcard.replyText)
        assertFalse(transport.calls.any { it.url.contains("erp.example.com") })
    }

    // 6. Standalone Fallback Mode with AI OFF -> Webhook responds cleanly; AI_TOOL Mode with AI OFF stays silent
    @Test
    fun scenario_06_standalone_webhook_works_only_when_ai_off_and_mode_is_standalone_fallback() = runTest {
        configureOrchestratorEnvironment(
            webhookMode = WebhookMode.STANDALONE_FALLBACK,
            webhookEnabled = true,
            saEnabled = false,
            aiEnabled = false
        )
        transport.handler = { url, _, _, _, _ ->
            if (url.contains("erp.example.com")) {
                HttpTransport.Result.Success("""{"reply":"رد Webhook المستقل الفعال"}""", 200, 10L)
            } else {
                HttpTransport.Result.Failure("unexpected", 500)
            }
        }

        val standaloneOk = tester.evaluate("مرحبا من النظام الخارجي")
        assertTrue(standaloneOk.matched)
        assertEquals(ReplySourceCategory.WEBHOOK, standaloneOk.sourceCategory)
        assertEquals("رد Webhook المستقل الفعال", standaloneOk.replyText)

        // Switching to AI_TOOL mode while AI is OFF must NOT trigger an independent Webhook reply
        val currentCfg = serverRepo.get()!!
        serverRepo.save(currentCfg.copy(webhookMode = WebhookMode.AI_TOOL.name))
        transport.calls.clear()

        val aiToolModeAiOff = tester.evaluate("مرحبا مجدداً")
        assertFalse(aiToolModeAiOff.matched)
        assertEquals(ReplySourceCategory.NO_MATCH, aiToolModeAiOff.sourceCategory)
        assertFalse(transport.calls.any { it.url.contains("erp.example.com") })
    }

    // 7. Tool failures (Webhook 503 + SA 403) -> AI never fabricates and secrets stay scrubbed
    @Test
    fun scenario_07_tool_failure_prevents_fabrication_and_scrubs_secrets() = runTest {
        configureOrchestratorEnvironment()
        var aiPass = 0
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Failure("HTTP 403 unauthorized sk-newapi-secret wh-secret-999", 403)
                url.contains("erp.example.com") -> HttpTransport.Result.Failure("HTTP 503 wh-secret-999 service down", 503)
                url.contains("gateway.example.com") -> {
                    aiPass++
                    if (aiPass == 1) {
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"mixed_fail","needs_store_data":true,"store_query":"عطر","needs_webhook_data":true,"webhook_query":"ORD-99","reply":"قد يكون السعر 150 ريال"}"""
                            ),
                            200,
                            10L
                        )
                    } else {
                        assertFalse(body.orEmpty().contains("wh-secret-999"))
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"mixed_fail","needs_human":true,"reply":"تعذر التحقق من النظامين حالياً، تم تحويل طلبك لفريق خدمة العملاء للمراجعة."}"""
                            ),
                            200,
                            12L
                        )
                    }
                }
                else -> HttpTransport.Result.Failure("unexpected", 500)
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "ناصر",
                messageText = "ما حالة طلب ORD-99 وسعر العطر؟",
                isSandbox = true
            )
        )
        assertTrue(outcome.needsHuman)
        assertFalse(outcome.verifiedStoreDataUsed)
        assertFalse(outcome.replyText.orEmpty().contains("150 ريال"))
        assertFalse(outcome.replyText.orEmpty().contains("wh-secret-999"))
        assertFalse(outcome.replyText.orEmpty().contains("sk-newapi-secret"))
        assertFalse(outcome.redactedError.orEmpty().contains("wh-secret-999"))
    }

    // 8. Circular / endless tool loop guard -> deduplicates same signature and stops at MAX_TOOL_ROUNDS = 2
    @Test
    fun scenario_08_infinite_tool_loop_guard_caps_rounds_and_deduplicates_calls() = runTest {
        configureOrchestratorEnvironment()
        var aiPass = 0
        transport.handler = { url, _, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Success(
                    """{"ok":true,"has_verified_data":true,"summary":"المخزون المتاح: 5 قطع"}""",
                    200,
                    8L
                )
                url.contains("erp.example.com") -> HttpTransport.Result.Success(
                    """{"reply":"الشحنة جاهزة"}""",
                    200,
                    8L
                )
                url.contains("gateway.example.com") -> {
                    aiPass++
                    // Malbehaving AI keeps asking for the exact same tool/query repeatedly
                    val q = if (aiPass == 1) "STOCK-1" else if (aiPass == 2) "STOCK-1" else "STOCK-3"
                    HttpTransport.Result.Success(
                        openAiPayload(
                            """{"intent":"loop_test","needs_store_data":true,"store_query":"$q","needs_webhook_data":true,"webhook_query":"$q","reply":"الرد الآمن بعد انتهاء الجولات: المخزون 5 قطع والشحنة جاهزة."}"""
                        ),
                        200,
                        10L
                    )
                }
                else -> HttpTransport.Result.Failure("unexpected", 500)
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "ريم",
                messageText = "افحص المخزون والشحنة",
                isSandbox = true
            )
        )
        assertTrue(outcome.toolRoundsUsed <= SmartCollaborationCoordinator.MAX_TOOL_ROUNDS)
        // Round 2 asked for STOCK-1 again -> deduplicated! Only Round 1 (STOCK-1) ran then stopped cleanly
        assertEquals(1, outcome.toolRoundsUsed)
        assertEquals(1, transport.calls.count { it.url.endsWith("/knowledge") })
        assertEquals(1, transport.calls.count { it.url.contains("erp.example.com") })
        assertFalse(outcome.replyText.isNullOrBlank())
    }

    // 9. Account actions: allowed in DM with explicit confirmation + idempotency; blocked in Groups; unconfirmed asks confirmation
    @Test
    fun scenario_09_account_actions_dm_only_confirmed_and_group_blocked() = runTest {
        configureOrchestratorEnvironment()
        transport.handler = { url, _, _, body, idemp ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":true,"consent_required":true}""",
                    200,
                    5L
                )
                url.endsWith("/account-action") -> {
                    assertEquals("idemp-magic-dm", idemp)
                    HttpTransport.Result.Success(
                        """{"ok":true,"status":"ok","link_created":true,"ephemeral_login_url":"https://store.example.com/login?tok=xyz"}""",
                        200,
                        10L
                    )
                }
                url.contains("gateway.example.com") -> HttpTransport.Result.Success(
                    openAiPayload(
                        """{"intent":"account","account_action":"magic_login","account_action_confirmed":false,"reply":"جاري التحقق"}"""
                    ),
                    200,
                    10L
                )
                else -> HttpTransport.Result.Failure("unexpected $url $body", 500)
            }
        }

        // A. Group message requesting magic_login -> strictly blocked before /account-action!
        val groupOutcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "سعيد",
                groupName = "مجموعة المتجر",
                isGroup = true,
                messageText = "أرسل لي رابط تسجيل الدخول",
                isSandbox = false,
                messageId = "idemp-group"
            )
        )
        assertTrue(groupOutcome.replyText.orEmpty().contains("المجموعات"))
        assertFalse(transport.calls.any { it.url.endsWith("/account-action") })

        // B. DM without explicit confirmation keywords -> asks confirmation instead of executing immediately
        val unconfirmedOutcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "سعيد",
                isGroup = false,
                messageText = "ماذا عن ملفي؟",
                isSandbox = false,
                messageId = "idemp-unconfirmed"
            )
        )
        assertTrue(unconfirmedOutcome.replyText.orEmpty().contains("تؤكد"))
        assertFalse(transport.calls.any { it.url.endsWith("/account-action") })

        // C. DM with explicit login request -> executes /account-action with idempotencyKey
        val dmConfirmed = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "سعيد",
                isGroup = false,
                messageText = "أرسل لي رابط تسجيل الدخول لحسابي",
                isSandbox = false,
                messageId = "idemp-magic-dm"
            )
        )
        assertTrue(dmConfirmed.calledSmartAdvisor)
        assertTrue(dmConfirmed.replyText.orEmpty().contains("https://store.example.com/login?tok=xyz"))
        assertEquals(1, transport.calls.count { it.url.endsWith("/account-action") })
    }

    // 10. NewAPI fails -> automatic fallback to Qwen while still calling Webhook tool cleanly
    @Test
    fun scenario_10_newapi_failure_falls_back_to_qwen_with_tools_and_context() = runTest {
        configureOrchestratorEnvironment()
        var qwenPass = 0
        transport.handler = { url, _, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":false,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.contains("gateway.example.com") -> HttpTransport.Result.Failure("502 Bad Gateway", 502)
                url.contains("qwen.example.com") -> {
                    qwenPass++
                    if (qwenPass == 1) {
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"shipping","needs_webhook_data":true,"webhook_query":"SHIP-404","webhook_reason":"فحص التوصيل عبر Webhook","reply":""}"""
                            ),
                            200,
                            20L
                        )
                    } else {
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"shipping","reply":"رد من Qwen الاحتياطي: الشحنة #404 خرجت للتوصيل اليوم."}"""
                            ),
                            200,
                            20L
                        )
                    }
                }
                url.contains("erp.example.com") -> HttpTransport.Result.Success(
                    """{"reply":"الشحنة #404 خرجت مع المندوب"}""",
                    200,
                    12L
                )
                else -> HttpTransport.Result.Failure("unexpected $url", 500)
            }
        }

        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "ليلى",
                messageText = "ما أخبار الشحنة SHIP-404؟",
                isSandbox = true
            )
        )
        assertTrue(outcome.switchedToQwen)
        assertTrue(outcome.calledWebhook)
        assertTrue(outcome.activeEngine.contains("qwen2.5:7b"))
        assertTrue(outcome.replyText.orEmpty().contains("الشحنة #404"))
    }

    // 11. Idempotency duplicate blocking + DELIVERY_UNKNOWN protection preserved
    @Test
    fun scenario_11_idempotency_and_delivery_unknown_protection_remain_strict() = runTest {
        configureOrchestratorEnvironment()
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "sbn-dup-1", "تأكيد الطلب")
        assertTrue(idempotencyGuard.tryAcquireReservation(msgKey, leaseDurationMs = 90_000L))
        idempotencyGuard.markDeliveryUnknown(msgKey)

        // Same notification arrives again -> strictly blocked by IdempotencyGuard without running AI or Webhook
        val duplicateResult = notificationCoordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-dup-1",
                notificationId = "n-dup",
                senderName = "عمر",
                rawTitle = "عمر",
                messageText = "تأكيد الطلب"
            )
        )
        assertFalse(duplicateResult.isHandled)
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_DEDUP, duplicateResult.deliveryMethod)
        assertEquals(0, transport.calls.size)
        assertEquals(DedupKeyEntity.SEND_DELIVERY_UNKNOWN, idempotencyGuard.getEntry(msgKey)?.sendState)
        assertEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, idempotencyGuard.getEntry(msgKey)?.state)
    }

    // 12. Memory privacy, consent gate & Webhook payload isolation + ServerViewModel tool preview
    @Test
    fun scenario_12_memory_consent_webhook_isolation_and_server_viewmodel_preview() = runTest {
        configureOrchestratorEnvironment()
        aiSettingsPrefs.save(
            aiSettingsPrefs.current().copy(syncCustomerMemory = true, requireCustomerConsent = true)
        )

        var aiPass = 0
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    """{"knowledge_enabled":true,"memory_enabled":true,"account_actions_enabled":false,"consent_required":true}""",
                    200,
                    5L
                )
                url.contains("erp.example.com") -> {
                    // Ensure AI conversation history / customer memory summary is NOT dumped into Webhook request
                    assertFalse(body.orEmpty().contains("turnsJson"))
                    assertFalse(body.orEmpty().contains("systemPrompt"))
                    HttpTransport.Result.Success("""{"reply":"حالة التوصيل جاهزة wh-secret-999"}""", 200, 10L)
                }
                url.contains("gateway.example.com") -> {
                    aiPass++
                    if (aiPass == 1) {
                        HttpTransport.Result.Success(
                            openAiPayload(
                                """{"intent":"order","needs_webhook_data":true,"webhook_query":"ORD-88","reply":"","memory_update":{"summary":"يفضل التوصيل المسائي","preferences":["التوصيل المسائي"],"interests":[],"constraints":[]}}"""
                            ),
                            200,
                            10L
                        )
                    } else {
                        HttpTransport.Result.Success(
                            openAiPayload("""{"intent":"order","reply":"طلبك جاهز للتوصيل."}"""),
                            200,
                            10L
                        )
                    }
                }
                else -> HttpTransport.Result.Failure("unexpected $url", 500)
            }
        }

        // Without customer consent, memory_outbox must remain empty and consentRequiredForMemory == true!
        val outcome = coordinator.collaborate(
            SmartCollaborationCoordinator.CollaborationInput(
                packageName = "com.whatsapp",
                senderName = "منى",
                phone = "+966500001234",
                messageText = "استعلم عن طلب ORD-88، وأفضل التوصيل المسائي دائماً",
                isSandbox = false,
                messageId = "msg-consent-1"
            )
        )
        assertTrue(outcome.calledWebhook)
        assertFalse(outcome.memoryUpdated)
        assertTrue(outcome.consentRequiredForMemory)
        assertEquals(0, db.memoryOutboxDao().getPending().size)
        // Sensitive secret in Webhook response is redacted in snippet too!
        assertFalse(outcome.webhookDataSnippet.contains("wh-secret-999"))

        // ServerViewModel tool preview & mode toggle work properly
        val serverVm = ServerViewModel(serverRepo, webhookClient, secretVault)
        var guard = 0
        while (serverVm.config.value == null && guard++ < 100) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15L)
        }
        assertNotNull(serverVm.config.value)
        serverVm.updateWebhookMode(WebhookMode.AI_TOOL)
        serverVm.updateToolDescription("وصف مخصص لأداة الشحن")
        serverVm.testAsAiTool("ORD-88")
        guard = 0
        while (serverVm.testResult.value == null && guard++ < 100) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val previewRes = serverVm.testResult.value
        assertNotNull(previewRes)
        assertTrue(previewRes!!.isToolPreview)
        assertTrue("Expected tool preview success but got error=${previewRes.error}", previewRes.success)
        assertFalse(previewRes.body.contains("wh-secret-999"))
        assertTrue(previewRes.body.contains("خارجي غير متحقق منه"))
    }
}
