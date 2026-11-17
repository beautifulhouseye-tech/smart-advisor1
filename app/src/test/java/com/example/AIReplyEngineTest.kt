package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.network.HttpTransport
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AIReplyEngineTest {

    private class FakeTransport(
        private val responder: (url: String, headers: Map<String, String>, body: String?) -> HttpTransport.Result
    ) : HttpTransport() {
        val calls = mutableListOf<String>()

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
            calls.add(url)
            return responder(url, headers, body)
        }
    }

    private fun testVault(): SecretVault {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("ai_test_vault", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SecretVault(context, prefs)
    }

    @Test
    fun `callOpenAI returns parsed assistant message`() = runTest {
        val transport = FakeTransport { url, headers, _ ->
            if (url.contains("api.openai.com") && headers["Authorization"] == "Bearer sk-test") {
                HttpTransport.Result.Success(
                    body = """{"choices":[{"message":{"content":"أهلاً بك! السعر 100 ريال."}}]}""",
                    code = 200,
                    ms = 40L
                )
            } else {
                HttpTransport.Result.Failure("Unauthorized", 401)
            }
        }
        val engine = AIReplyEngine(transport, testVault())

        val reply = engine.generateReply(
            message = "بكم السعر؟",
            config = AIReplyEngine.AIConfig(
                provider = AIReplyEngine.Provider.OPENAI,
                apiKey = "sk-test",
                model = "gpt-3.5-turbo",
                systemPrompt = "كن مختصراً"
            )
        )

        assertEquals("أهلاً بك! السعر 100 ريال.", reply)
    }

    @Test
    fun `callGemini returns parsed candidate text`() = runTest {
        val transport = FakeTransport { url, _, _ ->
            if (url.contains("generativelanguage.googleapis.com") && url.contains("key=gem-key")) {
                HttpTransport.Result.Success(
                    body = """{"candidates":[{"content":{"parts":[{"text":"مرحباً! كيف أخدمك اليوم؟"}]}}]}""",
                    code = 200,
                    ms = 35L
                )
            } else {
                HttpTransport.Result.Failure("Bad Request", 400)
            }
        }
        val engine = AIReplyEngine(transport, testVault())

        val reply = engine.generateReply(
            message = "مرحبا",
            config = AIReplyEngine.AIConfig(
                provider = AIReplyEngine.Provider.GEMINI,
                apiKey = "gem-key",
                model = "gemini-1.5-pro",
                systemPrompt = "مساعد مبيعات"
            )
        )

        assertEquals("مرحباً! كيف أخدمك اليوم؟", reply)
    }

    @Test
    fun `ollama returns null as loopback is unavailable`() = runTest {
        val transport = FakeTransport { _, _, _ ->
            HttpTransport.Result.Success("{}", 200, 1L)
        }
        val engine = AIReplyEngine(transport, testVault())

        val reply = engine.generateReply(
            message = "مرحبا",
            config = AIReplyEngine.AIConfig(
                provider = AIReplyEngine.Provider.OLLAMA,
                apiKey = "",
                model = "llama3",
                systemPrompt = ""
            )
        )

        assertNull(reply)
    }

    @Test
    fun `fallback switches from failed primary provider to secondary provider`() = runTest {
        val vault = testVault()
        vault.put("ai_api_key_gemini", "gem-backup-key")

        val transport = FakeTransport { url, _, _ ->
            if (url.contains("api.openai.com")) {
                HttpTransport.Result.Failure("Server Error", 500)
            } else {
                HttpTransport.Result.Success(
                    body = """{"candidates":[{"content":{"parts":[{"text":"رد بديل من Gemini"}]}}]}""",
                    code = 200,
                    ms = 25L
                )
            }
        }
        val engine = AIReplyEngine(transport, vault)

        val reply = engine.generateReplyWithFallback(
            message = "هل هناك خصم؟",
            primaryConfig = AIReplyEngine.AIConfig(
                provider = AIReplyEngine.Provider.OPENAI,
                apiKey = "sk-primary",
                model = "gpt-4o",
                systemPrompt = ""
            )
        )

        assertEquals("رد بديل من Gemini", reply)
        assertEquals(2, transport.calls.size)
    }
}
