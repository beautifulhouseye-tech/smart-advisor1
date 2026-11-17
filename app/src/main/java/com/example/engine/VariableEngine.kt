package com.example.engine

import com.example.data.local.entity.FieldType
import com.example.data.local.entity.RequestField
import com.example.data.local.entity.ServerConfigEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class IncomingMessage(
    val app: String,
    val sender: String,
    val senderName: String,
    val message: String,
    val phone: String,
    val groupName: String,
    val isGroup: Boolean,
    val notificationId: String,
    val timestamp: Long = System.currentTimeMillis()
)

object VariableEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun resolveTemplate(template: String, msg: IncomingMessage): String {
        if (template.isEmpty()) return template
        var resolved = template
        val replacements = mapOf(
            "{{message}}" to msg.message,
            "{{sender}}" to msg.sender,
            "{{phone}}" to msg.phone,
            "{{app}}" to msg.app,
            "{{group_name}}" to msg.groupName,
            "{{is_group}}" to msg.isGroup.toString(),
            "{{timestamp}}" to msg.timestamp.toString(),
            "{{sender_name}}" to msg.senderName,
            "{{notification_id}}" to msg.notificationId
        )
        for ((token, value) in replacements) {
            if (resolved.contains(token)) {
                resolved = resolved.replace(token, value)
            }
        }
        return resolved
    }

    fun buildRequestBody(config: ServerConfigEntity, msg: IncomingMessage): String {
        val fields = decodeFields(config.requestFieldsJson)
            .filter { it.enabled && it.key.isNotBlank() }

        return when (config.contentType.trim().uppercase()) {
            "FORM" -> buildFormBody(fields, msg)
            "TEXT" -> buildTextBody(fields, msg)
            else -> buildJsonBody(fields, msg)
        }
    }

    fun extractReply(responseBody: String, replyKey: String): String? {
        val cleanBody = responseBody.trim()
        val cleanKey = replyKey.trim()
        if (cleanBody.isEmpty() || cleanKey.isEmpty()) return null

        val root: JsonElement = try {
            json.parseToJsonElement(cleanBody)
        } catch (_: Exception) {
            return null
        }

        val current = navigatePath(root, cleanKey) ?: return null
        return when (current) {
            is JsonNull -> null
            is JsonPrimitive -> current.content.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun decodeFields(rawJson: String): List<RequestField> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<RequestField>>(rawJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildJsonBody(fields: List<RequestField>, msg: IncomingMessage): String {
        val jsonObj = buildJsonObject {
            for (field in fields) {
                val resolved = resolveTemplate(field.valueTemplate, msg)
                put(field.key.trim(), toTypedJsonElement(resolved, field.type))
            }
        }
        return jsonObj.toString()
    }

    private fun buildFormBody(fields: List<RequestField>, msg: IncomingMessage): String {
        return fields.joinToString("&") { field ->
            val key = URLEncoder.encode(field.key.trim(), StandardCharsets.UTF_8.name())
            val value = URLEncoder.encode(
                resolveTemplate(field.valueTemplate, msg),
                StandardCharsets.UTF_8.name()
            )
            "$key=$value"
        }
    }

    private fun buildTextBody(fields: List<RequestField>, msg: IncomingMessage): String {
        if (fields.isEmpty()) return msg.message
        if (fields.size == 1) {
            return resolveTemplate(fields.first().valueTemplate, msg)
        }
        return fields.joinToString("\n") { field ->
            val resolved = resolveTemplate(field.valueTemplate, msg)
            "${field.key.trim()}: $resolved"
        }
    }

    private fun toTypedJsonElement(resolved: String, type: FieldType): JsonElement {
        return when (type) {
            FieldType.STRING -> JsonPrimitive(resolved)
            FieldType.NUMBER -> {
                val trimmed = resolved.trim()
                trimmed.toLongOrNull()?.let(::JsonPrimitive)
                    ?: trimmed.toDoubleOrNull()?.let(::JsonPrimitive)
                    ?: JsonPrimitive(0)
            }
            FieldType.BOOLEAN -> {
                val boolValue = resolved.trim().equals("true", ignoreCase = true) ||
                    resolved.trim() == "1"
                JsonPrimitive(boolValue)
            }
            FieldType.ARRAY -> {
                try {
                    val parsed = json.parseToJsonElement(resolved)
                    parsed as? JsonArray ?: JsonArray(emptyList())
                } catch (_: Exception) {
                    JsonArray(emptyList())
                }
            }
            FieldType.OBJECT -> {
                try {
                    val parsed = json.parseToJsonElement(resolved)
                    parsed as? JsonObject ?: JsonObject(emptyMap())
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
            }
        }
    }

    private fun navigatePath(root: JsonElement, path: String): JsonElement? {
        var current: JsonElement = root
        val segments = path.split('.').filter { it.isNotEmpty() }

        for (segment in segments) {
            val bracketStart = segment.indexOf('[')
            if (bracketStart == -1) {
                val obj = current as? JsonObject ?: return null
                current = obj[segment] ?: return null
            } else {
                val keyPart = segment.substring(0, bracketStart)
                if (keyPart.isNotEmpty()) {
                    val obj = current as? JsonObject ?: return null
                    current = obj[keyPart] ?: return null
                }
                val indexMatches = BRACKET_INDEX_REGEX.findAll(segment.substring(bracketStart))
                var matchedAny = false
                for (match in indexMatches) {
                    matchedAny = true
                    val index = match.groupValues[1].toIntOrNull() ?: return null
                    val array = current as? JsonArray ?: return null
                    if (index !in array.indices) return null
                    current = array[index]
                }
                if (!matchedAny) return null
            }
        }
        return current
    }

    private val BRACKET_INDEX_REGEX = Regex("""\[(\d+)\]""")
}
