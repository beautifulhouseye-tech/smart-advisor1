package com.example

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AutoMateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AutoMateMigrationTest {

    private val testDbName = "automate_migration_test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AutoMateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate1to2_preservesOldData() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                """
                INSERT INTO reply_rules (id, keyword, replyText, matchType, isEnabled, priority, hitCount, createdAt)
                VALUES (10, 'hello', 'hi there', 'CONTAINS', 1, 5, 2, 1000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            2,
            true,
            AutoMateDatabase.MIGRATION_1_2
        )

        db.query("SELECT keyword, replyText FROM reply_rules WHERE id = 10").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertEquals("hi there", cursor.getString(1))
        }
        db.query("SELECT * FROM server_config").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate2to3_preservesOldData() {
        helper.createDatabase(testDbName, 2).apply {
            execSQL(
                """
                INSERT INTO server_config (id, isEnabled, url, method, contentType, headersJson, requestFieldsJson, responseReplyKey, timeoutSeconds, retryCount, sendOnGroup, sendOnPrivate, updatedAt)
                VALUES (1, 1, 'https://api.example.com/hook', 'POST', 'JSON', '[]', '[]', 'reply', 30, 2, 'KEYWORD_ONLY', 1, 2000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            3,
            true,
            AutoMateDatabase.MIGRATION_2_3
        )

        db.query("SELECT url, isEnabled FROM server_config WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("https://api.example.com/hook", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        db.query("SELECT * FROM menu_replies").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate3to4_preservesOldData() {
        helper.createDatabase(testDbName, 3).apply {
            execSQL(
                """
                INSERT INTO reply_rules (id, keyword, replyText, matchType, isEnabled, priority, hitCount, createdAt)
                VALUES (20, 'price', '100 SAR', 'EXACT', 1, 8, 0, 3000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO menu_replies (id, trigger, title, optionsJson, enabled, createdAt)
                VALUES (1, 'menu', 'Main Menu', '[]', 1, 3000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            4,
            true,
            AutoMateDatabase.MIGRATION_3_4
        )

        db.query("SELECT keyword FROM reply_rules WHERE id = 20").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("price", cursor.getString(0))
        }
        db.query("SELECT trigger FROM menu_replies WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("menu", cursor.getString(0))
        }
        db.query("SELECT * FROM follow_ups").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.query("SELECT * FROM dedup_keys").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate4to5_preservesOldData() {
        helper.createDatabase(testDbName, 4).apply {
            execSQL(
                """
                INSERT INTO reply_rules (id, keyword, replyText, matchType, isEnabled, priority, hitCount, createdAt)
                VALUES (30, 'offer', '20% off', 'CONTAINS', 1, 5, 1, 4000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO reply_logs (id, senderName, packageName, incomingMessage, sentReply, isGroup, deliveryMethod, timestamp)
                VALUES (5, 'Ali', 'com.whatsapp', 'hi', 'hello', 0, 'DirectReply', 4000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            5,
            true,
            AutoMateDatabase.MIGRATION_4_5
        )

        db.query("SELECT keyword, replyType FROM reply_rules WHERE id = 30").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("offer", cursor.getString(0))
            assertEquals("TEXT", cursor.getString(1))
        }
        db.query("SELECT senderName, retryCount FROM reply_logs WHERE id = 5").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Ali", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate5to6_preservesOldData() {
        helper.createDatabase(testDbName, 5).apply {
            execSQL(
                """
                INSERT INTO dedup_keys (`key`, createdAt)
                VALUES ('legacy-key-1', 5000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO reply_logs (id, senderName, packageName, incomingMessage, sentReply, isGroup, deliveryMethod, timestamp, retryCount)
                VALUES (9, 'Omar', 'com.whatsapp', 'test', 'ok', 0, 'DirectReply', 5000, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            6,
            true,
            AutoMateDatabase.MIGRATION_5_6
        )

        db.query("SELECT `key`, state, leaseUntil, sendState, attempts, updatedAt FROM dedup_keys WHERE `key` = 'legacy-key-1'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("legacy-key-1", cursor.getString(0))
            assertEquals("NEW", cursor.getString(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals("NONE", cursor.getString(3))
            assertEquals(0, cursor.getInt(4))
            assertEquals(0L, cursor.getLong(5))
        }
        db.query("SELECT senderName, msgKey FROM reply_logs WHERE id = 9").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Omar", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
        db.close()
    }
}
