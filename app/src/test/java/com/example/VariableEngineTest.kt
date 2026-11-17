package com.example

import com.example.data.local.entity.FieldType
import com.example.data.local.entity.RequestField
import com.example.data.local.entity.ServerConfigEntity
import com.example.engine.IncomingMessage
import com.example.engine.VariableEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableEngineTest {

    private val sampleMsg = IncomingMessage(
        app = "com.whatsapp",
        sender = "أحمد",
        senderName = "أحمد علي",
        message = "كم السعر؟",
        phone = "+966501234567",
        groupName = "عملاء VIP",
        isGroup = true,
        notificationId = "notif-42",
        timestamp = 1_700_000_000_000L
    )

    @Test
    fun `resolveTemplate replaces all 9 supported placeholders`() {
        val template = listOf(
            "msg={{message}}",
            "sender={{sender}}",
            "phone={{phone}}",
            "app={{app}}",
            "group={{group_name}}",
            "isGroup={{is_group}}",
            "ts={{timestamp}}",
            "name={{sender_name}}",
            "nid={{notification_id}}"
        ).joinToString("|")

        val result = VariableEngine.resolveTemplate(template, sampleMsg)

        assertEquals(
            "msg=كم السعر؟|sender=أحمد|phone=+966501234567|app=com.whatsapp|" +
                "group=عملاء VIP|isGroup=true|ts=1700000000000|name=أحمد علي|nid=notif-42",
            result
        )
    }

    @Test
    fun `buildRequestBody builds typed JSON fields and skips disabled fields`() {
        val fields = listOf(
            RequestField(key = "text", valueTemplate = "{{message}}", type = FieldType.STRING, enabled = true),
            RequestField(key = "timestamp", valueTemplate = "{{timestamp}}", type = FieldType.NUMBER, enabled = true),
            RequestField(key = "isGroup", valueTemplate = "{{is_group}}", type = FieldType.BOOLEAN, enabled = true),
            RequestField(
                key = "tags",
                valueTemplate = """["{{app}}","{{sender}}"]""",
                type = FieldType.ARRAY,
                enabled = true
            ),
            RequestField(
                key = "meta",
                valueTemplate = """{"phone":"{{phone}}","nid":"{{notification_id}}"}""",
                type = FieldType.OBJECT,
                enabled = true
            ),
            RequestField(key = "ignored", valueTemplate = "should_not_exist", type = FieldType.STRING, enabled = false)
        )
        val config = ServerConfigEntity(
            contentType = "JSON",
            requestFieldsJson = Json.encodeToString(fields)
        )

        val body = VariableEngine.buildRequestBody(config, sampleMsg)
        val parsed = Json.parseToJsonElement(body).jsonObject

        assertEquals("كم السعر؟", parsed["text"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000_000L, parsed["timestamp"]?.jsonPrimitive?.content?.toLong())
        assertEquals(true, parsed["isGroup"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("com.whatsapp", parsed["tags"]?.jsonArray?.get(0)?.jsonPrimitive?.content)
        assertEquals("أحمد", parsed["tags"]?.jsonArray?.get(1)?.jsonPrimitive?.content)
        assertEquals("+966501234567", parsed["meta"]?.jsonObject?.get("phone")?.jsonPrimitive?.content)
        assertNull(parsed["ignored"])
    }

    @Test
    fun `buildRequestBody supports FORM content type with URL encoding`() {
        val fields = listOf(
            RequestField(key = "sender", valueTemplate = "{{sender}}", type = FieldType.STRING),
            RequestField(key = "message", valueTemplate = "hello world", type = FieldType.STRING)
        )
        val config = ServerConfigEntity(
            contentType = "FORM",
            requestFieldsJson = Json.encodeToString(fields)
        )

        val body = VariableEngine.buildRequestBody(config, sampleMsg)
        assertTrue(body.contains("sender="))
        assertTrue(body.contains("message=hello+world"))
    }

    @Test
    fun `extractReply handles flat, nested dot paths, array indices, missing keys, and invalid JSON`() {
        assertEquals(
            "مرحباً بك",
            VariableEngine.extractReply("""{"reply":"مرحباً بك"}""", "reply")
        )
        assertEquals(
            "من Nested",
            VariableEngine.extractReply("""{"data":{"reply":"من Nested"}}""", "data.reply")
        )
        assertEquals(
            "من GPT",
            VariableEngine.extractReply(
                """{"choices":[{"text":"من GPT"},{"text":"ثاني"}]}""",
                "choices[0].text"
            )
        )
        assertNull(VariableEngine.extractReply("""{"data":{"other":"x"}}""", "data.reply"))
        assertNull(VariableEngine.extractReply("""{"choices":[]}""", "choices[0].text"))
        assertNull(VariableEngine.extractReply("not valid json", "reply"))
        assertNull(VariableEngine.extractReply("""{"reply":null}""", "reply"))
    }
}
