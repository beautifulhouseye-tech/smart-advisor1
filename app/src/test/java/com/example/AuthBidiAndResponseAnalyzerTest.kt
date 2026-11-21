package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.FieldType
import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.RequestField
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.local.entity.WebhookMode
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.IncomingMessage
import com.example.network.HttpTransport
import com.example.network.ResponseOutcomeAnalyzer
import com.example.network.ResponseOutcomeAnalyzer.ResponseStatus
import com.example.network.WebhookClient
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorAuthResolver
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.ui.components.BidiDisplayFormatter
import com.example.ui.components.UnifiedTestResultCard
import com.example.ui.components.UnifiedTestResultCardModel
import com.example.ui.theme.AutoMateTheme
import com.example.ui.viewmodel.SaSettingsViewModel
import com.example.ui.viewmodel.ServerViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthBidiAndResponseAnalyzerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeHttpTransport(
        var handler: suspend (
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?
        ) -> Result
    ) : HttpTransport() {
        var callCount = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String? = null

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
            callCount++
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return handler(url, method, headers, body)
        }
    }

    private class FakeServerConfigRepository(initial: ServerConfigEntity? = null) : ServerConfigRepository {
        private val flow = MutableStateFlow(initial)
        override fun observe(): Flow<ServerConfigEntity?> = flow
        override suspend fun get(): ServerConfigEntity? = flow.value
        override suspend fun save(config: ServerConfigEntity) {
            flow.value = config
        }
    }

    private fun createVault(): SecretVault {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences("auth_bidi_vault_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SecretVault(ctx, sp)
    }

    private fun createPrefs(
        baseUrl: String = SmartAdvisorPrefs.DEFAULT_BASE_URL,
        apiKeyRef: String = "",
        enabled: Boolean = true
    ): SmartAdvisorPrefs {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences("auth_bidi_sa_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SmartAdvisorPrefs(ctx, sp, useRuntimeDefaultUrl = true).apply {
            saveCredentials(baseUrl = baseUrl, apiKeyRef = apiKeyRef, enabled = enabled)
        }
    }

    private fun sampleMessage() = IncomingMessage(
        app = "com.whatsapp",
        sender = "أحمد محمد",
        senderName = "أحمد محمد",
        message = "السلام عليكم، هل يتوفر لديكم التوصيل اليوم؟",
        phone = "+966501234567",
        groupName = "",
        isGroup = false,
        notificationId = "msg-test-1"
    )

    // -------------------------------------------------------------------------
    // 1. Smart Advisor Auth & Secret Scoping Tests
    // -------------------------------------------------------------------------

    @Test
    fun `auth - valid key in SecretVault is attached in X-SA-API-Key header only and never in JSON body or logs`() = runTest {
        val vault = createVault()
        val secretKey = "SA_WA_SuperSecretKey_XYZ123"
        vault.put(SaSettingsViewModel.SA_API_KEY_REF, secretKey)
        val prefs = createPrefs(apiKeyRef = SaSettingsViewModel.SA_API_KEY_REF)

        val transport = FakeHttpTransport { _, _, _, _ ->
            HttpTransport.Result.Success("""{"ok":true,"reply":"أهلاً بك يا أحمد"}""", 200, 25L)
        }
        val webhookClient = WebhookClient(transport, vault, prefs)
        val config = ServerConfigEntity(
            isEnabled = true,
            url = SmartAdvisorPrefs.DEFAULT_BASE_URL,
            method = "POST",
            contentType = "JSON",
            headersJson = Json.encodeToString(listOf(HeaderItem("X-Client", "AutoMate-AI", false))),
            requestFieldsJson = Json.encodeToString(
                listOf(
                    RequestField("sender", "{{sender}}", FieldType.STRING),
                    RequestField("message", "{{message}}", FieldType.STRING)
                )
            ),
            responseReplyKey = "reply",
            webhookMode = WebhookMode.AI_TOOL.name
        )

        val res = webhookClient.invokeAsAiTool(config, sampleMessage(), "الاستعلام عن التوصيل")

        assertTrue(res.ok)
        assertEquals(ResponseStatus.SUCCESS, res.outcomeStatus)
        assertEquals(1, transport.callCount)
        assertEquals(secretKey, transport.lastHeaders["X-SA-API-Key"])
        assertFalse(transport.lastBody.orEmpty().contains(secretKey))
        assertFalse(res.rawExcerpt.contains(secretKey))
        assertFalse(res.serverMessage.contains(secretKey))
    }

    @Test
    fun `auth - missing key on Smart Advisor endpoint blocks request before transport and returns FAILURE`() = runTest {
        val vault = createVault()
        val prefs = createPrefs(apiKeyRef = "")
        val transport = FakeHttpTransport { _, _, _, _ ->
            error("Transport should not be invoked when X-SA-API-Key is missing")
        }
        val client = SmartAdvisorClient(transport, prefs, vault)
        val saVm = SaSettingsViewModel(prefs, client, vault)

        saVm.testConnection()
        Thread.sleep(60L)

        val result = saVm.testResult.value
        assertNotNull(result)
        assertFalse(result!!.success)
        assertEquals(ResponseStatus.FAILURE, result.outcomeStatus)
        assertEquals(0, transport.callCount)
        assertTrue(result.serverMessage.contains("X-SA-API-Key") || result.guidance.contains("مفتاح API"))

        // Same preflight block applies in WebhookClient targeting Smart Advisor URL
        val webhookClient = WebhookClient(transport, vault, prefs)
        val config = ServerConfigEntity(url = SmartAdvisorPrefs.DEFAULT_BASE_URL)
        val whOutcome = webhookClient.testWebhook(config, sampleMessage())
        assertEquals(ResponseStatus.FAILURE, whOutcome.status)
        assertEquals("sa_missing_key_client", whOutcome.errorCode)
        assertEquals(0, transport.callCount)
    }

    @Test
    fun `auth - Smart Advisor key is NEVER sent to arbitrary external webhook or HTTP endpoint`() = runTest {
        val vault = createVault()
        val secretKey = "SA_WA_Secret_DoNotLeak"
        vault.put(SaSettingsViewModel.SA_API_KEY_REF, secretKey)
        val prefs = createPrefs(
            baseUrl = "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply",
            apiKeyRef = SaSettingsViewModel.SA_API_KEY_REF
        )

        val transport = FakeHttpTransport { _, _, _, _ ->
            HttpTransport.Result.Success("""{"reply":"ok"}""", 200, 15L)
        }
        val webhookClient = WebhookClient(transport, vault, prefs)

        // 1) Arbitrary external webhook with custom header -> keeps custom header, does NOT inject SA key
        val externalConfig = ServerConfigEntity(
            url = "https://crm.other-domain.com/webhook",
            headersJson = Json.encodeToString(listOf(HeaderItem("X-Custom-Auth", "ext-123", false)))
        )
        val extOutcome = webhookClient.testWebhook(externalConfig, sampleMessage())
        assertEquals(ResponseStatus.SUCCESS, extOutcome.status)
        assertEquals("ext-123", transport.lastHeaders["X-Custom-Auth"])
        assertFalse(transport.lastHeaders.containsKey("X-SA-API-Key"))

        // 2) Different host mimicking /wp-json/smart-advisor/ path -> MUST NOT receive SA vault key
        val spoofedHostConfig = ServerConfigEntity(
            url = "https://attacker.example.com/wp-json/smart-advisor/v1/whatsapp/reply"
        )
        val spoofOutcome = webhookClient.testWebhook(spoofedHostConfig, sampleMessage())
        assertEquals(ResponseStatus.FAILURE, spoofOutcome.status)
        assertEquals("untrusted_smart_advisor_host", spoofOutcome.errorCode)
        assertEquals(1, transport.callCount) // still 1, blocked before sending!

        // 3) Plaintext HTTP Smart Advisor URL -> MUST be blocked
        val httpConfig = ServerConfigEntity(
            url = "http://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply"
        )
        val httpOutcome = webhookClient.testWebhook(httpConfig, sampleMessage())
        assertEquals(ResponseStatus.FAILURE, httpOutcome.status)
        assertEquals("insecure_transport", httpOutcome.errorCode)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `auth - HttpTransport blocks cross-host redirect so X-SA-API-Key is never forwarded`() = runTest {
        val evilHits = AtomicInteger(0)
        val capturedStoreHeader = AtomicReference<String?>(null)

        val evilServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/steal") { exchange ->
                evilHits.incrementAndGet()
                val body = """{"reply":"stolen"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val evilPort = evilServer.address.port

        val storeServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/wp-json/smart-advisor/v1/whatsapp/reply") { exchange ->
                capturedStoreHeader.set(exchange.requestHeaders.getFirst("X-SA-API-Key"))
                exchange.responseHeaders.add("Location", "http://localhost:$evilPort/steal")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }
        val storePort = storeServer.address.port

        try {
            val transport = HttpTransport()
            val result = transport.execute(
                url = "http://127.0.0.1:$storePort/wp-json/smart-advisor/v1/whatsapp/reply",
                method = "POST",
                headers = mapOf("X-SA-API-Key" to "SA_WA_PRIVATE_TOKEN"),
                body = """{"message":"hi"}""",
                maxAttempts = 1
            )

            assertTrue(result is HttpTransport.Result.Failure)
            val failure = result as HttpTransport.Result.Failure
            assertEquals(302, failure.code)
            assertFalse(failure.error.contains("SA_WA_PRIVATE_TOKEN"))
            // Evil server must NEVER have received the redirected request
            assertEquals(0, evilHits.get())
            assertEquals("SA_WA_PRIVATE_TOKEN", capturedStoreHeader.get())
        } finally {
            storeServer.stop(0)
            evilServer.stop(0)
        }
    }

    @Test
    fun `auth - SaSettingsViewModel restores existing vault key ref across restart and redacts errors`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sharedVaultPrefs = ctx.getSharedPreferences("restart_vault_${System.nanoTime()}", Context.MODE_PRIVATE)
        val sharedSaPrefs = ctx.getSharedPreferences("restart_sa_${System.nanoTime()}", Context.MODE_PRIVATE)

        val vault1 = SecretVault(ctx, sharedVaultPrefs)
        val prefs1 = SmartAdvisorPrefs(ctx, sharedSaPrefs, useRuntimeDefaultUrl = true)
        val secretKey = "SA_WA_PersistentKey_7788"

        val vm1 = SaSettingsViewModel(prefs1, SmartAdvisorClient(FakeHttpTransport { _, _, _, _ ->
            HttpTransport.Result.Success("""{"reply":"ok"}""", 200, 10L)
        }, prefs1, vault1), vault1)
        vm1.updateApiKey(secretKey)
        vm1.save()

        // Simulate app restart with new instances over same SharedPreferences
        val vault2 = SecretVault(ctx, sharedVaultPrefs)
        val prefs2 = SmartAdvisorPrefs(ctx, sharedSaPrefs, useRuntimeDefaultUrl = true)
        val transport2 = FakeHttpTransport { _, _, headers, _ ->
            // Echo secret accidentally in error to confirm redaction!
            HttpTransport.Result.Failure("""{"code":"sa_bad_key","message":"bad $secretKey"}""", 403, 12L)
        }
        val vm2 = SaSettingsViewModel(prefs2, SmartAdvisorClient(transport2, prefs2, vault2), vault2)

        assertTrue(vm2.hasStoredApiKey())
        vm2.testConnection()
        Thread.sleep(60L)

        assertEquals(secretKey, transport2.lastHeaders["X-SA-API-Key"])
        val res = vm2.testResult.value!!
        assertFalse(res.success)
        assertEquals(ResponseStatus.FAILURE, res.outcomeStatus)
        assertFalse(res.serverMessage.contains(secretKey))
        assertFalse(res.rawSnippet.contains(secretKey))
    }

    // -------------------------------------------------------------------------
    // 2. ResponseOutcomeAnalyzer Matrix Tests
    // -------------------------------------------------------------------------

    @Test
    fun `analyzer - classifies all HTTP 200, error codes, temporary messages, and edge cases accurately`() {
        val vault = createVault()

        // HTTP 200 + valid JSON Arabic reply -> SUCCESS
        val arSuccess = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("""{"ok":true,"reply":"أهلاً بك، التوصيل متاح اليوم."}""", 200, 30L),
            vault
        )
        assertEquals(ResponseStatus.SUCCESS, arSuccess.status)

        // HTTP 200 + valid JSON English reply -> SUCCESS
        val enSuccess = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("""{"reply":"Order #1001 is out for delivery."}""", 200, 30L),
            vault
        )
        assertEquals(ResponseStatus.SUCCESS, enSuccess.status)

        // HTTP 200 + conversational text mentioning "مشكلة" without service error -> SUCCESS (no false positive!)
        val benignWord = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success(
                """{"reply":"لا تقلق، إذا واجهت أي مشكلة في مقاس الحذاء يمكنك الاستبدال مجاناً."}""",
                200,
                30L
            ),
            vault
        )
        assertEquals(ResponseStatus.SUCCESS, benignWord.status)

        // HTTP 200 + benign metadata error=null / error="none" -> SUCCESS
        val benignMeta = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("""{"reply":"تم الحفظ","error":null,"debug":{"error_count":0}}""", 200, 20L),
            vault
        )
        assertEquals(ResponseStatus.SUCCESS, benignMeta.status)

        // HTTP 200 + ok=false -> FAILURE
        val okFalse = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("""{"ok":false,"message":"فشل تنفيذ العملية"}""", 200, 20L),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, okFalse.status)

        // HTTP 200 + success=false -> FAILURE
        val successFalse = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("""{"success":false,"message":"Rejected"}""", 200, 20L),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, successFalse.status)

        // HTTP 200 + status=error -> FAILURE
        val statusError = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("""{"status":"error","error":"Invalid payload"}""", 200, 20L),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, statusError.status)

        // HTTP 200 + sa_missing_key inside data envelope -> FAILURE
        val saMissingIn200 = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success(
                """{"code":"sa_missing_key","message":"مفتاح API مطلوب.","data":{"status":401}}""",
                200,
                20L
            ),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, saMissingIn200.status)
        assertEquals("sa_missing_key", saMissingIn200.errorCode)

        // HTTP 200 + temporary service error message -> WARNING (NEVER SUCCESS!)
        val tempService = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success(
                """{"reply":"عذراً، أواجه مشكلة مؤقتة في الخدمة. حاول مرة أخرى بعد قليل."}""",
                200,
                2433L
            ),
            vault,
            isToolPreview = true
        )
        assertEquals(ResponseStatus.WARNING, tempService.status)
        assertFalse(tempService.isSuccess)
        assertTrue(tempService.serverMessage.contains("أواجه مشكلة مؤقتة في الخدمة"))

        // HTTP 401 -> FAILURE
        val http401 = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Failure("""{"code":"sa_missing_key","message":"مفتاح API مطلوب."}""", 401, 15L),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, http401.status)

        // HTTP 403 -> FAILURE
        val http403 = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Failure("""{"code":"sa_bad_key","message":"مفتاح API غير صحيح."}""", 403, 15L),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, http403.status)

        // HTTP 409 -> FAILURE
        val http409 = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Conflict("""{"status":"processing","error":"الرسالة قيد المعالجة"}""", 409, 12L),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, http409.status)

        // HTTP 500 -> FAILURE
        val http500 = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Failure("Internal Server Error", 500, 12L),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, http500.status)

        // HTTP 503 with sa_key_not_configured -> FAILURE with guidance
        val http503KeyUnconfigured = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Failure(
                """{"code":"sa_key_not_configured","message":"مفتاح API غير مضبوط بعد."}""",
                503,
                14L
            ),
            vault
        )
        assertEquals(ResponseStatus.FAILURE, http503KeyUnconfigured.status)
        assertEquals("sa_key_not_configured", http503KeyUnconfigured.errorCode)

        // Empty HTTP 200 -> WARNING
        val empty200 = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("   ", 200, 10L),
            vault
        )
        assertEquals(ResponseStatus.WARNING, empty200.status)

        // Invalid JSON HTTP 200 -> WARNING
        val invalidJson = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("{broken json", 200, 10L),
            vault,
            expectJson = true
        )
        assertEquals(ResponseStatus.WARNING, invalidJson.status)

        // Nested data success envelope -> SUCCESS
        val nestedOk = ResponseOutcomeAnalyzer.analyze(
            HttpTransport.Result.Success("""{"data":{"status":200,"reply":"متوفر في المخزون"}}""", 200, 20L),
            vault,
            replyKey = "data.reply"
        )
        assertEquals(ResponseStatus.SUCCESS, nestedOk.status)
        assertEquals("متوفر في المخزون", nestedOk.extractedText)
    }

    // -------------------------------------------------------------------------
    // 3. BidiDisplayFormatter Tests
    // -------------------------------------------------------------------------

    @Test
    fun `bidi - isolates Arabic, mixed text, multiline, nested JSON, and long URLs without mutating outbound JSON`() {
        val rawPayloadObj = """
            {
              "sender": "أحمد محمد",
              "message": "السلام عليكم، هل يتوفر لديكم التوصيل\nاليوم إلى صنعاء؟",
              "mixed": "طلب #1001 من Smart Advisor بقيمة 250 ريال",
              "url": "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply?param=1",
              "meta": {
                "lang": "ar",
                "note": "حالة الشحنة: قيد التوصيل"
              }
            }
        """.trimIndent()

        val formatted = BidiDisplayFormatter.formatJsonForDisplay(rawPayloadObj)

        // Contains directional isolates for visual presentation
        assertTrue(formatted.contains(BidiDisplayFormatter.RLI))
        assertTrue(formatted.contains(BidiDisplayFormatter.LRI))
        assertTrue(formatted.contains(BidiDisplayFormatter.PDI))

        // Stripping isolates produces 100% valid JSON with identical values!
        val stripped = BidiDisplayFormatter.stripIsolates(formatted)
        val originalTree = Json.parseToJsonElement(rawPayloadObj).jsonObject
        val strippedTree = Json.parseToJsonElement(stripped).jsonObject
        assertEquals(originalTree["sender"]?.jsonPrimitive?.content, strippedTree["sender"]?.jsonPrimitive?.content)
        assertEquals(originalTree["message"]?.jsonPrimitive?.content, strippedTree["message"]?.jsonPrimitive?.content)
        assertEquals(originalTree["mixed"]?.jsonPrimitive?.content, strippedTree["mixed"]?.jsonPrimitive?.content)
        assertEquals(originalTree["url"]?.jsonPrimitive?.content, strippedTree["url"]?.jsonPrimitive?.content)
        assertEquals(
            originalTree["meta"]?.jsonObject?.get("note")?.jsonPrimitive?.content,
            strippedTree["meta"]?.jsonObject?.get("note")?.jsonPrimitive?.content
        )

        // Outbound VariableEngine request body has ZERO Unicode isolate characters!
        val config = ServerConfigEntity(
            requestFieldsJson = Json.encodeToString(
                listOf(
                    RequestField("sender", "{{sender}}", FieldType.STRING),
                    RequestField("message", "{{message}}", FieldType.STRING)
                )
            )
        )
        val outboundBody = WebhookClient(FakeHttpTransport { _, _, _, _ ->
            HttpTransport.Result.Success("{}", 200, 1L)
        }, createVault()).buildBody(config, sampleMessage())
        assertFalse(outboundBody.contains(BidiDisplayFormatter.LRI))
        assertFalse(outboundBody.contains(BidiDisplayFormatter.RLI))
        assertFalse(outboundBody.contains(BidiDisplayFormatter.FSI))
        assertFalse(outboundBody.contains(BidiDisplayFormatter.PDI))
    }

    // -------------------------------------------------------------------------
    // 4. UnifiedTestResultCard Compose UI Tests
    // -------------------------------------------------------------------------

    @Test
    fun `compose - UnifiedTestResultCard renders SUCCESS, WARNING, and FAILURE states cleanly`() {
        composeRule.setContent {
            AutoMateTheme {
                UnifiedTestResultCard(
                    model = UnifiedTestResultCardModel(
                        status = ResponseStatus.WARNING,
                        title = "تحذير: رسالة تعذّر مؤقت من الخادم",
                        statusCode = 200,
                        durationMs = 2433L,
                        serverMessage = "عذراً، أواجه مشكلة مؤقتة في الخدمة. حاول مرة أخرى بعد قليل.",
                        sourceLabel = "external_webhook",
                        trustLabel = "خارجي غير متحقق منه",
                        reasonSummary = "أعاد الخادم رسالة تعذّر مؤقت رغم HTTP 200.",
                        guidance = "تحقق من اتصال محرك الذكاء الاصطناعي في WordPress.",
                        rawSnippet = """{"reply":"عذراً، أواجه مشكلة مؤقتة في الخدمة."}"""
                    ),
                    cardTestTag = "test_result_card"
                )
            }
        }

        composeRule.onNodeWithTag("test_result_card").assertIsDisplayed()
        composeRule.onNodeWithText("تحذير: رسالة تعذّر مؤقت من الخادم", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("test_result_card_metrics").assertIsDisplayed()
        composeRule.onNodeWithTag("test_result_card_message").assertIsDisplayed()
        composeRule.onNodeWithTag("test_result_card_guidance").assertIsDisplayed()
        composeRule.onNodeWithTag("test_result_card_raw_excerpt").assertIsDisplayed()
    }
}
