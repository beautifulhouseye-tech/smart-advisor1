package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class ContactFilterListType(val labelAr: String) {
    INCLUDE("قائمة السماح (محدد)"),
    EXCLUDE("قائمة الاستثناء")
}

@Entity(tableName = "contact_filters")
data class ContactFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactName: String,
    val listType: ContactFilterListType,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
