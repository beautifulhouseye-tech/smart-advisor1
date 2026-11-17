package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.MenuReplyEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.MenuOption
import com.example.engine.MenuReplyManager
import com.example.engine.ReplySourceCategory
import com.example.engine.UnifiedReplyTester
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.ui.screens.TestReplyContent
import com.example.ui.theme.AutoMateTheme
import com.example.ui.viewmodel.TestChatMessage
import com.example.ui.viewmodel.TestReplyUiState
import com.example.ui.viewmodel.TestReplyViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnifiedReplyTestSuiteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeHttpTransport(
        var handler: suspend (url: String, method: String, headers: Map<String, String>, body: String?) -> Result =
            { _, _, _, _ -> Result.Success(body = """{"reply":"رد افتراضي"}""", code = 200, ms = 12L) }
    ) : HttpTransport() {
        val requests = mutableListOf<RecordedRequest>()

        data class RecordedRequest(
            val url: String,
            val method: String,
            val headers: Map<String, String>,
            val body: String?
        )

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
            requests += RecordedRequest(url, method, headers, body)
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
    private lateinit var fakeTransport: FakeHttpTransport
    private lateinit var aiReplyEngine: AIReplyEngine
    private lateinit var webhookClient: WebhookClient
    private lateinit var smartAdvisorClient: SmartAdvisorClient
    private lateinit var tester: UnifiedReplyTester

    private var fakeNowMs: Long = 1_763_316_600_000L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsDataStore = SettingsDataStore(context)
        resetSharedSettings()

        val json = Json { ignoreUnknownKeys = true }
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
            context.getSharedPreferences("test_vault_$suffix", Context.MODE_PRIVATE)
        )
        aiSettingsPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("test_ai_prefs_$suffix", Context.MODE_PRIVATE)
        )
        smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("test_sa_prefs_$suffix", Context.MODE_PRIVATE)
        )

        fakeTransport = FakeHttpTransport()
        aiReplyEngine = AIReplyEngine(fakeTransport, secretVault)
        webhookClient = WebhookClient(fakeTransport, secretVault)
        smartAdvisorClient = SmartAdvisorClient(fakeTransport, smartAdvisorPrefs, secretVault)

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
        settingsDataStore.setDrivingModeReplyText("أنا أقود السيارة حالياً، سأتواصل معك فور وصولي بأمان.")
    }

    private fun awaitCondition(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(15)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition was not satisfied within ${timeoutMs}ms", condition())
    }

    @Test
    fun `evaluate prioritizes driving mode over menus rules and AI`() = runTest {
        settingsDataStore.setDrivingModeEnabled(true)
        settingsDataStore.setDrivingModeReplyText("أنا أقود الآن، سأرد لاحقاً.")
        repository.upsertRule(
            ReplyRuleEntity(keyword = "مرحبا", replyText = "رد قاعدة محلية", matchType = MatchType.CONTAINS, isEnabled = true, priority = 10)
        )
        aiSettingsPrefs.save(AiSettingsState(enabled = true, provider = AIReplyEngine.Provider.OPENAI))

        val result = tester.evaluate("مرحبا")

        assertTrue(result.matched)
        assertEquals(ReplySourceCategory.DRIVING_MODE, result.sourceCategory)
        assertEquals("أنا أقود الآن، سأرد لاحقاً.", result.replyText)
        assertTrue(fakeTransport.requests.isEmpty())
    }

    @Test
    fun `evaluate handles multi step menu trigger then option selection and resets session`() = runTest {
        val options = listOf(
            MenuOption(1, "الباقات", "تبدأ الباقات من 99 ريال"),
            MenuOption(2, "الدعم", "تواصل معنا على 92000")
        )
        db.menuReplyDao().insert(
            MenuReplyEntity(
                trigger = "القائمة",
                title = "اختر الخدمة:",
                optionsJson = menuReplyManager.encodeOptions(options),
                enabled = true
            )
        )

        val step1 = tester.evaluate("القائمة")
        assertTrue(step1.matched)
        assertEquals(ReplySourceCategory.MENU_REPLY, step1.sourceCategory)
        assertTrue(step1.replyText.contains("1. الباقات"))
        assertTrue(step1.replyText.contains("2. الدعم"))

        val step2 = tester.evaluate("2")
        assertTrue(step2.matched)
        assertEquals(ReplySourceCategory.MENU_REPLY, step2.sourceCategory)
        assertEquals("تواصل معنا على 92000", step2.replyText)

        // After option completion, selecting "1" should not match menu anymore
        val step3 = tester.evaluate("1")
        assertFalse(step3.matched)
        assertEquals(ReplySourceCategory.NO_MATCH, step3.sourceCategory)

        // Start again then resetSession clears active menu state
        val step4 = tester.evaluate("القائمة")
        assertTrue(step4.matched)
        tester.resetSession()
        val afterReset = tester.evaluate("1")
        assertFalse(afterReset.matched)
        assertEquals(ReplySourceCategory.NO_MATCH, afterReset.sourceCategory)
    }

    @Test
    fun `evaluate matches specific rules before AI and falls back to wildcard when AI returns empty`() = runTest {
        repository.upsertRule(
            ReplyRuleEntity(
                keyword = "سعر",
                replyText = "السعر 150 ريال",
                matchType = MatchType.CONTAINS,
                isEnabled = true,
                priority = 10,
                replyType = ReplyRuleEntity.REPLY_TYPE_BOTH,
                imageUri = "content://media/external/images/1"
            )
        )
        repository.upsertRule(
            ReplyRuleEntity(
                keyword = "*",
                replyText = "رد افتراضي عام",
                matchType = MatchType.CONTAINS,
                isEnabled = true,
                priority = 1
            )
        )
        secretVault.put(AiSettingsPrefs.apiKeyRefFor(AIReplyEngine.Provider.OPENAI), "sk-test-123")
        aiSettingsPrefs.save(AiSettingsState(enabled = true, provider = AIReplyEngine.Provider.OPENAI))

        // Specific rule wins before AI or wildcard
        val specific = tester.evaluate("كم سعر الاشتراك؟")
        assertTrue(specific.matched)
        assertEquals(ReplySourceCategory.LOCAL_RULE, specific.sourceCategory)
        assertEquals("content://media/external/images/1", specific.imageUri)
        assertTrue(specific.replyText.contains("السعر 150 ريال"))
        assertTrue(fakeTransport.requests.isEmpty())

        // AI succeeds when specific rule doesn't match
        fakeTransport.handler = { url, _, _, _ ->
            assertTrue(url.contains("api.openai.com"))
            HttpTransport.Result.Success(
                body = """{"choices":[{"message":{"content":"أهلاً! يسعدني مساعدتك عبر الذكاء الاصطناعي."}}]}""",
                code = 200,
                ms = 25L
            )
        }
        val aiResult = tester.evaluate("ما هي ساعات العمل؟")
        assertTrue(aiResult.matched)
        assertEquals(ReplySourceCategory.AI_ENGINE, aiResult.sourceCategory)
        assertEquals("أهلاً! يسعدني مساعدتك عبر الذكاء الاصطناعي.", aiResult.replyText)

        // When AI fails, wildcard "*" rule catches the message
        fakeTransport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("timeout", 504, 10L)
        }
        val wildcardResult = tester.evaluate("رسالة غير معروفة")
        assertTrue(wildcardResult.matched)
        assertEquals(ReplySourceCategory.WILDCARD_RULE, wildcardResult.sourceCategory)
        assertEquals("رد افتراضي عام", wildcardResult.replyText)
    }

    @Test
    fun `evaluate invokes webhook and smart advisor and maps errors accurately`() = runTest {
        serverConfigRepository.save(
            ServerConfigEntity(
                isEnabled = true,
                url = "https://webhook.example.com/reply",
                method = "POST",
                responseReplyKey = "data.reply"
            )
        )
        fakeTransport.handler = { url, _, _, _ ->
            assertEquals("https://webhook.example.com/reply", url)
            HttpTransport.Result.Success(
                body = """{"data":{"reply":"تم استلام طلبك من الخادم"}}""",
                code = 200,
                ms = 30L
            )
        }

        val webhookSuccess = tester.evaluate("تفاصيل الطلب")
        assertTrue(webhookSuccess.matched)
        assertEquals(ReplySourceCategory.WEBHOOK, webhookSuccess.sourceCategory)
        assertEquals("تم استلام طلبك من الخادم", webhookSuccess.replyText)

        // Failure on webhook surfaces ERROR
        fakeTransport.handler = { _, _, _, _ ->
            HttpTransport.Result.Failure("Server unavailable", 503, 15L)
        }
        val webhookError = tester.evaluate("تفاصيل الطلب")
        assertFalse(webhookError.matched)
        assertEquals(ReplySourceCategory.ERROR, webhookError.sourceCategory)
        assertTrue(webhookError.replyText.contains("Server unavailable"))

        // Disable webhook, enable Smart Advisor
        serverConfigRepository.save(ServerConfigEntity(isEnabled = false))
        smartAdvisorPrefs.saveCredentials(
            baseUrl = "https://shop.example.com",
            apiKeyRef = "sa_key",
            enabled = true
        )
        secretVault.put("sa_key", "secret-sa-token")
        fakeTransport.handler = { url, _, headers, _ ->
            assertTrue(url.endsWith("/wp-json/smart-advisor/v1/whatsapp/reply"))
            assertEquals("secret-sa-token", headers[SmartAdvisorClient.HEADER_API_KEY])
            HttpTransport.Result.Success(
                body = """{"reply":"رد من مستشار المتجر"}""",
                code = 200,
                ms = 20L
            )
        }

        val saSuccess = tester.evaluate("هل المنتج متوفر؟")
        assertTrue(saSuccess.matched)
        assertEquals(ReplySourceCategory.SMART_ADVISOR, saSuccess.sourceCategory)
        assertEquals("رد من مستشار المتجر", saSuccess.replyText)
    }

    @Test
    fun `evaluate respects group policy and does not mutate reply logs or dedup keys`() = runTest {
        settingsDataStore.setAutoReplyEnabled(true)
        settingsDataStore.setReplyInGroups(false)
        repository.upsertRule(
            ReplyRuleEntity(keyword = "مرحبا", replyText = "أهلاً بك", matchType = MatchType.CONTAINS, isEnabled = true, priority = 10)
        )

        val blocked = tester.evaluate("مرحبا", isGroup = true)
        assertFalse(blocked.matched)
        assertEquals(ReplySourceCategory.POLICY_BLOCKED, blocked.sourceCategory)

        settingsDataStore.setReplyInGroups(true)
        val allowed = tester.evaluate("مرحبا", isGroup = true)
        assertTrue(allowed.matched)
        assertEquals(ReplySourceCategory.LOCAL_RULE, allowed.sourceCategory)
        assertEquals("أهلاً بك", allowed.replyText)

        // Side-effect-free guarantee: zero reply_logs and zero dedup_keys written
        assertTrue(db.replyLogDao().getLogsSince(0L).isEmpty())
        assertNull(db.dedupKeyDao().getByKey("test_unified_reply:default"))
    }

    @Test
    fun `TestReplyViewModel sends messages updates group mode and resets conversation`() = runTest {
        repository.upsertRule(
            ReplyRuleEntity(keyword = "أهلا", replyText = "وعليكم السلام", matchType = MatchType.CONTAINS, isEnabled = true, priority = 10)
        )

        val vm = TestReplyViewModel(
            unifiedReplyTester = tester,
            repository = repository,
            aiSettingsPrefs = aiSettingsPrefs,
            serverConfigRepository = serverConfigRepository,
            smartAdvisorPrefs = smartAdvisorPrefs,
            clockMs = { fakeNowMs }
        )

        assertEquals(1, vm.uiState.value.messages.size)
        assertEquals(TestReplyViewModel.WELCOME_TEXT, vm.uiState.value.messages.first().text)
        assertTrue(vm.uiState.value.messages.first().isWelcome)

        vm.sendTestMessage("   ")
        assertEquals(1, vm.uiState.value.messages.size)

        vm.sendTestMessage("أهلا بك")
        awaitCondition { vm.uiState.value.messages.size == 3 && !vm.uiState.value.isEvaluating }

        val messages = vm.uiState.value.messages
        assertTrue(messages[1].isFromUser)
        assertEquals("أهلا بك", messages[1].text)
        assertFalse(messages[2].isFromUser)
        assertEquals("وعليكم السلام", messages[2].text)
        assertEquals(ReplySourceCategory.LOCAL_RULE, messages[2].sourceCategory)

        vm.toggleGroupMode(true)
        assertTrue(vm.uiState.value.isGroupMode)

        vm.resetConversation()
        assertEquals(1, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.messages.first().isWelcome)
    }

    @Test
    fun `TestReplyContent renders WhatsApp style layout handles send input and actions`() {
        var sentMessages by mutableStateOf(listOf<String>())
        var groupToggledTo by mutableStateOf<Boolean?>(null)
        var resetCount by mutableStateOf(0)
        var backCount by mutableStateOf(0)
        var uiState by mutableStateOf(
            TestReplyUiState(
                messages = listOf(
                    TestChatMessage(
                        id = "welcome_msg",
                        text = TestReplyViewModel.WELCOME_TEXT,
                        isFromUser = false,
                        timestampMs = fakeNowMs,
                        isWelcome = true
                    )
                ),
                isEvaluating = false,
                isGroupMode = false,
                activePipelineSummary = "القوائم • القواعد"
            )
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AutoMateTheme(darkTheme = true) {
                    TestReplyContent(
                        uiState = uiState,
                        onBack = { backCount++ },
                        onSendMessage = { text ->
                            sentMessages = sentMessages + text
                            uiState = uiState.copy(
                                messages = uiState.messages + listOf(
                                    TestChatMessage(
                                        id = "u_${sentMessages.size}",
                                        text = text,
                                        isFromUser = true,
                                        timestampMs = fakeNowMs
                                    ),
                                    TestChatMessage(
                                        id = "b_${sentMessages.size}",
                                        text = "رد تلقائي تجريبي",
                                        isFromUser = false,
                                        timestampMs = fakeNowMs,
                                        sourceLabel = "قاعدة: مرحبا",
                                        sourceCategory = ReplySourceCategory.LOCAL_RULE,
                                        evaluatedPath = listOf("القوائم التفاعلية", "القواعد المحلية"),
                                        latencyMs = 4L
                                    )
                                )
                            )
                        },
                        onToggleGroupMode = { enabled ->
                            groupToggledTo = enabled
                            uiState = uiState.copy(isGroupMode = enabled)
                        },
                        onReset = { resetCount++ }
                    )
                }
            }
        }

        composeRule.waitForIdle()

        // Verify initial WhatsApp-like screenshot elements
        composeRule.onNodeWithTag("test_reply_title").assertIsDisplayed()
        composeRule.onNodeWithTag("test_reply_day_chip").assertIsDisplayed()
        composeRule.onNodeWithText("اليوم").assertIsDisplayed()
        composeRule.onNodeWithText(TestReplyViewModel.WELCOME_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("اكتب رسالة").assertIsDisplayed()
        composeRule.onNodeWithTag("test_reply_send_button").assertIsNotEnabled()

        // Type text and send
        composeRule.onNodeWithTag("test_reply_input").performTextInput("مرحبا")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("test_reply_send_button").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("مرحبا"), sentMessages)
        assertEquals(1, composeRule.onAllNodesWithTag("chat_bubble_user").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithTag("chat_bubble_bot").fetchSemanticsNodes().size)
        assertTrue(composeRule.onAllNodesWithText("رد تلقائي تجريبي").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("قاعدة: مرحبا").fetchSemanticsNodes().isNotEmpty())

        // Toggle group mode, reset, and back buttons
        composeRule.onNodeWithTag("test_reply_group_toggle").performClick()
        composeRule.waitForIdle()
        assertEquals(true, groupToggledTo)

        composeRule.onNodeWithTag("test_reply_reset_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, resetCount)

        composeRule.onNodeWithTag("test_reply_back_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, backCount)
    }
}
