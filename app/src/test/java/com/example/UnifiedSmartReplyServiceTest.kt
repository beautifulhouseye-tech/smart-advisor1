package com.example

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.MenuReplyEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.local.entity.WebhookMode
import com.example.engine.MenuOption
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.ReplyLanguage
import com.example.data.preferences.ResponseLength
import com.example.data.preferences.ResponseTone
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.ConversationKey
import com.example.engine.ConversationMemoryManager
import com.example.engine.IdempotencyGuard
import com.example.engine.LocalOutcome
import com.example.engine.MenuReplyManager
import com.example.engine.NotificationCoordinator
import com.example.engine.NotificationPayload
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import com.example.engine.ReplySourceCategory
import com.example.engine.SmartCollaborationCoordinator
import com.example.engine.UnifiedReplyTester
import com.example.engine.UnifiedSmartReplyService
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.ui.viewmodel.AISettingsViewModel
import com.example.worker.FollowUpScheduler
import com.example.worker.MemoryOutboxCoordinator
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnifiedSmartReplyServiceTest {

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
    private lateinit var unifiedService: UnifiedSmartReplyService
    private lateinit var idempotencyGuard: IdempotencyGuard
    private lateinit var pendingReplyCoordinator: PendingReplyCoordinator
    private lateinit var replyDispatcher: ReplyDispatcher
    private lateinit var autoReplyEngine: AutoReplyEngine
    private lateinit var notificationCoordinator: NotificationCoordinator
    private lateinit var tester: UnifiedReplyTester

    private var fakeNowMs: Long = 1_764_000_000_000L
    private lateinit var prefsSuffix: String
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsDataStore = SettingsDataStore(context)
        settingsDataStore.setAutoReplyEnabled(true)
        settingsDataStore.setDrivingModeEnabled(false)
        settingsDataStore.setReplyDelaySeconds(0)
        settingsDataStore.setCooldownMinutes(0)
        settingsDataStore.setReplyInGroups(false)
        settingsDataStore.setUseAccessibilityFallback(false)

        repository = AutoMateRepository(
            replyRuleDao = db.replyRuleDao(),
            contactFilterDao = db.contactFilterDao(),
            replyLogDao = db.replyLogDao(),
            settingsDataStore = settingsDataStore,
            backupManager = BackupManager(context, json),
            replyTemplateDao = db.replyTemplateDao()
        )
        serverRepo = ServerConfigRepositoryImpl(db.serverConfigDao())
        menuReplyManager = MenuReplyManager(db.menuReplyDao(), json)

        prefsSuffix = System.nanoTime().toString()
        secretVault = SecretVault(
            context,
            context.getSharedPreferences("unified_vault_$prefsSuffix", Context.MODE_PRIVATE)
        )
        aiSettingsPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("unified_ai_$prefsSuffix", Context.MODE_PRIVATE)
        )
        smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("unified_sa_$prefsSuffix", Context.MODE_PRIVATE)
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
        unifiedService = UnifiedSmartReplyService(
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
            smartCollaborationCoordinator = coordinator,
            unifiedSmartReplyService = unifiedService
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
            clockMs = { fakeNowMs },
            injectedUnifiedService = unifiedService
        )
    }

    @After
    fun tearDown() = runBlocking {
        settingsDataStore.setAutoReplyEnabled(false)
        settingsDataStore.setDrivingModeEnabled(false)
        settingsDataStore.setReplyInGroups(false)
        settingsDataStore.setCooldownMinutes(0)
        settingsDataStore.setReplyDelaySeconds(5)
        settingsDataStore.setUseAccessibilityFallback(true)
        db.close()
    }

    // 1. Static/Ready replies: Driving mode & interactive menus
    @Test
    fun `01 static driving mode and menu replies execute cleanly without AI`() = runTest {
        aiSettingsPrefs.save(AiSettingsState(enabled = true, provider = AIReplyEngine.Provider.OPENAI))
        settingsDataStore.setDrivingModeEnabled(true)
        settingsDataStore.setDrivingModeReplyText("أقود الآن وسأتواصل معك بعد قليل.")

        val driving = tester.evaluate("مرحبا")
        assertTrue(driving.matched)
        assertEquals(ReplySourceCategory.DRIVING_MODE, driving.sourceCategory)
        assertTrue(transport.calls.isEmpty())

        settingsDataStore.setDrivingModeEnabled(false)
        db.menuReplyDao().insert(
            MenuReplyEntity(
                trigger = "الخدمات",
                title = "اختر:",
                optionsJson = menuReplyManager.encodeOptions(listOf(MenuOption(1, "التوصيل", "التوصيل خلال 24 ساعة"))),
                enabled = true
            )
        )
        val menuOpen = tester.evaluate("الخدمات")
        assertEquals(ReplySourceCategory.MENU_REPLY, menuOpen.sourceCategory)
        val menuPick = tester.evaluate("1")
        assertEquals("التوصيل خلال 24 ساعة", menuPick.replyText)
        assertTrue(transport.calls.isEmpty())
    }

    // 2. Complete keyword rule matches immediately without calling AI
    @Test
    fun `02 complete keyword rule returns immediately without calling AI`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-rule-skip-ai")
        aiSettingsPrefs.save(AiSettingsState(enabled = true, provider = AIReplyEngine.Provider.OPENAI))
        repository.upsertRule(
            ReplyRuleEntity(keyword = "الضمان", replyText = "الضمان لمدة سنتين شامل.", matchType = MatchType.CONTAINS, isEnabled = true, priority = 20)
        )

        val decision = unifiedService.resolve(
            UnifiedSmartReplyService.Request(senderName = "خالد", messageText = "ما تفاصيل الضمان؟")
        )
        assertTrue(decision.matched)
        assertEquals(ReplySourceCategory.LOCAL_RULE, decision.sourceCategory)
        assertEquals("الضمان لمدة سنتين شامل.", decision.replyText)
        assertTrue(transport.calls.isEmpty())
    }

    // 3. Non-conclusive keyword rule ([AI] marker or blank body) falls through with context hint to AI
    @Test
    fun `03 non conclusive keyword rule falls through to AI with context hint`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-fallthrough")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                fallbackEnabled = false,
                saCollaborationEnabled = false
            )
        )
        repository.upsertRule(
            ReplyRuleEntity(
                keyword = "استشارة",
                replyText = "[AI] العميل يطلب استشارة مخصصة لاقتراح منتج مناسب.",
                matchType = MatchType.CONTAINS,
                isEnabled = true,
                priority = 20
            )
        )
        var capturedBody = ""
        transport.handler = { _, _, _, body, _ ->
            capturedBody = body.orEmpty()
            chatCompletionSuccess("""{"intent":"consult","confidence":0.9,"needs_store_data":false,"reply":"أهلاً بك! أخبرني باحتياجك وسأرشح لك الخيار الأنسب."}""")
        }

        val res = tester.evaluate("أحتاج استشارة لاختيار هدية")
        assertTrue(res.matched)
        assertEquals(ReplySourceCategory.AI_ENGINE, res.sourceCategory)
        assertTrue(capturedBody.contains("العميل يطلب استشارة مخصصة"))
        assertTrue(res.evaluatedPath.any { it.contains("قاعدة غير حاسمة") })
    }

    // 4. Changing ResponseTone injects the configured tone instruction into the AI orchestrator
    @Test
    fun `04 changing tone updates AI orchestrator system guidance accurately`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-tone")
        val seenBodies = mutableListOf<String>()
        transport.handler = { _, _, _, body, _ ->
            seenBodies += body.orEmpty()
            chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"تم الاستلام بكل سرور."}""")
        }

        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                responseTone = ResponseTone.FORMAL,
                saCollaborationEnabled = false
            )
        )
        tester.evaluate("مرحبا", conversationKey = "tone_conv_1")
        assertTrue(seenBodies.last().contains(ResponseTone.FORMAL.promptInstruction))

        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                responseTone = ResponseTone.CUSTOM,
                customToneInstruction = "استخدم تحية حجازية لطيفة جداً",
                saCollaborationEnabled = false
            )
        )
        tester.evaluate("مرحبا", conversationKey = "tone_conv_2")
        assertTrue(seenBodies.last().contains("استخدم تحية حجازية لطيفة جداً"))
    }

    // 5. ResponseLength and maxReplyLengthChars enforce prompt constraints & hard cap while preserving URLs
    @Test
    fun `05 response length and char limit actually constrain final reply without breaking links`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-len")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                responseLength = ResponseLength.VERY_SHORT,
                maxReplyLengthChars = 110,
                saCollaborationEnabled = false
            )
        )
        val overlyLongReply = "أهلاً وسهلاً بك في متجرنا المتميز. هذه الجملة الأولى تشرح التفاصيل الكاملة. " +
            "وهذه الجملة الثانية تضيف شرحاً إضافياً طويلاً جداً يتجاوز الحد المسموح به للرد القصير."
        transport.handler = { _, _, _, _, _ ->
            chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"$overlyLongReply"}""")
        }

        val shortOutcome = unifiedService.resolve(
            UnifiedSmartReplyService.Request(senderName = "فهد", messageText = "حدثني عنكم")
        )
        assertTrue(shortOutcome.matched)
        assertTrue("Expected reply <= 110 chars, was ${shortOutcome.replyText.length}", shortOutcome.replyText.length <= 110)

        // URLs inside long strings remain intact via AIReplyEngine.enforceToneAndLength
        val withUrl = "تفاصيل طلبك جاهزة عبر الرابط التالي مباشرة: https://shop.example.com/magic-login?token=abc123def456 مع تحيات الفريق."
        val enforcedUrlReply = AIReplyEngine.enforceToneAndLength(
            withUrl,
            AiSettingsState(responseLength = ResponseLength.VERY_SHORT, maxReplyLengthChars = 120)
        )
        assertTrue(enforcedUrlReply.contains("https://shop.example.com/magic-login?token=abc123def456"))
    }

    // 6. Switching primary AI provider routes cleanly to selected provider (no stale baseUrl hijack) & redacts secrets
    @Test
    fun `06 switching primary AI provider dispatches to exact chosen provider and redacts keys`() = runTest {
        val openaiKey = "sk-openai-secret-999"
        val geminiKey = "AIzaSyGeminiSecret888"
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), openaiKey)
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.GEMINI), geminiKey)
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "sk-newapi-secret-777")

        val calledUrls = mutableListOf<String>()
        transport.handler = { url, _, _, _, _ ->
            calledUrls += url
            if (url.contains("generativelanguage.googleapis.com")) {
                HttpTransport.Result.Success(
                    body = """{"candidates":[{"content":{"parts":[{"text":"{\"intent\":\"general\",\"confidence\":0.9,\"needs_store_data\":false,\"reply\":\"رد من جيميني مع مفتاح $geminiKey\"}"}]}}]}""",
                    code = 200,
                    ms = 10L
                )
            } else {
                chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"رد من المزود المختار"}""")
            }
        }

        // OpenAI
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                primaryBaseUrl = "https://stale-newapi.example.com",
                saCollaborationEnabled = false
            )
        )
        unifiedService.resolve(UnifiedSmartReplyService.Request(senderName = "U1", messageText = "اهلا"))
        assertTrue(calledUrls.last().contains("api.openai.com"))

        // Switch to Gemini despite stale primaryBaseUrl
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.GEMINI,
                primaryBaseUrl = "https://stale-newapi.example.com",
                fallbackEnabled = false,
                saCollaborationEnabled = false
            )
        )
        val geminiDecision = unifiedService.resolve(
            UnifiedSmartReplyService.Request(senderName = "U2", messageText = "اهلا")
        )
        assertTrue(calledUrls.last().contains("generativelanguage.googleapis.com"))
        assertFalse("Gemini key leaked in reply!", geminiDecision.replyText.contains(geminiKey))
        assertTrue(geminiDecision.replyText.contains(SecretVault.REDACTED_MARKER))
    }

    // 7. Primary AI failure switches safely to Ollama/Qwen fallback & sends single reply
    @Test
    fun `07 primary failure safely switches to Ollama Qwen fallback with single reply`() = runTest {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "sk-primary-down")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://gateway.example.com",
                fallbackEnabled = true,
                ollamaBaseUrl = "https://ollama.example.com",
                ollamaModel = "qwen2.5:1.5b",
                saCollaborationEnabled = false
            )
        )
        transport.handler = { url, _, _, _, _ ->
            if (url.contains("gateway.example.com")) {
                HttpTransport.Result.Failure("502 Bad Gateway", 502)
            } else {
                chatCompletionSuccess("""{"intent":"general","confidence":0.85,"needs_store_data":false,"reply":"أهلاً بك! الرد عبر المحرك الاحتياطي Qwen."}""")
            }
        }

        val dispatched = mutableListOf<String>()
        val outcome = autoReplyEngine.processIncomingMessage(
            makeIncoming(
                sender = "نورة",
                message = "مساء الخير",
                msgKey = "k_fb_1",
                dispatched = dispatched
            )
        )
        assertTrue(outcome is LocalOutcome.Handled)
        assertEquals(listOf("أهلاً بك! الرد عبر المحرك الاحتياطي Qwen."), dispatched)
    }

    // 8. Failure of all AI engines falls back to safeFallbackReply without crash
    @Test
    fun `08 failure of all AI engines returns safeFallbackReply without crash`() = runTest {
        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "sk-all-down")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://down1.example.com",
                fallbackEnabled = true,
                ollamaBaseUrl = "https://down2.example.com",
                safeFallbackReply = "عذراً، سيقوم فريق خدمة العملاء بالرد عليك قريباً.",
                saCollaborationEnabled = false
            )
        )
        transport.handler = { _, _, _, _, _ ->
            HttpTransport.Result.Failure("Authorization: Bearer sk-all-down timeout", 504)
        }

        val result = tester.evaluate("هل أنتم موجودون؟")
        assertTrue(result.matched)
        assertEquals("عذراً، سيقوم فريق خدمة العملاء بالرد عليك قريباً.", result.replyText)
        assertFalse(result.redactedError.orEmpty().contains("sk-all-down"))
    }

    // 9. Multi-turn sequential conversation remembers context & honors useConversationContext toggle
    @Test
    fun `09 sequential conversation retains prior turns when enabled and omits when disabled`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-ctx")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                useConversationContext = true,
                contextWindowPairs = 6,
                saCollaborationEnabled = false
            )
        )
        val bodies = mutableListOf<String>()
        transport.handler = { _, _, _, body, _ ->
            bodies += body.orEmpty()
            chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"فهمت طلبك تماماً."}""")
        }

        tester.evaluate("أهلاً، اسمي ماجد", conversationKey = "ctx_seq_1")
        tester.evaluate("هل تذكر اسمي؟", conversationKey = "ctx_seq_1")
        assertTrue(bodies.last().contains("أهلاً، اسمي ماجد"))

        // Disable context tracking in settings
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                useConversationContext = false,
                saCollaborationEnabled = false
            )
        )
        tester.evaluate("سؤال جديد بدون سياق", conversationKey = "ctx_seq_1")
        assertFalse(bodies.last().contains("أهلاً، اسمي ماجد"))
    }

    // 10. Real multi-turn perfume case: "أريد عطر" -> "رجالي" -> "كم سعره؟"
    @Test
    fun `10 perfume multi turn conversation retains men perfume entity through price inquiry`() = runTest {
        configureFullOrchestrator()
        val saQueries = mutableListOf<String>()
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"knowledge_enabled":true,"memory_enabled":true,"account_actions_enabled":false}""",
                    code = 200,
                    ms = 10L
                )
                url.contains("/memory/") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"summary":"","preferences":[],"interests":[],"constraints":[]}""",
                    code = 200,
                    ms = 10L
                )
                url.endsWith("/knowledge") -> {
                    val reqObj = json.parseToJsonElement(body!!).jsonObject
                    val query = reqObj["query"]?.jsonPrimitive?.content.orEmpty()
                    saQueries += query
                    HttpTransport.Result.Success(
                        body = """{"ok":true,"verified":true,"intent":"product_details","summary":"عطر رجالي فاخر (عود الملكي 100مل) بسعر 290 ريال ومتوفر حالياً.","facts":["السعر: 290 ريال","التوفر: متوفر"]}""",
                        code = 200,
                        ms = 10L
                    )
                }
                else -> {
                    val parsed = json.parseToJsonElement(body!!).jsonObject
                    val msgs = parsed["messages"]!!.jsonArray
                    val lastUser = msgs.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                        ?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                    val sys = msgs.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                    when {
                        sys.contains("نتائج الأدوات المسترجعة") ->
                            chatCompletionSuccess("""{"intent":"product_details","confidence":0.95,"needs_human":false,"reply":"يتوفر لدينا عطر رجالي فاخر بسعر 290 ريال ومتوفر حالياً."}""")
                        lastUser.contains("كم سعره") ->
                            chatCompletionSuccess("""{"intent":"product_details","confidence":0.92,"needs_store_data":true,"store_intent":"product_details","store_query":"","reply":"جاري التحقق"}""")
                        else ->
                            chatCompletionSuccess("""{"intent":"product_search","confidence":0.9,"needs_store_data":false,"reply":"بكل سرور! ما نوع العطر المطلوب؟"}""")
                    }
                }
            }
        }

        val convKey = "perfume_conv_1"
        unifiedService.resolve(UnifiedSmartReplyService.Request(senderName = "سعد", messageText = "أريد عطر", conversationKeyOverride = convKey, isSandbox = false))
            .also { memoryManager.commitAssistantTurnImmediately(convKey, it.replyText, 6, isSandbox = false) }
        unifiedService.resolve(UnifiedSmartReplyService.Request(senderName = "سعد", messageText = "رجالي", conversationKeyOverride = convKey, isSandbox = false))
            .also { memoryManager.commitAssistantTurnImmediately(convKey, it.replyText, 6, isSandbox = false) }
        val turn3 = unifiedService.resolve(UnifiedSmartReplyService.Request(senderName = "سعد", messageText = "كم سعره؟", conversationKeyOverride = convKey, isSandbox = false))

        val mem = db.conversationContextDao().getByKey(convKey)
        assertNotNull(mem)
        assertEquals("عطور", mem?.currentTopic)
        assertEquals("عطر رجالي", mem?.lastProductDiscussed)
        assertTrue(saQueries.any { it.contains("عطر رجالي") })
        assertTrue(turn3.replyText.contains("290 ريال"))
        assertTrue(turn3.verifiedStoreDataUsed)
    }

    // 11. Kitchen blender multi-turn case: "أبحث عن خلاط للمطبخ" -> "أريده بسعر مناسب" -> "هل يوجد لون أبيض؟"
    @Test
    fun `11 blender multi turn preserves topic and entity across budget and color followups`() = runTest {
        configureFullOrchestrator()
        val saQueries = mutableListOf<String>()
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"knowledge_enabled":true,"memory_enabled":true,"account_actions_enabled":false}""",
                    code = 200,
                    ms = 10L
                )
                url.endsWith("/knowledge") -> {
                    val reqObj = json.parseToJsonElement(body!!).jsonObject
                    saQueries += reqObj["query"]?.jsonPrimitive?.content.orEmpty()
                    HttpTransport.Result.Success(
                        body = """{"ok":true,"verified":true,"intent":"product_search","summary":"خلاط مطبخ 800 واط متوفر باللون الأبيض بسعر 185 ريال.","facts":["اللون: أبيض","السعر: 185 ريال"]}""",
                        code = 200,
                        ms = 10L
                    )
                }
                else -> {
                    val parsed = json.parseToJsonElement(body!!).jsonObject
                    val msgs = parsed["messages"]!!.jsonArray
                    val lastUser = msgs.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                        ?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                    val sys = msgs.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                    when {
                        sys.contains("نتائج الأدوات المسترجعة") ->
                            chatCompletionSuccess("""{"intent":"product_search","confidence":0.95,"needs_human":false,"reply":"نعم، يتوفر خلاط المطبخ بسعر 185 ريال باللون الأبيض."}""")
                        lastUser.contains("لون أبيض") ->
                            chatCompletionSuccess("""{"intent":"product_search","confidence":0.92,"needs_store_data":true,"store_intent":"product_search","store_query":"","reply":"جاري التحقق"}""")
                        else ->
                            chatCompletionSuccess("""{"intent":"product_search","confidence":0.9,"needs_store_data":false,"reply":"لدينا عدة خلاطات مطبخ بميزانيات مناسبة."}""")
                    }
                }
            }
        }

        val convKey = "blender_conv_1"
        unifiedService.resolve(UnifiedSmartReplyService.Request(senderName = "ليلى", messageText = "أبحث عن خلاط للمطبخ", conversationKeyOverride = convKey, isSandbox = false))
            .also { memoryManager.commitAssistantTurnImmediately(convKey, it.replyText, 6, isSandbox = false) }
        unifiedService.resolve(UnifiedSmartReplyService.Request(senderName = "ليلى", messageText = "أريده بسعر مناسب", conversationKeyOverride = convKey, isSandbox = false))
            .also { memoryManager.commitAssistantTurnImmediately(convKey, it.replyText, 6, isSandbox = false) }
        val turn3 = unifiedService.resolve(UnifiedSmartReplyService.Request(senderName = "ليلى", messageText = "هل يوجد لون أبيض؟", conversationKeyOverride = convKey, isSandbox = false))

        val mem = db.conversationContextDao().getByKey(convKey)
        assertNotNull(mem)
        assertEquals("أجهزة المطبخ", mem?.currentTopic)
        assertTrue(mem?.lastProductDiscussed.orEmpty().contains("خلاط"))
        assertTrue(saQueries.any { it.contains("خلاط") })
        assertTrue(turn3.replyText.contains("185 ريال"))
        assertTrue(turn3.replyText.contains("الأبيض"))
    }

    // 12. Context isolation across different conversations (User A vs User B vs Group)
    @Test
    fun `12 conversations remain strictly isolated between different users and groups`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-iso")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                saCollaborationEnabled = false
            )
        )
        val bodiesByCall = mutableListOf<String>()
        transport.handler = { _, _, _, body, _ ->
            bodiesByCall += body.orEmpty()
            chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"تم"}""")
        }

        val keyA = ConversationKey.build("com.whatsapp", "عميل أ", isGroup = false)
        val keyB = ConversationKey.build("com.whatsapp", "عميل ب", isGroup = false)
        assertNotEquals(keyA, keyB)

        tester.evaluate("أنا مهتم بساعات أبل السرية", senderName = "عميل أ", conversationKey = keyA)
        tester.evaluate("مرحبا", senderName = "عميل ب", conversationKey = keyB)

        assertFalse("Context leaked from User A into User B!", bodiesByCall.last().contains("ساعات أبل السرية"))
    }

    // 13. Smart Advisor verified product/price/stock data rendered faithfully
    @Test
    fun `13 Smart Advisor verified price and stock delivered accurately without distortion`() = runTest {
        configureFullOrchestrator()
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"knowledge_enabled":true}""",
                    code = 200,
                    ms = 10L
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"verified":true,"intent":"product_details","summary":"ساعة ذكية برو متوفرة بسعر 450 ريال والكمية المتبقية 7 قطع.","facts":["السعر: 450 ريال","المخزون: 7 قطع"]}""",
                    code = 200,
                    ms = 10L
                )
                body.orEmpty().contains("نتائج الأدوات المسترجعة") ->
                    chatCompletionSuccess("""{"intent":"product_details","confidence":0.96,"needs_human":false,"reply":"ساعة ذكية برو متوفرة بسعر 450 ريال (متبقي 7 قطع)."}""")
                else ->
                    chatCompletionSuccess("""{"intent":"product_details","confidence":0.9,"needs_store_data":true,"store_intent":"product_details","store_query":"ساعة ذكية برو","reply":""}""")
            }
        }

        val res = tester.evaluate("كم سعر الساعة الذكية برو وهل هي متوفرة؟")
        assertTrue(res.matched)
        assertTrue(res.calledSmartAdvisor)
        assertTrue(res.verifiedStoreDataUsed)
        assertTrue(res.replyText.contains("450 ريال"))
    }

    // 14. Smart Advisor empty/unverified response prevents price fabrication and sets needsHuman
    @Test
    fun `14 Smart Advisor unverified empty response blocks fabricated price and sets needsHuman`() = runTest {
        configureFullOrchestrator()
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"knowledge_enabled":true}""",
                    code = 200,
                    ms = 10L
                )
                url.endsWith("/knowledge") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"verified":false,"intent":"product_details","summary":"","facts":[],"items":[]}""",
                    code = 200,
                    ms = 10L
                )
                body.orEmpty().contains("نتائج الأدوات المسترجعة") ->
                    // Even if LLM hallucinated a price in Pass 2, Guard checks !verifiedDataAvailable -> replaces with no-verified safe reply + needsHuman=true!
                    chatCompletionSuccess("""{"intent":"product_details","confidence":0.4,"needs_human":false,"reply":"السعر 999 ريال متوفر!"}""")
                else ->
                    chatCompletionSuccess("""{"intent":"product_details","confidence":0.8,"needs_store_data":true,"store_intent":"product_details","store_query":"منتج غير موجود","reply":"السعر 999 ريال"}""")
            }
        }

        val res = tester.evaluate("كم سعر المنتج الغريب؟")
        assertTrue(res.matched)
        assertTrue(res.calledSmartAdvisor)
        assertFalse(res.verifiedStoreDataUsed)
        assertTrue(res.needsHuman)
        assertFalse("Must never fabricate unverified price!", res.replyText.contains("999 ريال"))
    }

    // 15. Smart Advisor HTTP failure produces safe fallback without price fabrication or crash
    @Test
    fun `15 Smart Advisor HTTP error returns safe fallback without price fabrication`() = runTest {
        configureFullOrchestrator()
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success("""{"ok":true,"knowledge_enabled":true}""", 200, 10L)
                url.endsWith("/knowledge") -> HttpTransport.Result.Failure("503 Store Unavailable", 503)
                body.orEmpty().contains("نتائج الأدوات المسترجعة") ->
                    chatCompletionSuccess("""{"intent":"product_details","confidence":0.4,"needs_human":true,"reply":"تعذر التحقق من بيانات المتجر حالياً."}""")
                else ->
                    chatCompletionSuccess("""{"intent":"product_details","confidence":0.85,"needs_store_data":true,"store_intent":"product_details","store_query":"كم سعر سماعة؟","reply":""}""")
            }
        }

        val res = tester.evaluate("كم سعر السماعة؟")
        assertTrue(res.matched)
        assertTrue(res.calledSmartAdvisor)
        assertFalse(res.verifiedStoreDataUsed)
        assertTrue(res.needsHuman)
        assertFalse(res.replyText.contains("ريال"))
    }

    // 16. Webhook tool failure & webhookToolEnabled toggle enforcement
    @Test
    fun `16 webhook failure is safely handled without leaking secret and webhookToolEnabled disables AI tool`() = runTest {
        configureFullOrchestrator()
        val erpSecret = "erp-secret-token-444"
        serverRepo.save(
            ServerConfigEntity(
                isEnabled = true,
                url = "https://erp.example.com/api",
                headersJson = """[{"key":"Authorization","value":"Bearer $erpSecret"}]""",
                webhookMode = WebhookMode.AI_TOOL.name,
                toolDescription = "حالة الشحنات"
            )
        )
        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success("""{"ok":true,"knowledge_enabled":false}""", 200, 10L)
                url.contains("erp.example.com") -> HttpTransport.Result.Failure("HTTP 502 with $erpSecret", 502)
                body.orEmpty().contains("نتائج الأدوات المسترجعة") ->
                    chatCompletionSuccess("""{"intent":"erp","confidence":0.5,"needs_human":true,"reply":"تعذر جلب حالة الشحنة حالياً."}""")
                else ->
                    chatCompletionSuccess("""{"intent":"erp","confidence":0.9,"needs_webhook_data":true,"webhook_query":"ORD-55","webhook_reason":"شحنة","reply":""}""")
            }
        }

        val failOutcome = tester.evaluate("أين طلبي ORD-55؟")
        assertTrue(failOutcome.calledWebhook)
        assertFalse(failOutcome.replyText.contains(erpSecret))
        assertFalse(failOutcome.webhookDataSnippet.contains(erpSecret))

        // Disable webhook tool in AI settings -> Webhook must not be called even if LLM asks for it
        aiSettingsPrefs.save(aiSettingsPrefs.current().copy(webhookToolEnabled = false))
        transport.calls.clear()
        val blockedToolRes = tester.evaluate("أين طلبي ORD-55؟")
        assertFalse(blockedToolRes.calledWebhook)
        assertTrue(transport.calls.none { it.url.contains("erp.example.com") })
    }

    // 17. Duplicate incoming notifications produce strictly 1 reply via IdempotencyGuard & NotificationCoordinator
    @Test
    fun `17 duplicate incoming notification never sends double reply`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-dedup")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                saCollaborationEnabled = false
            )
        )
        transport.handler = { _, _, _, _, _ ->
            chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"تم استلام رسالتك مرة واحدة."}""")
        }
        val dispatched = mutableListOf<String>()
        val payload = NotificationPayload(
            packageName = "com.whatsapp",
            sbnKey = "0|com.whatsapp|99|tag|0",
            notificationId = "n99",
            senderName = "زياد",
            rawTitle = "زياد",
            messageText = "السلام عليكم",
            replyAction = dummyAction(),
            directSendInvoker = { _, text -> dispatched += text }
        )

        val first = notificationCoordinator.process(payload)
        val second = notificationCoordinator.process(payload)
        assertTrue(first.isHandled)
        assertFalse(second.isHandled)
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_DEDUP, second.deliveryMethod)
        assertEquals(1, dispatched.size)
    }

    // 18. DELIVERY_UNKNOWN recorded safely on uncertain send or uncertain HTTP failure
    @Test
    fun `18 delivery unknown stops retry loop and preserves dedup state`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-unk")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                saCollaborationEnabled = false
            )
        )
        transport.handler = { _, _, _, _, _ ->
            chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"رد تجريبي"}""")
        }
        val payload = NotificationPayload(
            packageName = "com.whatsapp",
            sbnKey = "0|com.whatsapp|188|tag|0",
            notificationId = "n188",
            senderName = "طارق",
            rawTitle = "طارق",
            messageText = "مرحبا",
            replyAction = dummyAction(),
            directSendInvoker = { _, _ -> throw RuntimeException("Transient IPC uncertain timeout") }
        )

        val result = notificationCoordinator.process(payload)
        assertFalse(result.isHandled)
        assertEquals(MessageQueueWorker.STATUS_DELIVERY_UNKNOWN, result.deliveryMethod)
        val dedupRow = db.dedupKeyDao().getByKey(result.msgKey)
        assertEquals("DELIVERY_UNKNOWN", dedupRow?.sendState)

        // Second attempt with same payload must be skipped as dedup instead of resending
        val retry = notificationCoordinator.process(payload)
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_DEDUP, retry.deliveryMethod)
    }

    // 19. Provider failover/retry for same msgKey NEVER re-executes Smart Advisor or Webhook tool
    @Test
    fun `19 failover and retry never duplicate SmartAdvisor or Webhook execution for same message`() = runTest {
        configureFullOrchestrator()
        serverRepo.save(
            ServerConfigEntity(
                isEnabled = true,
                url = "https://crm.example.com/hook",
                webhookMode = WebhookMode.AI_TOOL.name,
                toolDescription = "سجل العميل"
            )
        )
        val saCalls = AtomicInteger(0)
        val webhookCalls = AtomicInteger(0)

        transport.handler = { url, _, _, body, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success("""{"ok":true,"knowledge_enabled":true}""", 200, 10L)
                url.endsWith("/knowledge") -> {
                    saCalls.incrementAndGet()
                    HttpTransport.Result.Success(
                        body = """{"ok":true,"verified":true,"intent":"product_details","summary":"سعر المنتج 120 ريال","facts":["120 ريال"]}""",
                        code = 200,
                        ms = 10L
                    )
                }
                url.contains("crm.example.com") -> {
                    webhookCalls.incrementAndGet()
                    HttpTransport.Result.Success("""{"reply":"العميل VIP"}""", 200, 10L)
                }
                body.orEmpty().contains("نتائج الأدوات المسترجعة") ->
                    chatCompletionSuccess("""{"intent":"combo","confidence":0.95,"needs_human":false,"reply":"السعر 120 ريال وبحسب CRM أنت عميل VIP."}""")
                else ->
                    chatCompletionSuccess("""{"intent":"combo","confidence":0.9,"needs_store_data":true,"store_intent":"product_details","store_query":"حقيبة","needs_webhook_data":true,"webhook_query":"VIP","reply":""}""")
            }
        }

        // First call with msgKey = "msg-idemp-tool-1"
        val r1 = unifiedService.resolve(
            UnifiedSmartReplyService.Request(
                senderName = "عمر",
                messageText = "ما سعر الحقيبة وهل أنا VIP؟",
                msgKey = "msg-idemp-tool-1",
                isSandbox = false
            )
        )
        // Simulated retry with same msgKey
        val r2 = unifiedService.resolve(
            UnifiedSmartReplyService.Request(
                senderName = "عمر",
                messageText = "ما سعر الحقيبة وهل أنا VIP؟",
                msgKey = "msg-idemp-tool-1",
                isSandbox = false
            )
        )
        assertTrue(r1.matched && r2.matched)
        assertEquals("Smart Advisor must only execute once for msgKey across retries", 1, saCalls.get())
        assertEquals("Webhook must only execute once for msgKey across retries", 1, webhookCalls.get())
    }

    // 20. Sensitive account actions blocked in Group chats and blocked without explicit user confirmation in DM
    @Test
    fun `20 sensitive account actions blocked in groups and without explicit user confirmation`() = runTest {
        configureFullOrchestrator()
        settingsDataStore.setReplyInGroups(true)
        val accountActionCalls = AtomicInteger(0)

        transport.handler = { url, _, _, _, _ ->
            when {
                url.endsWith("/capabilities") -> HttpTransport.Result.Success(
                    body = """{"ok":true,"knowledge_enabled":true,"account_actions_enabled":true}""",
                    code = 200,
                    ms = 10L
                )
                url.endsWith("/account-action") -> {
                    accountActionCalls.incrementAndGet()
                    HttpTransport.Result.Success("""{"ok":true,"link_created":true,"ephemeral_login_url":"https://shop.example.com/go?t=1"}""", 200, 10L)
                }
                else ->
                    chatCompletionSuccess("""{"intent":"account","confidence":0.95,"account_action":"magic_login","account_action_confirmed":false,"reply":"تم التحقق من الطلب."}""")
            }
        }

        // Group chat -> blocked even if customer explicitly asked for login link
        val groupOutcome = tester.evaluate("أرسل لي رابط الدخول السريع", isGroup = true)
        assertEquals(0, accountActionCalls.get())
        assertTrue(groupOutcome.replyText.contains("المجموعات"))

        // DM without explicit confirmation / ambiguous phrasing -> asks confirmation without executing action
        val dmNoConsent = tester.evaluate("ماذا عن حسابي؟", isGroup = false)
        assertEquals(0, accountActionCalls.get())
        assertTrue(dmNoConsent.matched)
        assertTrue(dmNoConsent.replyText.contains("تؤكد"))
    }

    // 21. Sandbox preview uses exact same service as live AutoReplyEngine and leaves zero production DB rows
    @Test
    fun `21 sandbox preview matches live decision and mutates zero production DB tables`() = runTest {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-parity")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                saCollaborationEnabled = false
            )
        )
        transport.handler = { _, _, _, _, _ ->
            chatCompletionSuccess("""{"intent":"general","confidence":0.9,"needs_store_data":false,"reply":"رد موحد مطابق تماماً."}""")
        }

        val sandboxRes = tester.evaluate("مرحباً", senderName = "أحمد")
        assertTrue(db.replyLogDao().getLogsSince(0L).isEmpty())
        assertNull(db.dedupKeyDao().getByKey("unified-test-"))
        assertTrue(db.memoryOutboxDao().getPending().isEmpty())

        val liveDispatched = mutableListOf<String>()
        val liveOutcome = autoReplyEngine.processIncomingMessage(
            makeIncoming(sender = "أحمد", message = "مرحباً", msgKey = "live_parity_1", dispatched = liveDispatched)
        )
        assertTrue(liveOutcome is LocalOutcome.Handled)
        assertEquals(sandboxRes.replyText, (liveOutcome as LocalOutcome.Handled).replyText)
        assertEquals(listOf("رد موحد مطابق تماماً."), liveDispatched)
    }

    // 22. Settings persistence across AiSettingsPrefs and ViewModel recreation (simulating restart)
    @Test
    fun `22 tone length language and unified toggles persist across Prefs and ViewModel reload`() = runTest {
        val prefsName = "persist_ai_$prefsSuffix"
        val sharedPrefs1 = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val aiPrefs1 = AiSettingsPrefs(context, sharedPrefs1)
        val vm1 = AISettingsViewModel(aiPrefs1, secretVault, aiReplyEngine)

        vm1.setEnabled(true)
        vm1.selectResponseTone(ResponseTone.MARKETING)
        vm1.selectResponseLength(ResponseLength.MEDIUM)
        vm1.updateMaxReplyLengthChars("480")
        vm1.selectReplyLanguage(ReplyLanguage.CUSTOM)
        vm1.updateCustomReplyLanguage("الفرنسية")
        vm1.setUseConversationContext(false)
        vm1.setUseKeywordRules(false)
        vm1.setWebhookToolEnabled(false)
        vm1.saveSettings()

        // Simulate app restart with fresh Prefs & ViewModel instances over same SharedPreferences
        val sharedPrefs2 = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val aiPrefs2 = AiSettingsPrefs(context, sharedPrefs2)
        val vm2 = AISettingsViewModel(aiPrefs2, secretVault, aiReplyEngine)
        val reloaded = vm2.uiState.value

        assertTrue(reloaded.enabled)
        assertEquals(ResponseTone.MARKETING, reloaded.responseTone)
        assertEquals(ResponseLength.MEDIUM, reloaded.responseLength)
        assertEquals(480, reloaded.maxReplyLengthChars)
        assertEquals(ReplyLanguage.CUSTOM, reloaded.replyLanguage)
        assertEquals("الفرنسية", reloaded.customReplyLanguage)
        assertFalse(reloaded.useConversationContext)
        assertFalse(reloaded.useKeywordRules)
        assertFalse(reloaded.webhookToolEnabled)
    }

    private fun configureFullOrchestrator() {
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-orch-main")
        secretVault.put("sa_orch_key", "sa-secret-key")
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.OPENAI,
                fallbackEnabled = false,
                saCollaborationEnabled = true,
                webhookToolEnabled = true,
                useConversationContext = true,
                useKeywordRules = true,
                responseTone = ResponseTone.PROFESSIONAL,
                responseLength = ResponseLength.SHORT,
                maxReplyLengthChars = 320
            )
        )
        smartAdvisorPrefs.saveCredentials(
            baseUrl = "https://shop.example.com",
            apiKeyRef = "sa_orch_key",
            enabled = true
        )
    }

    private fun chatCompletionSuccess(jsonContent: String): HttpTransport.Result.Success {
        val escaped = Json.encodeToString(kotlinx.serialization.serializer(), jsonContent)
        return HttpTransport.Result.Success(
            body = """{"choices":[{"message":{"content":$escaped}}]}""",
            code = 200,
            ms = 12L
        )
    }

    private fun makeIncoming(
        sender: String,
        message: String,
        msgKey: String,
        dispatched: MutableList<String>
    ) = com.example.engine.IncomingNotificationMessage(
        packageName = "com.whatsapp",
        senderName = sender,
        messageText = message,
        isGroup = false,
        replyAction = dummyAction(),
        contentIntent = null,
        receivedAtMs = fakeNowMs,
        msgKey = msgKey,
        directSendInvoker = { _, text -> dispatched += text }
    )

    private fun dummyAction(): Notification.Action {
        val intent = Intent("com.example.TEST_REPLY")
        val pi = PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remoteInput = android.app.RemoteInput.Builder("key_text_reply")
            .setLabel("رد")
            .build()
        return Notification.Action.Builder(0, "رد", pi)
            .addRemoteInput(remoteInput)
            .build()
    }
}
