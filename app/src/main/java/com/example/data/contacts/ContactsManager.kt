package com.example.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsManager internal constructor(
    private val context: Context,
    private val resolverProvider: () -> ContentResolver,
    private val permissionChecker: () -> Boolean
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        resolverProvider = { context.contentResolver },
        permissionChecker = {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        }
    )

    data class Contact(
        val name: String,
        val phone: String,
        val phoneHash: String = sha256(phone)
    )

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    fun hasPermission(): Boolean = permissionChecker()

    fun getAllContacts(): List<Contact> {
        if (!hasPermission()) {
            _contacts.value = emptyList()
            return emptyList()
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val collected = linkedMapOf<String, Contact>()
        runCatching {
            resolverProvider().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex)?.trim().orEmpty() else ""
                    val phone = if (phoneIndex >= 0) cursor.getString(phoneIndex)?.trim().orEmpty() else ""
                    if (name.isEmpty()) continue
                    val hash = sha256(phone)
                    val uniqueKey = "$name|$hash"
                    if (!collected.containsKey(uniqueKey)) {
                        collected[uniqueKey] = Contact(
                            name = name,
                            phone = phone,
                            phoneHash = hash
                        )
                    }
                }
            }
        }

        val list = collected.values.toList()
        _contacts.value = list
        return list
    }

    fun searchContacts(query: String): List<Contact> {
        val current = _contacts.value.ifEmpty { getAllContacts() }
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return current
        val normalizedQuery = cleanQuery.replace(Regex("""[\s\-()]"""), "")
        return current.filter { contact ->
            contact.name.contains(cleanQuery, ignoreCase = true) ||
                contact.phone.contains(cleanQuery, ignoreCase = true) ||
                (normalizedQuery.isNotEmpty() &&
                    contact.phone.replace(Regex("""[\s\-()]"""), "").contains(normalizedQuery, ignoreCase = true))
        }
    }

    companion object {
        fun sha256(input: String): String {
            val normalized = input.trim().replace(Regex("""[\s\-()]"""), "")
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
