package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.MatchType

class AutoMateTypeConverters {
    @TypeConverter
    fun fromMatchType(value: MatchType): String = value.name

    @TypeConverter
    fun toMatchType(value: String): MatchType =
        runCatching { MatchType.valueOf(value) }.getOrDefault(MatchType.CONTAINS)

    @TypeConverter
    fun fromContactFilterListType(value: ContactFilterListType): String = value.name

    @TypeConverter
    fun toContactFilterListType(value: String): ContactFilterListType =
        runCatching { ContactFilterListType.valueOf(value) }.getOrDefault(ContactFilterListType.EXCLUDE)
}
