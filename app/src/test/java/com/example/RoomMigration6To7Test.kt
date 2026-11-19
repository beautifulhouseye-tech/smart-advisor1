package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ConversationContextEntity
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.MemoryOutboxEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigration6To7Test {

    @Test
    fun room_migration_6_to_7_preserves_existing_data_and_validates_schema() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val schemaV6File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/6.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/6.json")
        ).firstOrNull { it.exists() }
        assertNotNull("6.json schema file must exist", schemaV6File)

        val schemaV7File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/7.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/7.json")
        ).firstOrNull { it.exists() }
        assertNotNull("7.json schema file must exist", schemaV7File)

        val rootV6 = Json.parseToJsonElement(schemaV6File!!.readText()).jsonObject
        val databaseV6 = rootV6["database"]!!.jsonObject
        val entitiesV6 = databaseV6["entities"]!!.jsonArray
        val setupQueriesV6 = databaseV6["setupQueries"]!!.jsonArray

        val dbFile = File(context.cacheDir, "migration_6_to_7_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        val v6Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (entity in entitiesV6) {
                            val obj = entity.jsonObject
                            val tableName = obj["tableName"]!!.jsonPrimitive.content
                            val createSql = obj["createSql"]!!.jsonPrimitive.content
                                .replace("\${TABLE_NAME}", tableName)
                            db.execSQL(createSql)
                            obj["indices"]?.jsonArray?.forEach { idx ->
                                val idxSql = idx.jsonObject["createSql"]!!.jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", tableName)
                                db.execSQL(idxSql)
                            }
                        }
                        for (q in setupQueriesV6) {
                            db.execSQL(q.jsonPrimitive.content)
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        v6Helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO reply_rules (id, keyword, replyText, matchType, isEnabled, priority, hitCount, createdAt, replyType, imageUri)
                VALUES (11, 'سعر', 'السعر 50 ريال', 'CONTAINS', 1, 10, 3, 100000, 'TEXT', NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO reply_logs (id, senderName, packageName, incomingMessage, sentReply, isGroup, deliveryMethod, timestamp, retryCount, msgKey)
                VALUES (42, 'أحمد', 'com.whatsapp', 'مرحبا', 'أهلاً بك', 0, 'DirectReply', 1700000000, 0, 'msg-key-42')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO dedup_keys (`key`, createdAt, state, leaseUntil, sendState, attempts, updatedAt)
                VALUES ('msg-key-42', 1700000000, 'COMPLETED', 0, 'SENT', 1, 1700000005)
                """.trimIndent()
            )
            close()
        }
        v6Helper.close()

        // Migrate v6 -> v7 and let Room validate compiled v7 schema identity hash & tables
        val migratedDb = Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(
                AutoMateDatabase.MIGRATION_6_7,
                AutoMateDatabase.MIGRATION_7_8,
                AutoMateDatabase.MIGRATION_8_9
            )
            .allowMainThreadQueries()
            .build()

        // Verify v6 data preserved + new reply_logs columns defaulted cleanly
        val rule = migratedDb.replyRuleDao().getAllRulesOnce().firstOrNull { it.id == 11L }
        assertNotNull(rule)
        assertEquals("سعر", rule?.keyword)

        val log = migratedDb.replyLogDao().getById(42L)
        assertNotNull(log)
        assertEquals("أحمد", log?.senderName)
        assertEquals("msg-key-42", log?.msgKey)
        assertEquals("", log?.conversationKey)
        assertEquals("", log?.sourceCategory)
        assertEquals("", log?.matchedRuleKeyword)

        val dedup = migratedDb.dedupKeyDao().getByKey("msg-key-42")
        assertNotNull(dedup)
        assertEquals(DedupKeyEntity.STATE_COMPLETED, dedup?.state)
        assertEquals(DedupKeyEntity.SEND_SENT, dedup?.sendState)

        // Verify new tables work cleanly after migration
        val conv = ConversationContextEntity(
            conversationKey = "com.whatsapp|dm|أحمد",
            packageName = "com.whatsapp",
            isGroup = false,
            displayName = "أحمد",
            phone = "+966500000001",
            customerKey = "cust_ahmed",
            turnsJson = "[]",
            updatedAt = 1700000010L
        )
        migratedDb.conversationContextDao().upsert(conv)
        assertEquals("cust_ahmed", migratedDb.conversationContextDao().getByKey("com.whatsapp|dm|أحمد")?.customerKey)

        val outbox = MemoryOutboxEntity(
            eventId = "mem_01",
            conversationKey = "com.whatsapp|dm|أحمد",
            customerKey = "cust_ahmed",
            encryptedPayload = "ENC_BLOB",
            revision = 1L,
            createdAt = 1700000010L,
            updatedAt = 1700000010L
        )
        assertTrue(migratedDb.memoryOutboxDao().insertIgnore(outbox) > 0L)
        assertEquals(1, migratedDb.memoryOutboxDao().getPending().size)

        migratedDb.close()
        dbFile.delete()
    }
}
