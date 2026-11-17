package com.example.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class HeaderItem(
    val key: String,
    val valueRef: String,
    val isSecret: Boolean = true
)

@Serializable
data class RequestField(
    val key: String,
    val valueTemplate: String,
    val type: FieldType = FieldType.STRING,
    val enabled: Boolean = true,
    val description: String = ""
)

@Serializable
enum class FieldType {
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT
}

@Serializable
enum class GroupReplyMode {
    ALL,
    MENTION_ONLY,
    KEYWORD_ONLY,
    NEVER
}

@Serializable
data class MenuOption(
    val number: Int,
    val label: String,
    val response: String
)
