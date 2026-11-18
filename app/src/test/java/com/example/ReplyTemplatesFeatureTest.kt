package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ReplyTemplateEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.MenuReplyManager
import com.example.engine.ReplySourceCategory
import com.example.engine.UnifiedReplyTester
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReplyTemplatesFeatureTest {

    private class StubHttpTransport : HttpTransport() {
        override suspend fun execute(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
            contentType: String,
            maxAttempts: Int,
            timeoutSeconds: Int,
            idempotencyKey: String?
        ): Result = Result.Failure("not-used-in-template-tests")
    }

    private lateinit var context: Context
    private lateinit var db: AutoMateDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var repository: AutoMateRepository
    private lateinit var tester: UnifiedReplyTester

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsDataStore = SettingsDataStore(context)

        val json = Json { ignoreUnknownKeys = true }
        repository = AutoMateRepository(
            replyRuleDao = db.replyRuleDao(),
            contactFilterDao = db.contactFilterDao(),
            replyLogDao = db.replyLogDao(),
            settingsDataStore = settingsDataStore,
            backupManager = BackupManager(context, json),
            replyTemplateDao = db.replyTemplateDao()
        )

        val suffix = System.nanoTime()
        val secretVault = SecretVault(
            context,
            context.getSharedPreferences("tpl_vault_$suffix", Context.MODE_PRIVATE)
        )
        val aiSettingsPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("tpl_ai_$suffix", Context.MODE_PRIVATE)
        )
        val smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("tpl_sa_$suffix", Context.MODE_PRIVATE)
        )
        val transport = StubHttpTransport()
        tester = UnifiedReplyTester(
            repository = repository,
            menuReplyManager = MenuReplyManager(db.menuReplyDao(), json),
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = AIReplyEngine(transport, secretVault),
            secretVault = secretVault,
            serverRepo = ServerConfigRepositoryImpl(db.serverConfigDao()),
            webhookClient = WebhookClient(transport, secretVault),
            smartAdvisorClient = SmartAdvisorClient(transport, smartAdvisorPrefs, secretVault),
            smartAdvisorPrefs = smartAdvisorPrefs
        )
    }

    @After
    fun tearDown() = runBlocking {
        tester.resetSession()
        db.close()
    }

    @Test
    fun template_crud_duplication_and_starter_seeding_work() = runTest {
        repository.ensureStarterTemplatesIfEmpty()
        val starters = repository.getAllTemplatesOnce()
        assertEquals(3, starters.size)

        val newId = repository.upsertTemplate(
            ReplyTemplateEntity(
                title = "عرض اليوم",
                category = ReplyTemplateEntity.CATEGORY_PRICING,
                body = "أهلاً {{sender_name}}، العرض ساري اليوم بخصم 20%!"
            )
        )
        assertTrue(newId > 0L)

        val created = repository.getTemplateById(newId)
        assertNotNull(created)
        assertEquals("عرض اليوم", created?.title)

        val copyId = repository.duplicateTemplate(created!!)
        assertTrue(copyId > 0L && copyId != newId)
        val copied = repository.getTemplateById(copyId)
        assertEquals("عرض اليوم (نسخة)", copied?.title)
        assertEquals(0, copied?.usageCount)

        repository.incrementTemplateUsage(newId)
        assertEquals(1, repository.getTemplateById(newId)?.usageCount)

        repository.deleteTemplate(copyId)
        assertNull(repository.getTemplateById(copyId))
    }

    @Test
    fun linked_rule_updates_dynamically_with_template_and_resolves_variables_and_falls_back_on_delete() = runTest {
        val tplId = repository.upsertTemplate(
            ReplyTemplateEntity(
                title = "سياسة الشحن",
                category = ReplyTemplateEntity.CATEGORY_SHIPPING,
                body = "مرحباً {{sender_name}}، بخصوص \"{{message}}\" الشحن يستغرق يومين."
            )
        )
        val rule = ReplyRuleEntity(
            keyword = "شحن",
            replyText = "نص احتياطي قديم",
            matchType = MatchType.CONTAINS,
            isEnabled = true,
            priority = 10,
            templateId = tplId
        )
        repository.upsertRule(rule)

        val firstRes = tester.evaluate(message = "متى يصل الشحن؟", senderName = "سالم")
        assertEquals(ReplySourceCategory.LOCAL_RULE, firstRes.sourceCategory)
        assertEquals(
            "مرحباً سالم، بخصوص \"متى يصل الشحن؟\" الشحن يستغرق يومين.",
            firstRes.replyText
        )

        // Update the template body without touching the rule -> live linked rule reflects the change!
        val storedTpl = repository.getTemplateById(tplId)!!
        repository.upsertTemplate(
            storedTpl.copy(body = "أهلاً {{sender_name}}، الآن الشحن خلال 24 ساعة فقط!")
        )
        val secondRes = tester.evaluate(message = "متى يصل الشحن؟", senderName = "سالم")
        assertEquals("أهلاً سالم، الآن الشحن خلال 24 ساعة فقط!", secondRes.replyText)

        // Delete template -> rule safely falls back to its cached replyText
        repository.deleteTemplate(tplId)
        val fallbackRes = tester.evaluate(message = "متى يصل الشحن؟", senderName = "سالم")
        assertEquals("نص احتياطي قديم", fallbackRes.replyText)
    }

    @Test
    fun backup_v2_exports_and_imports_templates_with_rule_links_and_supports_v1_payloads() = runTest {
        val tplId = repository.upsertTemplate(
            ReplyTemplateEntity(
                title = "قالب الترحيب",
                category = ReplyTemplateEntity.CATEGORY_WELCOME,
                body = "أهلاً بك يا {{sender_name}}!"
            )
        )
        repository.upsertRule(
            ReplyRuleEntity(
                keyword = "اهلا",
                replyText = "نسخة احتياطية للترحيب",
                matchType = MatchType.CONTAINS,
                isEnabled = true,
                priority = 9,
                templateId = tplId
            )
        )

        val exportFile = File(context.cacheDir, "backup_v2_${System.nanoTime()}.json")
        val exportUri = Uri.fromFile(exportFile)
        val exportedCount = repository.exportBackup(exportUri).getOrThrow()
        assertEquals(2, exportedCount) // 1 rule + 1 template

        // Clear and re-import via replaceExisting = true
        val importResult = repository.importBackup(exportUri, replaceExisting = true).getOrThrow()
        assertEquals(1, importResult.first)

        val restoredTemplates = repository.getAllTemplatesOnce()
        assertEquals(1, restoredTemplates.size)
        val restoredTpl = restoredTemplates.first()
        assertEquals("قالب الترحيب", restoredTpl.title)

        val restoredRules = repository.rulesFlow.first()
        assertEquals(1, restoredRules.size)
        assertEquals(restoredTpl.id, restoredRules.first().templateId)

        // Legacy v1 JSON backup (without templates field) imports cleanly
        val v1File = File(context.cacheDir, "backup_v1_${System.nanoTime()}.json")
        v1File.writeText(
            """
            {
              "version": 1,
              "exportedAt": 1700000000,
              "rules": [
                {
                  "keyword": "مساعدة",
                  "replyText": "نحن هنا لمساعدتك",
                  "matchType": "CONTAINS",
                  "isEnabled": true,
                  "priority": 5
                }
              ],
              "contactFilters": []
            }
            """.trimIndent()
        )
        val v1Import = repository.importBackup(Uri.fromFile(v1File), replaceExisting = true).getOrThrow()
        assertEquals(1, v1Import.first)
        val v1Rule = repository.rulesFlow.first().first()
        assertEquals("مساعدة", v1Rule.keyword)
        assertNull(v1Rule.templateId)

        exportFile.delete()
        v1File.delete()
    }
}
