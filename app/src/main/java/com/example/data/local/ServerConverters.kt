package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.local.entity.GroupReplyMode
import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.MenuOption
import com.example.data.local.entity.RequestField
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServerConverters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromHeaderList(value: List<HeaderItem>): String = json.encodeToString(value)

    @TypeConverter
    fun toHeaderList(value: String): List<HeaderItem> =
        runCatching { json.decodeFromString<List<HeaderItem>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromRequestFieldList(value: List<RequestField>): String = json.encodeToString(value)

    @TypeConverter
    fun toRequestFieldList(value: String): List<RequestField> =
        runCatching { json.decodeFromString<List<RequestField>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromMenuOptionList(value: List<MenuOption>): String = json.encodeToString(value)

    @TypeConverter
    fun toMenuOptionList(value: String): List<MenuOption> =
        runCatching { json.decodeFromString<List<MenuOption>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromGroupReplyMode(value: GroupReplyMode): String = value.name

    @TypeConverter
    fun toGroupReplyMode(value: String): GroupReplyMode =
        runCatching { GroupReplyMode.valueOf(value) }.getOrDefault(GroupReplyMode.KEYWORD_ONLY)
}
