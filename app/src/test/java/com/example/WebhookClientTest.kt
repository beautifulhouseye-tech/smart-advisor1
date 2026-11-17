package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.FieldType
import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.RequestField
import com.example.data.local.entity.ServerConfigEntity
import com.example.engine.IncomingMessage
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.security.SecretVault
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebhookClientTest {

    private class RecordingHttpTransport : HttpTransport() {
        var lastUrl: String? = null
        var lastMethod: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String? = null
        var lastContentType: String? = null
        var lastMaxAttempts: Int? = null
        var lastTimeoutSeconds: Int? = null
        var lastIdempotencyKey: String? = null

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
            lastContentType = contentType
            lastMaxAttempts = maxAttempts
            lastTimeoutSeconds = timeoutSeconds
            lastIdempotencyKey = idempotencyKey
            return Result.Success(
                body = """{"reply":"تم الاستلام"}""",
                code = 200,
                ms = 42L
            )
        }
    }

    private fun createTestVault(): SecretVault {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("webhook_test_vault_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SecretVault(context, prefs)
    }

    private val incomingMessage = IncomingMessage(
        app = "com.whatsapp",
        sender = "سارة",
        senderName = "سارة خالد",
        message = "أريد تفاصيل الاشتراك",
        phone = "+966550001122",
        groupName = "",
        isGroup = false,
        notificationId = "key-77",
        timestamp = 1_700_000_123_000L
    )

    @Test
    fun `send resolves secret headers from vault, builds JSON body via VariableEngine, and executes POST`() = runTest {
        val vault = createTestVault()
        vault.put("header_auth_token", "Bearer super-secret-token")

        val transport = RecordingHttpTransport()
        val client = WebhookClient(transport, vault)

        val headers = listOf(
            HeaderItem(key = "Authorization", valueRef = "header_auth_token", isSecret = true),
            HeaderItem(key = "X-Client", valueRef = "AutoMate-Android", isSecret = false)
        )
        val fields = listOf(
            RequestField(key = "msg", valueTemplate = "{{message}}", type = FieldType.STRING),
            RequestField(key = "from", valueTemplate = "{{phone}}", type = FieldType.STRING),
            RequestField(key = "group", valueTemplate = "{{is_group}}", type = FieldType.BOOLEAN)
        )

        val config = ServerConfigEntity(
            isEnabled = true,
            url = "https://api.example.com/webhook",
            method = "POST",
            contentType = "JSON",
            headersJson = Json.encodeToString(headers),
            requestFieldsJson = Json.encodeToString(fields),
            timeoutSeconds = 20,
            retryCount = 3
        )

        val result = client.send(config, incomingMessage)

        assertTrue(result is HttpTransport.Result.Success)
        assertEquals("https://api.example.com/webhook", transport.lastUrl)
        assertEquals("POST", transport.lastMethod)
        assertEquals("Bearer super-secret-token", transport.lastHeaders["Authorization"])
        assertEquals("AutoMate-Android", transport.lastHeaders["X-Client"])
        assertEquals("JSON", transport.lastContentType)
        assertEquals(3, transport.lastMaxAttempts)
        assertEquals(20, transport.lastTimeoutSeconds)

        val bodyJson = Json.parseToJsonElement(transport.lastBody!!).jsonObject
        assertEquals("أريد تفاصيل الاشتراك", bodyJson["msg"]?.jsonPrimitive?.content)
        assertEquals("+966550001122", bodyJson["from"]?.jsonPrimitive?.content)
        assertEquals("false", bodyJson["group"]?.jsonPrimitive?.content)
    }

    @Test
    fun `send omits request body when method is GET`() = runTest {
        val vault = createTestVault()
        val transport = RecordingHttpTransport()
        val client = WebhookClient(transport, vault)

        val config = ServerConfigEntity(
            url = "https://api.example.com/poll",
            method = "GET"
        )

        client.send(config, incomingMessage)

        assertEquals("GET", transport.lastMethod)
        assertNull(transport.lastBody)
    }
}
