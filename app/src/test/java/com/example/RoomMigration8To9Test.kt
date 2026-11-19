package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
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
class RoomMigration8To9Test {

    @Test
    fun room_migration_8_to_9_preserves_server_config_and_adds_webhook_tool_columns() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val schemaV8File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/8.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/8.json")
        ).firstOrNull { it.exists() }
        assertNotNull("8.json schema file must exist", schemaV8File)

        val schemaV9File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/9.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/9.json")
        ).firstOrNull { it.exists() }
        assertNotNull("9.json schema file must exist", schemaV9File)

        val rootV8 = Json.parseToJsonElement(schemaV8File!!.readText()).jsonObject
        val databaseV8 = rootV8["database"]!!.jsonObject
        val entitiesV8 = databaseV8["entities"]!!.jsonArray
        val setupQueriesV8 = databaseV8["setupQueries"]!!.jsonArray

        val dbFile = File(context.cacheDir, "migration_8_to_9_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        val v8Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (entity in entitiesV8) {
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
                        for (q in setupQueriesV8) {
                            db.execSQL(q.jsonPrimitive.content)
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        v8Helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO server_config (id, isEnabled, url, method, contentType, headersJson, requestFieldsJson, responseReplyKey, timeoutSeconds, retryCount, sendOnGroup, sendOnPrivate, updatedAt)
                VALUES (1, 1, 'https://erp.example.com/hook', 'POST', 'JSON', '[]', '[]', 'reply', 30, 3, 'KEYWORD_ONLY', 1, 1700000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO reply_rules (id, keyword, replyText, matchType, isEnabled, priority, hitCount, createdAt, replyType, imageUri, templateId)
                VALUES (9, 'شحن', 'شحن سريع', 'CONTAINS', 1, 5, 2, 1700000000, 'TEXT', NULL, NULL)
                """.trimIndent()
            )
            close()
        }
        v8Helper.close()

        val migratedDb = Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(AutoMateDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        // Existing server_config preserved with safe backward-compatible defaults
        val migratedConfig = migratedDb.serverConfigDao().get()
        assertNotNull(migratedConfig)
        assertTrue(migratedConfig!!.isEnabled)
        assertEquals("https://erp.example.com/hook", migratedConfig.url)
        assertEquals(30, migratedConfig.timeoutSeconds)
        assertEquals(3, migratedConfig.retryCount)
        assertEquals(WebhookMode.STANDALONE_FALLBACK.name, migratedConfig.webhookMode)
        assertEquals(WebhookMode.STANDALONE_FALLBACK, migratedConfig.effectiveWebhookMode())
        assertEquals("", migratedConfig.toolDescription)
        assertEquals(ServerConfigEntity.DEFAULT_TOOL_MAX_CHARS, migratedConfig.toolMaxResponseChars)

        // Existing rule data also intact
        val existingRule = migratedDb.replyRuleDao().getAllRulesOnce().firstOrNull { it.id == 9L }
        assertNotNull(existingRule)
        assertEquals("شحن", existingRule?.keyword)

        // Updating to AI_TOOL mode with custom description persists cleanly in v9
        migratedDb.serverConfigDao().upsert(
            migratedConfig.copy(
                webhookMode = WebhookMode.AI_TOOL.name,
                toolDescription = "الاستعلام عن حالة الشحنات والفواتير في ERP",
                toolMaxResponseChars = 1500
            )
        )
        val updatedConfig = migratedDb.serverConfigDao().get()
        assertEquals(WebhookMode.AI_TOOL, updatedConfig?.effectiveWebhookMode())
        assertEquals("الاستعلام عن حالة الشحنات والفواتير في ERP", updatedConfig?.toolDescription)
        assertEquals(1500, updatedConfig?.toolMaxResponseChars)

        migratedDb.close()
        dbFile.delete()
    }
}
