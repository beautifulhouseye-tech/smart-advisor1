package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ReplyTemplateEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigration7To8Test {

    @Test
    fun room_migration_7_to_8_preserves_rules_and_creates_reply_templates_table() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val schemaV7File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/7.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/7.json")
        ).firstOrNull { it.exists() }
        assertNotNull("7.json schema file must exist", schemaV7File)

        val schemaV8File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/8.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/8.json")
        ).firstOrNull { it.exists() }
        assertNotNull("8.json schema file must exist", schemaV8File)

        val rootV7 = Json.parseToJsonElement(schemaV7File!!.readText()).jsonObject
        val databaseV7 = rootV7["database"]!!.jsonObject
        val entitiesV7 = databaseV7["entities"]!!.jsonArray
        val setupQueriesV7 = databaseV7["setupQueries"]!!.jsonArray

        val dbFile = File(context.cacheDir, "migration_7_to_8_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        val v7Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (entity in entitiesV7) {
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
                        for (q in setupQueriesV7) {
                            db.execSQL(q.jsonPrimitive.content)
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        v7Helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO reply_rules (id, keyword, replyText, matchType, isEnabled, priority, hitCount, createdAt, replyType, imageUri)
                VALUES (7, 'تفاصيل', 'تفاصيل العرض المتاح حالياً', 'CONTAINS', 1, 10, 4, 1700000000, 'TEXT', NULL)
                """.trimIndent()
            )
            close()
        }
        v7Helper.close()

        val migratedDb = Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(AutoMateDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()

        val existingRule = migratedDb.replyRuleDao().getAllRulesOnce().firstOrNull { it.id == 7L }
        assertNotNull(existingRule)
        assertEquals("تفاصيل", existingRule?.keyword)
        assertEquals("تفاصيل العرض المتاح حالياً", existingRule?.replyText)
        assertNull(existingRule?.templateId)

        val templateDao = migratedDb.replyTemplateDao()
        val insertedId = templateDao.insertTemplate(
            ReplyTemplateEntity(
                title = "قالب الترحيب",
                category = ReplyTemplateEntity.CATEGORY_WELCOME,
                body = "أهلاً {{sender_name}}",
                createdAt = 1700000100L,
                updatedAt = 1700000100L
            )
        )
        assertTrue(insertedId > 0L)

        templateDao.incrementUsageCount(insertedId)
        val stored = templateDao.getTemplateById(insertedId)
        assertNotNull(stored)
        assertEquals("قالب الترحيب", stored?.title)
        assertEquals(1, stored?.usageCount)

        migratedDb.close()
        dbFile.delete()
    }
}
