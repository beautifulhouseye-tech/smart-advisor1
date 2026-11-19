package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.local.entity.WebhookMode
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
class RoomMigration1To9Test {

    @Test
    fun room_migration_1_through_9_preserves_all_data_and_upgrades_schema_cleanly() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val schemaV1File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/1.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/1.json")
        ).firstOrNull { it.exists() }
        assertNotNull("1.json schema file must exist", schemaV1File)

        val rootV1 = Json.parseToJsonElement(schemaV1File!!.readText()).jsonObject
        val databaseV1 = rootV1["database"]!!.jsonObject
        val entitiesV1 = databaseV1["entities"]!!.jsonArray
        val setupQueriesV1 = databaseV1["setupQueries"]!!.jsonArray

        val dbFile = File(context.cacheDir, "migration_1_to_9_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (entity in entitiesV1) {
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
                        for (q in setupQueriesV1) {
                            db.execSQL(q.jsonPrimitive.content)
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        // Seed genuine v1 records in reply_rules, contact_filters, and reply_logs
        v1Helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO reply_rules (id, keyword, replyText, matchType, isEnabled, priority, hitCount, createdAt)
                VALUES (101, 'مرحبا', 'أهلاً بك في متجرنا', 'CONTAINS', 1, 20, 4, 1700000100)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO contact_filters (id, contactName, listType, notes, createdAt)
                VALUES (11, 'VIP Customer', 'INCLUDE', 'Priority contact', 1700000200)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO reply_logs (id, senderName, packageName, incomingMessage, sentReply, isGroup, deliveryMethod, timestamp)
                VALUES (201, 'سارة', 'com.whatsapp', 'هل يوجد شحن؟', 'نعم متاح', 0, 'DirectReply', 1700000300)
                """.trimIndent()
            )
            close()
        }
        v1Helper.close()

        val migratedDb = Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(
                AutoMateDatabase.MIGRATION_1_2,
                AutoMateDatabase.MIGRATION_2_3,
                AutoMateDatabase.MIGRATION_3_4,
                AutoMateDatabase.MIGRATION_4_5,
                AutoMateDatabase.MIGRATION_5_6,
                AutoMateDatabase.MIGRATION_6_7,
                AutoMateDatabase.MIGRATION_7_8,
                AutoMateDatabase.MIGRATION_8_9
            )
            .allowMainThreadQueries()
            .build()

        // 1. Original v1 records preserved cleanly with new columns defaulted properly
        val migratedRules = migratedDb.replyRuleDao().getAllRulesOnce()
        val rule101 = migratedRules.firstOrNull { it.id == 101L }
        assertNotNull(rule101)
        assertEquals("مرحبا", rule101?.keyword)
        assertEquals("أهلاً بك في متجرنا", rule101?.replyText)
        assertEquals(4, rule101?.hitCount)
        assertEquals(ReplyRuleEntity.REPLY_TYPE_TEXT, rule101?.replyType)

        val filters = migratedDb.contactFilterDao().getFiltersByTypeOnce(ContactFilterListType.INCLUDE)
        assertEquals(1, filters.size)
        assertEquals("VIP Customer", filters.first().contactName)

        val logs = migratedDb.replyLogDao().getLogsSince(0L)
        assertEquals(1, logs.size)
        assertEquals("سارة", logs.first().senderName)
        assertEquals(0, logs.first().retryCount)
        assertEquals("", logs.first().conversationKey)

        // 2. Newer v9 tables (server_config, conversation_contexts, reply_templates) are fully usable
        migratedDb.serverConfigDao().upsert(
            ServerConfigEntity(
                isEnabled = true,
                url = "https://crm.example.com/hook",
                webhookMode = WebhookMode.AI_TOOL.name,
                toolDescription = "بيانات الفواتير"
            )
        )
        val serverConfig = migratedDb.serverConfigDao().get()
        assertNotNull(serverConfig)
        assertEquals(WebhookMode.AI_TOOL, serverConfig?.effectiveWebhookMode())
        assertEquals("بيانات الفواتير", serverConfig?.toolDescription)

        migratedDb.close()
        dbFile.delete()
    }
}
