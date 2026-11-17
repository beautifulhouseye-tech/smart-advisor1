package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.IncomingMessage
import com.example.network.HttpTransport
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartAdvisorClientTest {

    private class RecordingTransport : HttpTransport() {
        var lastUrl: String? = null
        var lastMethod: String? = null
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
            lastUrl = url
            lastMethod = method
            lastHeaders = headers
            lastBody = body
            return Result.Success(
                body = """{"reply":"أهلاً بك في المتجر"}""",
                code = 200,
                ms = 35L
            )
        }
    }

    private fun createVault(): SecretVault {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("sa_client_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SecretVault(context, prefs)
    }

    @Test
    fun `endpoint appends wp-json path when missing and preserves when already present`() {
        val vault = createVault()
        val transport = RecordingTransport()

        val clientWithoutPath = SmartAdvisorClient(
            transport = transport,
            vault = vault,
            baseUrlProvider = { "https://www.777736225.com/" },
            apiKeyRefProvider = { "" }
        )
        assertEquals(
            "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply",
            clientWithoutPath.endpoint()
        )

        val clientWithPath = SmartAdvisorClient(
            transport = transport,
            vault = vault,
            baseUrlProvider = { "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply" },
            apiKeyRefProvider = { "" }
        )
        assertEquals(
            "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply",
            clientWithPath.endpoint()
        )
    }

    @Test
    fun `buildHeaders resolves X-SA-API-Key from SecretVault and returns empty when apiKeyRef is blank`() {
        val vault = createVault()
        vault.put("sa_key_ref", "sa-live-secret-999")
        val transport = RecordingTransport()

        val clientWithKey = SmartAdvisorClient(
            transport = transport,
            vault = vault,
            baseUrlProvider = { "https://www.777736225.com" },
            apiKeyRefProvider = { "sa_key_ref" }
        )
        assertEquals(mapOf("X-SA-API-Key" to "sa-live-secret-999"), clientWithKey.buildHeaders())

        val clientWithoutKey = SmartAdvisorClient(
            transport = transport,
            vault = vault,
            baseUrlProvider = { "https://www.777736225.com" },
            apiKeyRefProvider = { "" }
        )
        assertTrue(clientWithoutKey.buildHeaders().isEmpty())
    }

    @Test
    fun `buildBody produces exact 5-field JSON and test sends marhaba from AutoMate-Test`() = runTest {
        val vault = createVault()
        vault.put("sa_ref", "secret-key-abc")
        val transport = RecordingTransport()
        val client = SmartAdvisorClient(
            transport = transport,
            vault = vault,
            baseUrlProvider = { "https://www.777736225.com" },
            apiKeyRefProvider = { "sa_ref" }
        )

        val msg = IncomingMessage(
            app = "com.whatsapp",
            sender = "خالد",
            senderName = "خالد",
            message = "بكم الشحن؟",
            phone = "+966500001111",
            groupName = "الدعم",
            isGroup = true,
            notificationId = "n-1"
        )
        val builtJson = Json.parseToJsonElement(client.buildBody(msg)).jsonObject
        assertEquals(setOf("app", "sender", "message", "group_name", "phone"), builtJson.keys)
        assertEquals("com.whatsapp", builtJson["app"]?.jsonPrimitive?.content)
        assertEquals("خالد", builtJson["sender"]?.jsonPrimitive?.content)
        assertEquals("بكم الشحن؟", builtJson["message"]?.jsonPrimitive?.content)
        assertEquals("الدعم", builtJson["group_name"]?.jsonPrimitive?.content)
        assertEquals("+966500001111", builtJson["phone"]?.jsonPrimitive?.content)

        val result = client.test()
        assertTrue(result is HttpTransport.Result.Success)
        assertEquals("POST", transport.lastMethod)
        assertEquals(
            "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply",
            transport.lastUrl
        )
        assertEquals("secret-key-abc", transport.lastHeaders["X-SA-API-Key"])

        val sentJson = Json.parseToJsonElement(transport.lastBody!!).jsonObject
        assertEquals("AutoMate-Test", sentJson["sender"]?.jsonPrimitive?.content)
        assertEquals("مرحبا", sentJson["message"]?.jsonPrimitive?.content)
    }
}
