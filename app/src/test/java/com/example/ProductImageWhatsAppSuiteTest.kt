package com.example

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.ConversationMemoryManager
import com.example.engine.IdempotencyGuard
import com.example.engine.ImageDeliveryState
import com.example.engine.MenuReplyManager
import com.example.engine.NotificationCoordinator
import com.example.engine.NotificationPayload
import com.example.engine.PendingAccessibilityReply
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ProductMessage
import com.example.engine.ReplyDispatcher
import com.example.engine.ResponseType
import com.example.engine.SmartCollaborationCoordinator
import com.example.engine.UnifiedReplyTester
import com.example.media.ProductImageCacheManager
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.network.smartadvisor.SmartAdvisorResponseParser
import com.example.service.AutoReplyAccessibilityService
import com.example.worker.FollowUpScheduler
import com.example.worker.MemoryOutboxCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductImageWhatsAppSuiteTest {

    private lateinit var context: Context
    private lateinit var db: AutoMateDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var repository: AutoMateRepository
    private lateinit var aiSettingsPrefs: AiSettingsPrefs
    private lateinit var secretVault: SecretVault
    private lateinit var smartAdvisorPrefs: SmartAdvisorPrefs
    private lateinit var guard: IdempotencyGuard
    private lateinit var pendingCoordinator: PendingReplyCoordinator
    private lateinit var menuReplyManager: MenuReplyManager
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var serverRepo: ServerConfigRepository
    private lateinit var workManager: WorkManager

    private val fakeNowMs = 1_750_000_000_000L
    private val testJson = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsDataStore = SettingsDataStore(context)
        settingsDataStore.setAutoReplyEnabled(true)
        settingsDataStore.setDrivingModeEnabled(false)
        settingsDataStore.setReplyDelaySeconds(0)
        settingsDataStore.setCooldownMinutes(0)
        settingsDataStore.setReplyInGroups(false)
        settingsDataStore.setUseAccessibilityFallback(true)

        repository = AutoMateRepository(
            replyRuleDao = db.replyRuleDao(),
            contactFilterDao = db.contactFilterDao(),
            replyLogDao = db.replyLogDao(),
            settingsDataStore = settingsDataStore,
            backupManager = BackupManager(context, testJson)
        )
        secretVault = SecretVault(
            context,
            context.getSharedPreferences("prod_img_v_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
        aiSettingsPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("prod_img_ai_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
        smartAdvisorPrefs = SmartAdvisorPrefs(
            context,
            context.getSharedPreferences("prod_img_sa_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
        guard = IdempotencyGuard(db.dedupKeyDao(), clockMs = { fakeNowMs })
        pendingCoordinator = PendingReplyCoordinator(clockMs = { fakeNowMs })
        menuReplyManager = MenuReplyManager(db.menuReplyDao(), testJson)
        memoryManager = ConversationMemoryManager(db.conversationContextDao(), secretVault, clockMs = { fakeNowMs })
        serverRepo = object : ServerConfigRepository {
            private val flow = MutableStateFlow<ServerConfigEntity?>(null)
            override fun observe(): Flow<ServerConfigEntity?> = flow
            override suspend fun get(): ServerConfigEntity? = flow.value
            override suspend fun save(config: ServerConfigEntity) {
                flow.value = config
            }
        }
        workManager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(
                context,
                Configuration.Builder().setExecutor { r -> r.run() }.build()
            )
            WorkManager.getInstance(context)
        }
    }

    @After
    fun tearDown() = runBlocking {
        settingsDataStore.setAutoReplyEnabled(false)
        settingsDataStore.setDrivingModeEnabled(false)
        db.close()
    }

    // -------------------------------------------------------------------------
    // 1. Parser & ProductMessage Format Tests
    // -------------------------------------------------------------------------

    @Test
    fun test_01_single_product_with_valid_https_image_parses_to_PRODUCT_IMAGE_and_exact_caption() {
        val body = """
            {
              "store_name": "متجر النخبة",
              "intent": "product_details",
              "reply": "متوفر لدينا حذاء رياضي أزرق بسعر 250 ر.س",
              "products": [
                {
                  "id": 101,
                  "name": "حذاء رياضي مريح",
                  "price": "250",
                  "currency": "ر.س",
                  "short_description": "<p>تصميم خفيف <b>ومريح</b> للمشي اليومي.</p>",
                  "url": "https://www.777736225.com/product/running-shoe/",
                  "image": "https://www.777736225.com/wp-content/uploads/shoe.jpg"
                }
              ]
            }
        """.trimIndent()

        val parsed = SmartAdvisorResponseParser.parseReplyEndpoint(body)
        assertNotNull(parsed)
        assertEquals(ResponseType.PRODUCT_IMAGE, parsed!!.responseType)
        assertNotNull(parsed.selectedProduct)

        val expectedCaption = """
            متجر النخبة - حذاء رياضي مريح

            السعر: 250 ر.س

            تصميم خفيف ومريح للمشي اليومي.

            للمشاهدة والطلب:
            https://www.777736225.com/product/running-shoe/
        """.trimIndent()

        assertEquals(expectedCaption, parsed.selectedProduct!!.buildCaption())
        assertEquals(expectedCaption, parsed.replyText)
    }

    @Test
    fun test_02_omits_blank_fields_and_avoids_duplicate_currency_without_inventing_data() {
        val p = ProductMessage(
            productId = 55L,
            storeName = "",
            imageUrl = "https://cdn.example.com/bag.jpg",
            productName = "حقيبة جلد",
            price = "180 ر.س",
            currency = "ر.س",
            shortDescription = "   ",
            productUrl = "https://cdn.example.com/product/bag"
        )

        val expected = """
            حقيبة جلد

            السعر: 180 ر.س

            للمشاهدة والطلب:
            https://cdn.example.com/product/bag
        """.trimIndent()

        assertEquals(expected, p.buildCaption())

        val noPriceNoUrl = ProductMessage(
            storeName = "متجر النخبة",
            imageUrl = "https://cdn.example.com/item.jpg",
            productName = "عطر عود",
            price = "0",
            currency = "SAR",
            shortDescription = null,
            productUrl = null
        )
        assertEquals("متجر النخبة - عطر عود", noPriceNoUrl.buildCaption())
    }

    @Test
    fun test_03_single_product_without_image_or_placeholder_or_http_falls_back_to_TEXT_MESSAGE() {
        val placeholderBody = """
            {
              "store_name": "متجر النخبة",
              "intent": "product_search",
              "reply": "",
              "products": [
                {
                  "id": 201,
                  "name": "منتج بلا صورة",
                  "price": "120",
                  "currency": "ر.س",
                  "url": "https://www.777736225.com/product/no-img/",
                  "image": "https://www.777736225.com/wp-content/uploads/woocommerce-placeholder.png"
                }
              ]
            }
        """.trimIndent()

        val parsedPlaceholder = SmartAdvisorResponseParser.parseReplyEndpoint(placeholderBody)!!
        assertEquals(ResponseType.TEXT_MESSAGE, parsedPlaceholder.responseType)
        assertNull(parsedPlaceholder.selectedProduct)
        assertTrue(parsedPlaceholder.replyText.contains("منتج بلا صورة"))

        val httpBody = placeholderBody.replace(
            "https://www.777736225.com/wp-content/uploads/woocommerce-placeholder.png",
            "http://www.777736225.com/wp-content/uploads/real.jpg"
        )
        val parsedHttp = SmartAdvisorResponseParser.parseReplyEndpoint(httpBody)!!
        assertEquals(ResponseType.TEXT_MESSAGE, parsedHttp.responseType)
        assertNull(parsedHttp.selectedProduct)
    }

    @Test
    fun test_04_multi_product_category_and_comparison_classifications_are_strictly_preserved() {
        val categoryJson = """
            {
              "store_name": "متجر النخبة",
              "intent": "CATEGORY_BROWSE",
              "category_id": 15,
              "category_name": "الأحذية الرجالية",
              "category_url": "https://www.777736225.com/product-category/men-shoes/",
              "reply": "بكل سرور 👟 تصفح قسم الأحذية الرجالية:\nhttps://www.777736225.com/product-category/men-shoes/",
              "products": [
                { "id": 9, "name": "حذاء", "image": "https://www.777736225.com/img.jpg" }
              ]
            }
        """.trimIndent()
        val parsedCat = SmartAdvisorResponseParser.parseReplyEndpoint(categoryJson)!!
        assertEquals(ResponseType.CATEGORY_LINK, parsedCat.responseType)
        assertNull(parsedCat.selectedProduct)
        assertTrue(parsedCat.products.isEmpty())

        val compareJson = """
            {
              "store_name": "متجر النخبة",
              "intent": "PRODUCT_COMPARE",
              "reply": "الفرق بين المنتج الأول والثاني...",
              "products": [
                { "id": 1, "name": "عطر أ", "image": "https://www.777736225.com/a.jpg" },
                { "id": 2, "name": "عطر ب", "image": "https://www.777736225.com/b.jpg" }
              ]
            }
        """.trimIndent()
        val parsedCompare = SmartAdvisorResponseParser.parseReplyEndpoint(compareJson)!!
        assertEquals(ResponseType.PRODUCT_COMPARISON, parsedCompare.responseType)
        assertNull(parsedCompare.selectedProduct)

        val listJson = compareJson.replace("PRODUCT_COMPARE", "product_search")
        val parsedList = SmartAdvisorResponseParser.parseReplyEndpoint(listJson)!!
        assertEquals(ResponseType.PRODUCT_LIST, parsedList.responseType)
        assertEquals(2, parsedList.products.size)
        assertNull(parsedList.selectedProduct)

        val plainJson = """{ "reply": "أهلاً بك، كيف يمكنني مساعدتك اليوم؟" }"""
        val parsedPlain = SmartAdvisorResponseParser.parseReplyEndpoint(plainJson)!!
        assertEquals(ResponseType.TEXT_MESSAGE, parsedPlain.responseType)
    }

    // -------------------------------------------------------------------------
    // 2. ProductImageCacheManager Tests
    // -------------------------------------------------------------------------

    @Test
    fun test_05_image_cache_validates_caches_and_rejects_invalid_http_html_corrupt_or_oversized() = runTest {
        val networkCalls = AtomicInteger(0)
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCalls.incrementAndGet()
                val url = chain.request().url.toString()
                val (code, mime, bytes) = when {
                    url.endsWith("/valid.png") -> Triple(200, "image/png", VALID_1X1_PNG)
                    url.endsWith("/missing.png") -> Triple(404, "text/html", "<html>404</html>".toByteArray())
                    url.endsWith("/html.jpg") -> Triple(200, "text/html; charset=utf-8", "<html>error</html>".toByteArray())
                    url.endsWith("/corrupt.jpg") -> Triple(200, "image/jpeg", "NOT_AN_IMAGE_PAYLOAD_123456".toByteArray())
                    else -> Triple(200, "image/png", VALID_1X1_PNG)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .header("Content-Type", mime)
                    .body(bytes.toResponseBody(mime.toMediaType()))
                    .build()
            }
            .build()

        val cacheDir = File(context.cacheDir, "test_cache_${System.nanoTime()}")
        val cacheManager = ProductImageCacheManager(context, okHttp, cacheRootDir = cacheDir)

        // Valid download then cache hit
        val first = cacheManager.getOrDownload("https://cdn.example.com/valid.png")
        assertTrue(first is ProductImageCacheManager.CacheResult.Ready)
        val ready1 = first as ProductImageCacheManager.CacheResult.Ready
        assertFalse(ready1.fromCache)
        assertTrue(ready1.file.exists())
        assertEquals(1, networkCalls.get())

        val second = cacheManager.getOrDownload("https://cdn.example.com/valid.png")
        assertTrue(second is ProductImageCacheManager.CacheResult.Ready)
        assertTrue((second as ProductImageCacheManager.CacheResult.Ready).fromCache)
        assertEquals(1, networkCalls.get())

        // Reject HTTP, placeholder, 404, HTML, corrupt, oversized
        assertTrue(cacheManager.getOrDownload("http://cdn.example.com/valid.png") is ProductImageCacheManager.CacheResult.Rejected)
        assertTrue(cacheManager.getOrDownload("https://cdn.example.com/woocommerce-placeholder.png") is ProductImageCacheManager.CacheResult.Rejected)
        assertTrue(cacheManager.getOrDownload("https://cdn.example.com/missing.png") is ProductImageCacheManager.CacheResult.Rejected)
        assertTrue(cacheManager.getOrDownload("https://cdn.example.com/html.jpg") is ProductImageCacheManager.CacheResult.Rejected)
        assertTrue(cacheManager.getOrDownload("https://cdn.example.com/corrupt.jpg") is ProductImageCacheManager.CacheResult.Rejected)
        assertTrue(cacheManager.getOrDownload("https://cdn.example.com/valid2.png", maxBytes = 10L) is ProductImageCacheManager.CacheResult.Rejected)

        cacheDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // 3. End-to-End Delivery & Accessibility Safety Tests
    // -------------------------------------------------------------------------

    @Test
    fun test_06_happy_path_single_product_image_with_caption_and_delivery_states() = runTest {
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "pi-happy", "اريد تفاصيل العطر")
        assertTrue(guard.tryAcquireReservation(msgKey))

        val cacheManager = buildValidImageCacheManager()
        val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard, cacheManager)

        val product = ProductMessage(
            productId = 77L,
            storeName = "متجر النخبة",
            imageUrl = "https://www.777736225.com/wp-content/uploads/oud.png",
            productName = "عطر عود ملكي",
            price = "320",
            currency = "ر.س",
            shortDescription = "ثبات طويل ومناسب للمناسبات",
            productUrl = "https://www.777736225.com/product/oud/"
        )

        var handoffLaunched = 0
        val dispatchOutcome = dispatcher.dispatchProductReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "خالد",
            responseType = ResponseType.PRODUCT_IMAGE,
            productMessage = product,
            fallbackTextReply = product.buildTextFallbackReply(),
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            contentSendInvoker = { handoffLaunched++ }
        )

        assertTrue(dispatchOutcome is ReplyDispatcher.SendOutcome.Sent)
        assertTrue((dispatchOutcome as ReplyDispatcher.SendOutcome.Sent).isHandoff)
        assertEquals(1, handoffLaunched)
        assertEquals(ImageDeliveryState.IMAGE_READY, pendingCoordinator.getDeliveryState(msgKey))
        assertEquals(DedupKeyEntity.SEND_HANDOFF, db.dedupKeyDao().getByKey(msgKey)?.sendState)

        // Build Accessibility wizard screens: Chat -> Sheet -> Picker -> Preview(caption+send)
        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val tracker = AccessibilityFlowTracker()
        val trees = buildProductFlowTrees(
            senderName = "خالد",
            tracker = tracker,
            includeCaptionField = true,
            captionSucceeds = true,
            sendSucceeds = true
        )
        var step = 0
        val rootProvider: () -> AccessibilityNodeInfo? = {
            step++
            trees.getOrElse(step) { trees.last() }
        }

        val sent = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = trees.first(),
            coordinatorOverride = pendingCoordinator,
            idempotencyGuardOverride = guard,
            memoryManagerOverride = memoryManager,
            cacheManagerOverride = cacheManager,
            stepDelayMs = 0L,
            postSendVerifyDelayMs = 0L,
            rootProvider = rootProvider
        )

        assertTrue(sent)
        assertEquals(1, tracker.attachClicks.get())
        assertEquals(1, tracker.galleryClicks.get())
        assertEquals(1, tracker.thumbnailClicks.get())
        assertEquals(1, tracker.captionWrites.get())
        assertEquals(1, tracker.sendClicks.get())
        assertEquals(product.buildCaption(), tracker.lastCaptionText)
        assertTrue(tracker.captionWrittenBeforeSend)
        assertEquals(ImageDeliveryState.SENT, pendingCoordinator.getDeliveryState(msgKey))
        assertEquals(DedupKeyEntity.SEND_SENT, db.dedupKeyDao().getByKey(msgKey)?.sendState)
        assertEquals(DedupKeyEntity.STATE_COMPLETED, db.dedupKeyDao().getByKey(msgKey)?.state)

        // Duplicate accessibility event must write 0 times
        val dupHandled = service.handleEventInternal(
            eventPackage = "com.whatsapp",
            rootNode = trees.first(),
            coordinatorOverride = pendingCoordinator,
            idempotencyGuardOverride = guard,
            stepDelayMs = 0L,
            postSendVerifyDelayMs = 0L
        )
        assertFalse(dupHandled)
        assertEquals(1, tracker.sendClicks.get())
    }

    @Test
    fun test_07_image_download_failure_falls_back_cleanly_once_to_text_message() = runTest {
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "pi-dl-fail", "سعر")
        assertTrue(guard.tryAcquireReservation(msgKey))

        val failingCache = buildCacheManagerWithResult(404, "text/html", "not found".toByteArray())
        val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard, failingCache)

        val product = ProductMessage(
            productId = 12L,
            storeName = "متجر النخبة",
            imageUrl = "https://www.777736225.com/wp-content/uploads/broken.png",
            productName = "ساعة ذكية",
            price = "450",
            currency = "ر.س",
            shortDescription = "شاشة AMOLED",
            productUrl = "https://www.777736225.com/product/watch/"
        )

        var directTextSent = ""
        var directCount = 0
        val outcome = dispatcher.dispatchProductReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "سارة",
            responseType = ResponseType.PRODUCT_IMAGE,
            productMessage = product,
            fallbackTextReply = product.buildTextFallbackReply(),
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, text ->
                directCount++
                directTextSent = text
            }
        )

        assertTrue(outcome is ReplyDispatcher.SendOutcome.Sent)
        assertEquals(1, directCount)
        assertEquals(ImageDeliveryState.FAILED, pendingCoordinator.getDeliveryState(msgKey))
        assertTrue(directTextSent.contains("ساعة ذكية"))
        assertTrue(directTextSent.contains("450 ر.س"))
        assertTrue(directTextSent.contains("https://www.777736225.com/product/watch/"))
        assertEquals(0, pendingCoordinator.pendingCount())
    }

    @Test
    fun test_08_missing_caption_field_or_setText_failure_aborts_image_NEVER_clicks_send_and_falls_back_to_text_once() = runTest {
        suspend fun verifyNoCaptionlessSend(includeCaptionField: Boolean, captionSucceeds: Boolean, suffix: String) {
            val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "pi-cap-$suffix", "اريد هذا")
            assertTrue(guard.tryAcquireReservation(msgKey))

            val cacheManager = buildValidImageCacheManager()
            val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard, cacheManager)
            val product = ProductMessage(
                productId = 88L,
                storeName = "متجر النخبة",
                imageUrl = "https://www.777736225.com/wp-content/uploads/item.png",
                productName = "حقيبة سفر",
                price = "299",
                currency = "ر.س",
                shortDescription = "مقاس كبير",
                productUrl = "https://www.777736225.com/product/bag/"
            )

            val fallbackCount = AtomicInteger(0)
            var fallbackSentText = ""

            dispatcher.dispatchProductReply(
                msgKey = msgKey,
                targetPackage = "com.whatsapp",
                senderName = "عمر",
                responseType = ResponseType.PRODUCT_IMAGE,
                productMessage = product,
                fallbackTextReply = product.buildTextFallbackReply(),
                replyAction = buildDummyReplyAction(),
                contentIntent = buildDummyContentIntent(),
                useAccessibilityFallback = true,
                directSendInvoker = { _, text ->
                    fallbackCount.incrementAndGet()
                    fallbackSentText = text
                },
                contentSendInvoker = { }
            )

            val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
            val tracker = AccessibilityFlowTracker()
            val trees = buildProductFlowTrees(
                senderName = "عمر",
                tracker = tracker,
                includeCaptionField = includeCaptionField,
                captionSucceeds = captionSucceeds,
                sendSucceeds = true
            )
            var idx = 0
            val result = service.handleEventInternal(
                eventPackage = "com.whatsapp",
                rootNode = trees.first(),
                coordinatorOverride = pendingCoordinator,
                idempotencyGuardOverride = guard,
                memoryManagerOverride = memoryManager,
                cacheManagerOverride = cacheManager,
                stepDelayMs = 0L,
                postSendVerifyDelayMs = 0L,
                rootProvider = {
                    idx++
                    trees.getOrElse(idx) { trees.last() }
                }
            )

            assertFalse(result)
            // CRITICAL SAFETY ASSERTION: Never send an image without its caption!
            assertEquals(0, tracker.sendClicks.get())
            assertEquals(ImageDeliveryState.FAILED, pendingCoordinator.getDeliveryState(msgKey))
            // Clean single text fallback with product name, price, URL
            assertEquals(1, fallbackCount.get())
            assertTrue(fallbackSentText.contains("حقيبة سفر"))
            assertTrue(fallbackSentText.contains("299 ر.س"))
            assertTrue(fallbackSentText.contains("https://www.777736225.com/product/bag/"))
        }

        verifyNoCaptionlessSend(includeCaptionField = false, captionSucceeds = false, suffix = "missing")
        verifyNoCaptionlessSend(includeCaptionField = true, captionSucceeds = false, suffix = "settext_false")
    }

    @Test
    fun test_09_send_click_false_triggers_fallback_while_ambiguous_send_blocks_retries_and_fallback() = runTest {
        // Case A: Send button click returns false -> confirmed pre-send failure -> fallback once
        val failKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "pi-send-false", "سؤال")
        assertTrue(guard.tryAcquireReservation(failKey))
        val cacheManager = buildValidImageCacheManager()
        val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard, cacheManager)
        val product = ProductMessage(
            productId = 90L,
            storeName = "متجر النخبة",
            imageUrl = "https://www.777736225.com/wp-content/uploads/p90.png",
            productName = "سماعة",
            price = "150",
            currency = "ر.س",
            productUrl = "https://www.777736225.com/product/headset/"
        )

        val fallbackCounter = AtomicInteger(0)
        dispatcher.dispatchProductReply(
            msgKey = failKey,
            targetPackage = "com.whatsapp",
            senderName = "زياد",
            responseType = ResponseType.PRODUCT_IMAGE,
            productMessage = product,
            fallbackTextReply = product.buildTextFallbackReply(),
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> fallbackCounter.incrementAndGet() },
            contentSendInvoker = { }
        )

        val service = Robolectric.setupService(AutoReplyAccessibilityService::class.java)
        val trackerFail = AccessibilityFlowTracker()
        val treesFail = buildProductFlowTrees(
            senderName = "زياد",
            tracker = trackerFail,
            includeCaptionField = true,
            captionSucceeds = true,
            sendSucceeds = false
        )
        var stepFail = 0
        assertFalse(
            service.handleEventInternal(
                eventPackage = "com.whatsapp",
                rootNode = treesFail.first(),
                coordinatorOverride = pendingCoordinator,
                idempotencyGuardOverride = guard,
                cacheManagerOverride = cacheManager,
                stepDelayMs = 0L,
                postSendVerifyDelayMs = 0L,
                rootProvider = {
                    stepFail++
                    treesFail.getOrElse(stepFail) { treesFail.last() }
                }
            )
        )
        assertEquals(ImageDeliveryState.FAILED, pendingCoordinator.getDeliveryState(failKey))
        assertEquals(1, fallbackCounter.get())

        // Case B: Ambiguous post-send outcome -> DELIVERY_UNKNOWN -> 0 fallback & retries blocked
        val unkKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "pi-amb", "سؤال")
        assertTrue(guard.tryAcquireReservation(unkKey))
        val ambFallbackCounter = AtomicInteger(0)
        dispatcher.dispatchProductReply(
            msgKey = unkKey,
            targetPackage = "com.whatsapp",
            senderName = "ماجد",
            responseType = ResponseType.PRODUCT_IMAGE,
            productMessage = product,
            fallbackTextReply = product.buildTextFallbackReply(),
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> ambFallbackCounter.incrementAndGet() },
            contentSendInvoker = { }
        )

        val trackerUnk = AccessibilityFlowTracker()
        val treesUnk = buildProductFlowTrees(
            senderName = "ماجد",
            tracker = trackerUnk,
            includeCaptionField = true,
            captionSucceeds = true,
            sendSucceeds = true
        )
        var stepUnk = 0
        assertFalse(
            service.handleEventInternal(
                eventPackage = "com.whatsapp",
                rootNode = treesUnk.first(),
                coordinatorOverride = pendingCoordinator,
                idempotencyGuardOverride = guard,
                cacheManagerOverride = cacheManager,
                stepDelayMs = 0L,
                postSendVerifyDelayMs = 0L,
                rootProvider = {
                    stepUnk++
                    treesUnk.getOrElse(stepUnk) { treesUnk.last() }
                },
                postSendVerifier = { AutoReplyAccessibilityService.PostSendStatus.AMBIGUOUS }
            )
        )

        assertEquals(ImageDeliveryState.DELIVERY_UNKNOWN, pendingCoordinator.getDeliveryState(unkKey))
        assertEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, db.dedupKeyDao().getByKey(unkKey)?.state)
        assertEquals(0, ambFallbackCounter.get())

        // Any subsequent dispatch attempt for unkKey is blocked as DeliveryUnknown
        val retryOutcome = dispatcher.dispatchProductReply(
            msgKey = unkKey,
            targetPackage = "com.whatsapp",
            senderName = "ماجد",
            responseType = ResponseType.PRODUCT_IMAGE,
            productMessage = product,
            fallbackTextReply = product.buildTextFallbackReply(),
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true
        )
        assertTrue(retryOutcome is ReplyDispatcher.SendOutcome.DeliveryUnknown)
    }

    @Test
    fun test_10_end_to_end_smart_advisor_standalone_and_exclude_ids_on_more_products() = runTest {
        val capturedRequestBodies = mutableListOf<String>()
        var turnNumber = 0

        val transport = object : HttpTransport() {
            override suspend fun execute(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: String?,
                contentType: String,
                maxAttempts: Int,
                timeoutSeconds: Int,
                idempotencyKey: String?
            ): Result {
                capturedRequestBodies += body.orEmpty()
                turnNumber++
                val responseBody = when (turnNumber) {
                    1 -> """
                        {
                          "store_name": "متجر النخبة",
                          "intent": "CATEGORY_BROWSE",
                          "category_id": 10,
                          "category_name": "أحذية رجالية",
                          "category_url": "https://www.777736225.com/product-category/men-shoes/",
                          "reply": "بكل سرور 👟 تصفح قسم الأحذية الرجالية عبر الرابط:\nhttps://www.777736225.com/product-category/men-shoes/"
                        }
                    """.trimIndent()
                    2 -> """
                        {
                          "store_name": "متجر النخبة",
                          "intent": "product_details",
                          "reply": "تفاصيل المنتج متوفرة",
                          "products": [
                            {
                              "id": 501,
                              "name": "حذاء جري أزرق",
                              "price": "280",
                              "currency": "ر.س",
                              "short_description": "مناسب للركض لمسافات طويلة",
                              "url": "https://www.777736225.com/product/blue-runner/",
                              "image": "https://www.777736225.com/wp-content/uploads/blue-runner.png"
                            }
                          ]
                        }
                    """.trimIndent()
                    else -> """
                        {
                          "store_name": "متجر النخبة",
                          "intent": "MORE_PRODUCTS",
                          "reply": "متجر النخبة - حذاء كلاسيكي أسود",
                          "products": [
                            {
                              "id": 502,
                              "name": "حذاء كلاسيكي أسود",
                              "price": "310",
                              "currency": "ر.س",
                              "short_description": "جلد طبيعي فاخر",
                              "url": "https://www.777736225.com/product/black-classic/",
                              "image": "https://www.777736225.com/wp-content/uploads/black-classic.png"
                            }
                          ]
                        }
                    """.trimIndent()
                }
                return Result.Success(body = responseBody, code = 200, ms = 15L)
            }
        }

        smartAdvisorPrefs.saveCredentials(
            baseUrl = "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply",
            apiKeyRef = "sa_key",
            enabled = true
        )
        secretVault.put("sa_key", "sa-secret")
        aiSettingsPrefs.save(AiSettingsState(enabled = false, useKeywordRules = false))

        val saClient = SmartAdvisorClient(
            transport = transport,
            prefs = smartAdvisorPrefs,
            vault = secretVault
        )
        val cacheManager = buildValidImageCacheManager()
        val dispatcher = ReplyDispatcher(context, pendingCoordinator, guard, cacheManager)
        val aiEngine = AIReplyEngine(transport, secretVault)
        val outbox = MemoryOutboxCoordinator(db.memoryOutboxDao(), saClient, memoryManager, secretVault, null)
        val collab = SmartCollaborationCoordinator(aiSettingsPrefs, aiEngine, saClient, smartAdvisorPrefs, memoryManager, outbox, secretVault)
        val engine = AutoReplyEngine(
            repository = repository,
            pendingReplyCoordinator = pendingCoordinator,
            menuReplyManager = menuReplyManager,
            followUpScheduler = FollowUpScheduler(workManager, db.followUpDao()),
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiEngine,
            secretVault = secretVault,
            replyDispatcher = dispatcher,
            conversationMemoryManager = memoryManager,
            smartCollaborationCoordinator = collab
        )
        val coordinator = NotificationCoordinator(
            autoReplyEngine = engine,
            replyDispatcher = dispatcher,
            repository = repository,
            serverRepo = serverRepo,
            webhookClient = WebhookClient(transport, secretVault),
            smartAdvisorClient = saClient,
            smartAdvisorPrefs = smartAdvisorPrefs,
            idempotencyGuard = guard,
            rateLimiter = TokenBucketRateLimiter(50, 50),
            workManager = workManager,
            aiSettingsPrefs = aiSettingsPrefs,
            clockMs = { fakeNowMs },
            queueScheduler = null,
            conversationMemoryManager = memoryManager
        )

        // Turn 1: Category Browse -> text link only
        var directSentText = ""
        val turn1 = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-t1",
                notificationId = "1",
                senderName = "أحمد",
                rawTitle = "أحمد",
                messageText = "اشتي حذاء رجالي",
                replyAction = buildDummyReplyAction(),
                contentIntent = buildDummyContentIntent(),
                directSendInvoker = { _, text -> directSentText = text }
            )
        )
        assertTrue(turn1.isHandled)
        assertTrue(directSentText.contains("men-shoes"))
        assertEquals(0, pendingCoordinator.pendingCount())

        // Turn 2: Single Product Details -> PRODUCT_IMAGE handoff
        val turn2 = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-t2",
                notificationId = "2",
                senderName = "أحمد",
                rawTitle = "أحمد",
                messageText = "أعطني تفاصيل الحذاء الرياضي الأزرق",
                replyAction = buildDummyReplyAction(),
                contentIntent = buildDummyContentIntent(),
                contentSendInvoker = { }
            )
        )
        assertTrue(turn2.isHandled)
        assertEquals("SmartAdvisor-ProductImage", turn2.deliveryMethod)
        val pendingProduct = pendingCoordinator.peekValidForPackage("com.whatsapp")
        assertNotNull(pendingProduct)
        assertTrue(pendingProduct!!.replyText.contains("متجر النخبة - حذاء جري أزرق"))
        assertTrue(pendingProduct.replyText.contains("السعر: 280 ر.س"))
        assertTrue(pendingProduct.replyText.contains("https://www.777736225.com/product/blue-runner/"))
        pendingCoordinator.clear()

        // Turn 3: More products -> must send exclude_ids=[501]
        val turn3 = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-t3",
                notificationId = "3",
                senderName = "أحمد",
                rawTitle = "أحمد",
                messageText = "غيره؟",
                replyAction = buildDummyReplyAction(),
                contentIntent = buildDummyContentIntent(),
                contentSendInvoker = { }
            )
        )
        assertTrue(turn3.isHandled)
        assertTrue(capturedRequestBodies.last().contains("\"shown_product_ids\":[501]"))
    }

    @Test
    fun test_11_ai_orchestrator_selects_product_image_preserving_exact_caption() = runTest {
        var aiCallCount = 0
        val capturedKnowledgeBodies = mutableListOf<String>()
        val transport = object : HttpTransport() {
            override suspend fun execute(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: String?,
                contentType: String,
                maxAttempts: Int,
                timeoutSeconds: Int,
                idempotencyKey: String?
            ): Result {
                return when {
                    url.contains("capabilities") -> Result.Success(
                        code = 200,
                        ms = 5L,
                        body = """{"ok":true,"store_name":"متجر النخبة","capabilities":{"knowledge":true,"memory":false,"account_actions":false}}"""
                    )
                    url.contains("knowledge") -> {
                        capturedKnowledgeBodies += body.orEmpty()
                        Result.Success(
                            code = 200,
                            ms = 12L,
                            body = """
                                {
                                  "ok": true,
                                  "store_name": "متجر النخبة",
                                  "intent": "product_details",
                                  "summary": "عطر مسك أبيض بسعر 190 ر.س",
                                  "items": [
                                    {
                                      "id": 305,
                                      "name": "مسك أبيض صافي",
                                      "price": "190",
                                      "currency": "ر.س",
                                      "short_description": "عبوة 50 مل بتركيز عالٍ",
                                      "url": "https://www.777736225.com/product/white-musk/",
                                      "image": "https://www.777736225.com/wp-content/uploads/white-musk.png"
                                    }
                                  ]
                                }
                            """.trimIndent()
                        )
                    }
                    else -> {
                        aiCallCount++
                        val contentJson = if (aiCallCount == 1) {
                            "{\"needs_store_data\":true,\"store_query\":\"مسك أبيض صافي\",\"store_intent\":\"product_details\"}"
                        } else {
                            "{\"final_reply\":\"إليك تفاصيل عطر المسك\",\"selected_product_id\":305,\"needs_human\":false}"
                        }
                        val escapedContent = contentJson.replace("\"", "\\\"")
                        Result.Success(
                            code = 200,
                            ms = 20L,
                            body = "{\"choices\":[{\"message\":{\"content\":\"$escapedContent\"}}]}"
                        )
                    }
                }
            }
        }

        secretVault.put(AiSettingsPrefs.KEY_REF_NEW_API, "sk-test-newapi")
        secretVault.put("sa_key_ai", "sa-secret-ai")
        smartAdvisorPrefs.saveCredentials(
            baseUrl = "https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply",
            apiKeyRef = "sa_key_ai",
            enabled = true
        )
        aiSettingsPrefs.save(
            AiSettingsState(
                enabled = true,
                provider = AIReplyEngine.Provider.NEW_API,
                primaryBaseUrl = "https://api.example.com/v1",
                model = "gpt-4o-mini",
                useKeywordRules = false,
                saCollaborationEnabled = true,
                responseLength = com.example.data.preferences.ResponseLength.SHORT
            )
        )

        val saClient = SmartAdvisorClient(transport, smartAdvisorPrefs, secretVault)
        val aiEngine = AIReplyEngine(transport, secretVault)
        val outbox = MemoryOutboxCoordinator(db.memoryOutboxDao(), saClient, memoryManager, secretVault, null)
        val collab = SmartCollaborationCoordinator(aiSettingsPrefs, aiEngine, saClient, smartAdvisorPrefs, memoryManager, outbox, secretVault)
        val tester = UnifiedReplyTester(
            repository = repository,
            menuReplyManager = menuReplyManager,
            aiSettingsPrefs = aiSettingsPrefs,
            aiReplyEngine = aiEngine,
            secretVault = secretVault,
            serverRepo = serverRepo,
            webhookClient = WebhookClient(transport, secretVault),
            smartAdvisorClient = saClient,
            smartAdvisorPrefs = smartAdvisorPrefs,
            conversationMemoryManager = memoryManager,
            smartCollaborationCoordinator = collab,
            clockMs = { fakeNowMs }
        )

        val result = tester.evaluate("أعطني صورة وسعر مسك أبيض صافي")
        assertTrue(result.matched)
        assertEquals(ResponseType.PRODUCT_IMAGE, result.responseType)
        assertNotNull(result.productMessage)
        assertEquals("https://www.777736225.com/wp-content/uploads/white-musk.png", result.imageUri)
        // Exact grounded caption & full URL must NOT be clipped by SHORT length enforcement
        assertTrue(result.replyText.contains("متجر النخبة - مسك أبيض صافي"))
        assertTrue(result.replyText.contains("السعر: 190 ر.س"))
        assertTrue(result.replyText.contains("https://www.777736225.com/product/white-musk/"))
    }

    // -------------------------------------------------------------------------
    // Helpers & Fixtures
    // -------------------------------------------------------------------------

    private class AccessibilityFlowTracker {
        val attachClicks = AtomicInteger(0)
        val galleryClicks = AtomicInteger(0)
        val thumbnailClicks = AtomicInteger(0)
        val captionWrites = AtomicInteger(0)
        val sendClicks = AtomicInteger(0)
        @Volatile var lastCaptionText: String = ""
        @Volatile var captionWrittenBeforeSend: Boolean = false
    }

    private fun buildProductFlowTrees(
        senderName: String,
        tracker: AccessibilityFlowTracker,
        includeCaptionField: Boolean,
        captionSucceeds: Boolean,
        sendSucceeds: Boolean
    ): List<AccessibilityNodeInfo> {
        val pkg = "com.whatsapp"

        // Tree 0: Chat Screen (header + attach button)
        val chatRoot = makeRoot(pkg, senderName)
        val attachBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            className = "android.widget.ImageButton"
            viewIdResourceName = "$pkg:id/input_attach_button"
            isVisibleToUser = true
            isClickable = true
            Shadows.shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    tracker.attachClicks.incrementAndGet()
                }
                true
            }
        }
        Shadows.shadowOf(chatRoot).addChild(attachBtn)

        // Tree 1: Attachment Sheet (Gallery option)
        val sheetRoot = makeRoot(pkg, senderName)
        val galleryBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            className = "android.widget.TextView"
            text = "المعرض"
            isVisibleToUser = true
            isClickable = true
            Shadows.shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    tracker.galleryClicks.incrementAndGet()
                }
                true
            }
        }
        Shadows.shadowOf(sheetRoot).addChild(galleryBtn)

        // Tree 2: Gallery Picker (Thumbnail)
        val pickerRoot = makeRoot(pkg, senderName)
        val thumbNode = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            className = "android.widget.ImageView"
            viewIdResourceName = "$pkg:id/thumbnail"
            isVisibleToUser = true
            isClickable = true
            Shadows.shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    tracker.thumbnailClicks.incrementAndGet()
                }
                true
            }
        }
        Shadows.shadowOf(pickerRoot).addChild(thumbNode)

        // Tree 3: Image Preview Screen (Caption EditText + Send button)
        val previewRoot = makeRoot(pkg, senderName)
        if (includeCaptionField) {
            val captionEdit = AccessibilityNodeInfo.obtain().apply {
                packageName = pkg
                className = "android.widget.EditText"
                viewIdResourceName = "$pkg:id/caption"
                isVisibleToUser = true
                isEditable = true
                isFocusable = true
                Shadows.shadowOf(this).setOnPerformActionListener { action, args ->
                    if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                        if (captionSucceeds) {
                            tracker.captionWrites.incrementAndGet()
                            tracker.lastCaptionText = args
                                ?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                                ?.toString()
                                .orEmpty()
                        }
                        captionSucceeds
                    } else {
                        true
                    }
                }
            }
            Shadows.shadowOf(previewRoot).addChild(captionEdit)
        }

        val sendBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            className = "android.widget.ImageButton"
            viewIdResourceName = "$pkg:id/send"
            contentDescription = "إرسال"
            isVisibleToUser = true
            isClickable = true
            Shadows.shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    tracker.captionWrittenBeforeSend = tracker.captionWrites.get() > 0
                    if (sendSucceeds) {
                        tracker.sendClicks.incrementAndGet()
                    }
                    sendSucceeds
                } else {
                    true
                }
            }
        }
        Shadows.shadowOf(previewRoot).addChild(sendBtn)

        return listOf(chatRoot, sheetRoot, pickerRoot, previewRoot, previewRoot)
    }

    private fun makeRoot(pkg: String, senderName: String): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = pkg
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val titleNode = AccessibilityNodeInfo.obtain()
        titleNode.packageName = pkg
        titleNode.className = "android.widget.TextView"
        titleNode.viewIdResourceName = "$pkg:id/conversation_contact_name"
        titleNode.text = senderName
        titleNode.isVisibleToUser = true
        titleNode.isEditable = false
        Shadows.shadowOf(root).addChild(titleNode)
        return root
    }

    private fun buildValidImageCacheManager(): ProductImageCacheManager {
        return buildCacheManagerWithResult(200, "image/png", VALID_1X1_PNG)
    }

    private fun buildCacheManagerWithResult(
        code: Int,
        mime: String,
        bytes: ByteArray
    ): ProductImageCacheManager {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Mock")
                    .header("Content-Type", mime)
                    .body(bytes.toResponseBody(mime.toMediaType()))
                    .build()
            }
            .build()
        val dir = File(context.cacheDir, "suite_cache_${System.nanoTime()}")
        return ProductImageCacheManager(context, okHttp, cacheRootDir = dir)
    }

    private fun buildDummyReplyAction(): Notification.Action {
        val remoteInput = android.app.RemoteInput.Builder("key_text_reply").setLabel("Reply").build()
        val pi = PendingIntent.getBroadcast(
            context,
            801,
            Intent("com.example.ACTION_REPLY_PROD"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(0, "Reply", pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun buildDummyContentIntent(): PendingIntent {
        return PendingIntent.getActivity(
            context,
            802,
            Intent("com.example.ACTION_OPEN_PROD"),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        // Real valid 1x1 transparent PNG image bytes
        private val VALID_1X1_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
    }
}
