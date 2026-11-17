package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.example.data.contacts.ContactsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactsManagerTest {

    @Test
    fun `sha256 normalizes whitespace dashes and parentheses`() {
        val h1 = ContactsManager.sha256("+966 50-123 (4567)")
        val h2 = ContactsManager.sha256("+966501234567")
        assertEquals(h2, h1)
        assertEquals(64, h1.length)
    }

    @Test
    fun `getAllContacts returns empty when permission denied`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ContactsManager(
            context = context,
            resolverProvider = { context.contentResolver },
            permissionChecker = { false }
        )

        assertFalse(manager.hasPermission())
        assertTrue(manager.getAllContacts().isEmpty())
    }

    @Test
    fun `getAllContacts and searchContacts return hashed contacts when permitted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            FakeContactsProvider()
        )
        val manager = ContactsManager(
            context = context,
            resolverProvider = { context.contentResolver },
            permissionChecker = { true }
        )

        val contacts = manager.getAllContacts()
        assertEquals(2, contacts.size)
        assertEquals("أحمد علي", contacts[0].name)
        assertEquals(ContactsManager.sha256("+966501112233"), contacts[0].phoneHash)

        val byName = manager.searchContacts("سارة")
        assertEquals(1, byName.size)
        assertEquals("سارة محمد", byName[0].name)

        val byPhone = manager.searchContacts("50111")
        assertEquals(1, byPhone.size)
        assertEquals("أحمد علي", byPhone[0].name)
    }

    private class FakeContactsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cursor = MatrixCursor(
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
            )
            cursor.addRow(arrayOf("أحمد علي", "+966 50 111 2233"))
            cursor.addRow(arrayOf("أحمد علي", "+966501112233")) // duplicate normalized
            cursor.addRow(arrayOf("سارة محمد", "0559998877"))
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }
}
