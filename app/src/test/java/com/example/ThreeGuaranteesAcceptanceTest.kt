package com.example

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.backup.BackupManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ContactFilterListType
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.preferences.AiSettingsPrefs
import com.example.data.preferences.ContactFilterMode
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.AutoMateRepository
import com.example.data.repository.ServerConfigRepository
import com.example.data.security.SmartAdvisorPrefs
import com.example.engine.AutoReplyEngine
import com.example.engine.IdempotencyGuard
import com.example.engine.LocalOutcome
import com.example.engine.MenuReplyManager
import com.example.engine.NotificationCoordinator
import com.example.engine.NotificationPayload
import com.example.engine.PendingReplyCoordinator
import com.example.engine.ReplyDispatcher
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.ai.AIReplyEngine
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.worker.FollowUpScheduler
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreeGuaranteesAcceptanceTest {

    private lateinit var context: Context
    private lateinit var db: AutoMateDatabase
    private lateinit var settingsStore: SettingsDataStore
    private lateinit var vault: SecretVault
    private lateinit var saPrefs: SmartAdvisorPrefs
    private lateinit var repository: AutoMateRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsStore = SettingsDataStore(context)
        val vaultPrefs = context.getSharedPreferences("tg_vault_${System.nanoTime()}", Context.MODE_PRIVATE)
        vault = SecretVault(context, vaultPrefs)
        val saShared = context.getSharedPreferences("tg_sa_${System.nanoTime()}", Context.MODE_PRIVATE)
        saPrefs = SmartAdvisorPrefs(context, saShared)
        val testJson = Json { ignoreUnknownKeys = true }
        repository = AutoMateRepository(
            replyRuleDao = db.replyRuleDao(),
            contactFilterDao = db.contactFilterDao(),
            replyLogDao = db.replyLogDao(),
            settingsDataStore = settingsStore,
            backupManager = BackupManager(context, testJson)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildDummyReplyAction(): Notification.Action {
        val remoteInput = RemoteInput.Builder("key_text_reply").setLabel("Reply").build()
        val pi = PendingIntent.getBroadcast(
            context,
            101,
            Intent("com.example.ACTION_REPLY"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(0, "Reply", pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun buildDummyContentIntent(): PendingIntent {
        return PendingIntent.getActivity(
            context,
            102,
            Intent("com.example.ACTION_OPEN"),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private class FakeServerConfigRepo(
        initial: ServerConfigEntity? = null
    ) : ServerConfigRepository {
        private val flow = MutableStateFlow(initial)
        override fun observe(): Flow<ServerConfigEntity?> = flow
        override suspend fun get(): ServerConfigEntity? = flow.value
        override suspend fun save(config: ServerConfigEntity) {
            flow.value = config
        }
    }

    private fun buildCoordinator(
        transport: HttpTransport,
        serverConfig: ServerConfigEntity? = null,
        clockMs: () -> Long = { System.currentTimeMillis() },
        rateLimiter: TokenBucketRateLimiter = TokenBucketRateLimiter(10, 10, clockMs),
        queueScheduler: ((Long) -> Unit)? = null
    ): Pair<NotificationCoordinator, IdempotencyGuard> {
        val idempotencyGuard = IdempotencyGuard(db.dedupKeyDao(), clockMs)
        val pendingCoordinator = PendingReplyCoordinator()
        val replyDispatcher = ReplyDispatcher(context, pendingCoordinator, idempotencyGuard)
        val aiPrefs = AiSettingsPrefs(
            context,
            context.getSharedPreferences("tg_ai_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
        val aiEngine = AIReplyEngine(transport, vault)
        val menuManager = MenuReplyManager(db.menuReplyDao(), Json { ignoreUnknownKeys = true })
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(
                context,
                Configuration.Builder().setExecutor { r -> r.run() }.build()
            )
            WorkManager.getInstance(context)
        }
        val followUpScheduler = FollowUpScheduler(workManager, db.followUpDao())
        val autoReplyEngine = AutoReplyEngine(
            repository = repository,
            pendingReplyCoordinator = pendingCoordinator,
            menuReplyManager = menuManager,
            followUpScheduler = followUpScheduler,
            aiSettingsPrefs = aiPrefs,
            aiReplyEngine = aiEngine,
            secretVault = vault,
            replyDispatcher = replyDispatcher
        )
        val coordinator = NotificationCoordinator(
            autoReplyEngine = autoReplyEngine,
            replyDispatcher = replyDispatcher,
            repository = repository,
            serverRepo = FakeServerConfigRepo(serverConfig),
            webhookClient = WebhookClient(transport, vault),
            smartAdvisorClient = SmartAdvisorClient(transport, saPrefs, vault),
            smartAdvisorPrefs = saPrefs,
            idempotencyGuard = idempotencyGuard,
            rateLimiter = rateLimiter,
            workManager = workManager,
            clockMs = clockMs,
            queueScheduler = queueScheduler
        )
        return coordinator to idempotencyGuard
    }

    // 1. HttpTransport applies callTimeout even when timeoutSeconds == 30 and defaults to 25
    @Test
    fun test_01_httpTransport_applies_30s_and_default_25s_timeout() = runTest {
        val observedCallTimeoutsMs = mutableListOf<Int>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                observedCallTimeoutsMs += chain.call().timeout().timeoutNanos().let {
                    TimeUnit.NANOSECONDS.toMillis(it).toInt()
                }
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        // Use real OkHttp call timeout directly via Interceptor reading call timeout via call() or newCall wrapper
        val factoryClient = client
        val transport = HttpTransport(factoryClient)
        val resDefault = transport.execute("https://example.com/hook", body = "{}")
        val resThirty = transport.execute("https://example.com/hook", body = "{}", timeoutSeconds = 30)

        assertTrue(resDefault is HttpTransport.Result.Success)
        assertTrue(resThirty is HttpTransport.Result.Success)
        assertEquals(25_000, observedCallTimeoutsMs[0])
        assertEquals(30_000, observedCallTimeoutsMs[1])
    }

    // 2. Atomic reservation: concurrent calls for same msgKey allow strictly 1 reservation & 1 send
    @Test
    fun test_02_atomic_dedup_parallel_same_msgKey_only_one_wins() = runTest {
        val guard = IdempotencyGuard(db.dedupKeyDao())
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "0|wa|1", "مرحبا")

        val results = (1..8).map {
            async { guard.tryAcquireReservation(msgKey) }
        }.awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(7, results.count { !it })
        assertEquals(DedupKeyEntity.STATE_PROCESSING, db.dedupKeyDao().getByKey(msgKey)?.state)
    }

    // 3. msgKey ignores timestamps and normalizes whitespace consistently
    @Test
    fun test_03_msgKey_ignores_timestamp_and_normalizes_whitespace() {
        val keyA = IdempotencyGuard.deriveMsgKey("com.whatsapp", "sbn-42", "  مرحبا   بكم   اليوم  ")
        val keyB = IdempotencyGuard.deriveMsgKey("com.whatsapp", "sbn-42", "مرحبا بكم اليوم")
        val keyDifferent = IdempotencyGuard.deriveMsgKey("com.whatsapp", "sbn-42", "مرحبا")

        assertEquals(32, keyA.length)
        assertEquals(keyA, keyB)
        assertNotEquals(keyA, keyDifferent)
    }

    // 4. SocketTimeoutException / InterruptedIOException -> DELIVERY_UNKNOWN, no blind retry
    @Test
    fun test_04_socketTimeout_is_deliveryUnknown_without_retry() = runTest {
        val callCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor {
                callCount.incrementAndGet()
                throw SocketTimeoutException("read timed out")
            }
            .build()
        val transport = HttpTransport(client, sleeper = {})

        val result = transport.execute(
            url = "https://example.com/hook",
            body = "{}",
            maxAttempts = 3
        )

        assertTrue(result is HttpTransport.Result.Failure)
        val failure = result as HttpTransport.Result.Failure
        assertEquals(HttpTransport.FailureKind.DELIVERY_UNKNOWN, failure.kind)
        assertFalse(failure.retryable)
        assertEquals(1, callCount.get())

        val iioClient = OkHttpClient.Builder()
            .addInterceptor { throw InterruptedIOException("interrupted") }
            .build()
        val res2 = HttpTransport(iioClient, sleeper = {}).execute("https://example.com/hook", body = "{}")
        assertEquals(
            HttpTransport.FailureKind.DELIVERY_UNKNOWN,
            (res2 as HttpTransport.Result.Failure).kind
        )
    }

    // 5. ConnectException / UnknownHostException -> FAILED_CONFIRMED, retryable within maxAttempts + backoff
    @Test
    fun test_05_preBody_connectException_is_failedConfirmed_and_retries() = runTest {
        val callCount = AtomicInteger(0)
        val backoffs = mutableListOf<Long>()
        val client = OkHttpClient.Builder()
            .addInterceptor {
                callCount.incrementAndGet()
                throw ConnectException("Connection refused")
            }
            .build()
        val transport = HttpTransport(client, sleeper = { ms -> backoffs += ms })

        val result = transport.execute(
            url = "https://example.com/hook",
            body = "{}",
            maxAttempts = 3
        )

        assertTrue(result is HttpTransport.Result.Failure)
        val failure = result as HttpTransport.Result.Failure
        assertEquals(HttpTransport.FailureKind.FAILED_CONFIRMED, failure.kind)
        assertTrue(failure.retryable)
        assertEquals(3, callCount.get())
        assertEquals(listOf(2_000L, 5_000L), backoffs)
    }

    // 6. HTTP 409 returns independent Result.Conflict
    @Test
    fun test_06_http_409_returns_independent_Conflict_result() = runTest {
        val callCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(409)
                    .message("Conflict")
                    .header("Idempotency-Supported", "1")
                    .body("""{"error":"in_progress"}""".toResponseBody())
                    .build()
            }
            .build()
        val transport = HttpTransport(client, sleeper = {})

        val result = transport.execute("https://example.com/hook", body = "{}", maxAttempts = 3)
        assertTrue(result is HttpTransport.Result.Conflict)
        assertEquals(409, (result as HttpTransport.Result.Conflict).code)
        assertEquals(1, callCount.get())
    }

    // 7. HTTP 429 returns RATE_LIMITED with default 60_000ms or Retry-After header
    @Test
    fun test_07_http_429_returns_rateLimited_with_retryAfterMs() = runTest {
        val defaultClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("rate limit".toResponseBody())
                    .build()
            }
            .build()
        val resDefault = HttpTransport(defaultClient).execute("https://example.com/a", body = "{}")
        assertEquals(HttpTransport.FailureKind.RATE_LIMITED, (resDefault as HttpTransport.Result.Failure).kind)
        assertEquals(60_000L, resDefault.retryAfterMs)

        val customClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Retry-After", "45")
                    .body("rate limit".toResponseBody())
                    .build()
            }
            .build()
        val resCustom = HttpTransport(customClient).execute("https://example.com/a", body = "{}")
        assertEquals(45_000L, (resCustom as HttpTransport.Result.Failure).retryAfterMs)
    }

    // 8. HTTP 401/403/404 are strictly NON_RETRYABLE (1 attempt only, no queue retry)
    @Test
    fun test_08_http_4xx_is_nonRetryable_single_attempt_no_queue() = runTest {
        val callCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("bad token".toResponseBody())
                    .build()
            }
            .build()
        val scheduledDelays = mutableListOf<Long>()
        val transport = HttpTransport(client, sleeper = {})
        val serverConfig = ServerConfigEntity(isEnabled = true, url = "https://example.com/hook", retryCount = 3)
        val (coordinator, _) = buildCoordinator(
            transport = transport,
            serverConfig = serverConfig,
            queueScheduler = { scheduledDelays += it }
        )

        val outcome = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-401",
                notificationId = "401",
                senderName = "خالد",
                rawTitle = "خالد",
                messageText = "مرحبا"
            )
        )

        assertEquals(1, callCount.get())
        assertEquals(MessageQueueWorker.STATUS_PERMANENTLY_FAILED, outcome.deliveryMethod)
        assertTrue(scheduledDelays.isEmpty())
    }

    // 9. G3 sends Idempotency-Key and parses Idempotency-Supported & Idempotency-Replayed
    @Test
    fun test_09_g3_sends_idempotencyKey_and_reads_supported_and_replayed() = runTest {
        var capturedKeyHeader: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedKeyHeader = chain.request().header("Idempotency-Key")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Idempotency-Supported", "1")
                    .header("Idempotency-Replayed", "1")
                    .body("""{"reply":"ok"}""".toResponseBody())
                    .build()
            }
            .build()
        val transport = HttpTransport(client)
        val res = transport.execute(
            url = "https://example.com/hook",
            body = "{}",
            idempotencyKey = "abc123key"
        ) as HttpTransport.Result.Success

        assertEquals("abc123key", capturedKeyHeader)
        assertTrue(res.idempotencySupported)
        assertTrue(res.idempotencyReplayed)
    }

    // 10. G3 host with Idempotency-Supported: 1 retries 5xx up to maxAttempts with 2s, 5s backoff
    @Test
    fun test_10_g3_supported_host_retries_5xx_with_backoff() = runTest {
        val attempt = AtomicInteger(0)
        val backoffs = mutableListOf<Long>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = attempt.incrementAndGet()
                if (count < 3) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .header("Idempotency-Supported", "1")
                        .body("busy".toResponseBody())
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Idempotency-Supported", "1")
                        .body("""{"reply":"recovered"}""".toResponseBody())
                        .build()
                }
            }
            .build()
        val transport = HttpTransport(client, sleeper = { backoffs += it })
        val res = transport.execute(
            url = "https://idem.example.com/hook",
            body = "{}",
            maxAttempts = 3,
            idempotencyKey = "k-10"
        )

        assertTrue(res is HttpTransport.Result.Success)
        assertEquals(3, attempt.get())
        assertEquals(listOf(2_000L, 5_000L), backoffs)
    }

    // 11. G3 host WITHOUT Idempotency-Supported forces maxAttempts = 1 on HTTP error
    @Test
    fun test_11_g3_unsupported_host_forces_maxAttempts_1() = runTest {
        val attempt = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempt.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("err".toResponseBody())
                    .build()
            }
            .build()
        val transport = HttpTransport(client, sleeper = {})
        val res = transport.execute(
            url = "https://no-idem.example.com/hook",
            body = "{}",
            maxAttempts = 3,
            idempotencyKey = "k-11"
        )

        assertTrue(res is HttpTransport.Result.Failure)
        assertEquals(1, attempt.get())
        assertFalse(transport.isHostIdempotencySupported("no-idem.example.com"))
    }

    // 12. G2 write-ahead IN_FLIGHT -> SENT blocks Accessibility fallback
    @Test
    fun test_12_g2_writeAhead_inFlight_then_sent_blocks_fallback() = runTest {
        val guard = IdempotencyGuard(db.dedupKeyDao())
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "k12", "hi")
        guard.tryAcquireReservation(msgKey)

        val coordinator = PendingReplyCoordinator()
        val dispatcher = ReplyDispatcher(context, coordinator, guard)
        var sendStateDuringCall: String? = null
        var fallbackCalled = false

        val outcome = dispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "Ali",
            replyText = "أهلاً",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ ->
                // Check DB already has IN_FLIGHT write-ahead before completion
                sendStateDuringCall = kotlinx.coroutines.runBlocking {
                    db.dedupKeyDao().getByKey(msgKey)?.sendState
                }
            },
            contentSendInvoker = { fallbackCalled = true }
        )

        assertEquals(DedupKeyEntity.SEND_IN_FLIGHT, sendStateDuringCall)
        assertTrue(outcome is ReplyDispatcher.SendOutcome.Sent)
        assertFalse(fallbackCalled)
        assertEquals(DedupKeyEntity.SEND_SENT, db.dedupKeyDao().getByKey(msgKey)?.sendState)
    }

    // 13. G2 CanceledException -> FAILED_CONFIRMED -> single Accessibility fallback
    @Test
    fun test_13_g2_canceledException_triggers_single_accessibility_fallback() = runTest {
        val guard = IdempotencyGuard(db.dedupKeyDao())
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "k13", "hi")
        guard.tryAcquireReservation(msgKey)

        val dispatcher = ReplyDispatcher(context, PendingReplyCoordinator(), guard)
        var fallbackCount = 0

        val outcome = dispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "Ali",
            replyText = "رد بديل",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> throw PendingIntent.CanceledException("expired") },
            contentSendInvoker = { fallbackCount++ }
        )

        assertTrue(outcome is ReplyDispatcher.SendOutcome.Sent)
        val sentOutcome = outcome as ReplyDispatcher.SendOutcome.Sent
        assertEquals("Accessibility", sentOutcome.deliveryMethod)
        assertTrue(sentOutcome.isHandoff)
        assertEquals(1, fallbackCount)
        assertEquals(DedupKeyEntity.SEND_HANDOFF, db.dedupKeyDao().getByKey(msgKey)?.sendState)
    }

    // 14. G2 ambiguous error OR stale IN_FLIGHT on startup -> DELIVERY_UNKNOWN (blocks fallback & retry)
    @Test
    fun test_14_g2_ambiguous_failure_and_startup_inFlight_becomes_deliveryUnknown() = runTest {
        val guard = IdempotencyGuard(db.dedupKeyDao())
        val msgKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "k14", "hi")
        guard.tryAcquireReservation(msgKey)

        val dispatcher = ReplyDispatcher(context, PendingReplyCoordinator(), guard)
        var fallbackCalled = false

        val outcome = dispatcher.dispatchReply(
            msgKey = msgKey,
            targetPackage = "com.whatsapp",
            senderName = "Ali",
            replyText = "رد",
            replyAction = buildDummyReplyAction(),
            contentIntent = buildDummyContentIntent(),
            useAccessibilityFallback = true,
            directSendInvoker = { _, _ -> throw RuntimeException("Binder dead / uncertain") },
            contentSendInvoker = { fallbackCalled = true }
        )

        assertTrue(outcome is ReplyDispatcher.SendOutcome.DeliveryUnknown)
        assertFalse(fallbackCalled)
        assertEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, db.dedupKeyDao().getByKey(msgKey)?.state)
        assertFalse(guard.tryAcquireReservation(msgKey))

        // Crash simulation: stale IN_FLIGHT recovered on startup
        val crashKey = IdempotencyGuard.deriveMsgKey("com.whatsapp", "k14-crash", "hi")
        guard.tryAcquireReservation(crashKey)
        guard.updateSendState(crashKey, DedupKeyEntity.SEND_IN_FLIGHT)
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "Ali",
                packageName = "com.whatsapp",
                incomingMessage = "hi",
                sentReply = "",
                isGroup = false,
                deliveryMethod = MessageQueueWorker.STATUS_IN_FLIGHT,
                msgKey = crashKey
            )
        )

        val recovered = guard.recoverStaleInFlight()
        db.replyLogDao().markInFlightLogsAsDeliveryUnknown()
        assertEquals(1, recovered)
        assertEquals(DedupKeyEntity.STATE_DELIVERY_UNKNOWN, db.dedupKeyDao().getByKey(crashKey)?.state)
        assertEquals(
            MessageQueueWorker.STATUS_DELIVERY_UNKNOWN,
            db.replyLogDao().getLogsSince(0L).first { it.msgKey == crashKey }.deliveryMethod
        )
    }

    // 15. COMPLETED TTL (10 minutes) blocks within TTL and allows after 10+ minutes
    @Test
    fun test_15_completed_ttl_blocks_within_10m_and_allows_after() = runTest {
        var now = 1_000_000_000L
        val guard = IdempotencyGuard(db.dedupKeyDao(), clockMs = { now })
        val key = IdempotencyGuard.deriveMsgKey("com.whatsapp", "k15", "مرحبا")

        assertTrue(guard.tryAcquireReservation(key))
        guard.markCompleted(key)

        // 9 minutes later: still within 10m TTL -> blocked
        now += 9L * 60_000L
        assertFalse(guard.tryAcquireReservation(key))

        // 2 more minutes (11 minutes total): expired -> allowed
        now += 2L * 60_000L
        assertTrue(guard.tryAcquireReservation(key))
    }

    // 16. Entry guards: DISABLED, SELF (echo + summary), POLICY, COOLDOWN never reserve dedup or consume rate token
    @Test
    fun test_16_entry_guards_skip_without_consuming_dedup_or_rateLimiter() = runTest {
        var now = 5_000_000_000L
        val rateLimiter = TokenBucketRateLimiter(capacity = 2, refillRatePerMinute = 0, clockMs = { now })
        val emptyTransport = HttpTransport()
        val (coordinator, _) = buildCoordinator(
            transport = emptyTransport,
            clockMs = { now },
            rateLimiter = rateLimiter
        )

        // 1) DISABLED: autoReply=false & webhook=false
        val rDisabled = coordinator.process(
            NotificationPayload("com.whatsapp", "s1", "1", "خالد", "خالد", "مرحبا")
        )
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_DISABLED, rDisabled.deliveryMethod)
        assertNull(db.dedupKeyDao().getByKey(rDisabled.msgKey))
        assertEquals(2.0, rateLimiter.availableTokens(), 0.001)

        // Enable auto-reply + rule + contact filter + cooldown 5m
        settingsStore.setAutoReplyEnabled(true)
        settingsStore.setReplyInGroups(false)
        settingsStore.setReplyDelaySeconds(0)
        settingsStore.setCooldownMinutes(5)
        settingsStore.setContactFilterMode(ContactFilterMode.EXCLUDE_LIST)
        repository.addContactFilter("المزعج", ContactFilterListType.EXCLUDE)
        repository.upsertRule(
            ReplyRuleEntity(keyword = "مرحبا", replyText = "أهلاً بك يا صديقي", matchType = MatchType.CONTAINS)
        )

        // 2) SUMMARY: "3 رسائل جديدة" & "5 new messages"
        val rSumAr = coordinator.process(
            NotificationPayload("com.whatsapp", "s2a", "2", "خالد", "خالد", "3 رسائل جديدة")
        )
        val rSumEn = coordinator.process(
            NotificationPayload("com.whatsapp", "s2b", "3", "خالد", "خالد", "5 new messages")
        )
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_SELF, rSumAr.deliveryMethod)
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_SELF, rSumEn.deliveryMethod)
        assertEquals(2.0, rateLimiter.availableTokens(), 0.001)

        // 3) POLICY: excluded contact & group disabled
        val rPolicyContact = coordinator.process(
            NotificationPayload("com.whatsapp", "s3a", "4", "المزعج", "المزعج", "مرحبا")
        )
        val rPolicyGroup = coordinator.process(
            NotificationPayload("com.whatsapp", "s3b", "5", "جروب", "جروب", "مرحبا", isGroup = true)
        )
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_POLICY, rPolicyContact.deliveryMethod)
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_POLICY, rPolicyGroup.deliveryMethod)
        assertEquals(2.0, rateLimiter.availableTokens(), 0.001)

        // 4) Valid message succeeds (consumes 1 token)
        val rValid = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "s4",
                notificationId = "6",
                senderName = "خالد",
                rawTitle = "خالد",
                messageText = "مرحبا",
                replyAction = buildDummyReplyAction(),
                directSendInvoker = { _, _ -> }
            )
        )
        assertTrue(rValid.isHandled)
        assertEquals(1.0, rateLimiter.availableTokens(), 0.001)

        // 5) ECHO of sent reply within 2 minutes -> SKIPPED_SELF (does NOT consume remaining 1 token)
        now += 30_000L
        val rEcho = coordinator.process(
            NotificationPayload("com.whatsapp", "s5", "7", "خالد", "خالد", "أهلاً بك يا صديقي")
        )
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_SELF, rEcho.deliveryMethod)
        assertEquals(1.0, rateLimiter.availableTokens(), 0.001)

        // 6) COOLDOWN within 5 minutes -> SKIPPED_COOLDOWN (does NOT consume remaining 1 token)
        now += 60_000L
        val rCooldown = coordinator.process(
            NotificationPayload("com.whatsapp", "s6", "8", "خالد", "خالد", "رسالة ثانية مختلفة")
        )
        assertEquals(MessageQueueWorker.STATUS_SKIPPED_COOLDOWN, rCooldown.deliveryMethod)
        assertNull(db.dedupKeyDao().getByKey(rCooldown.msgKey))
        assertEquals(1.0, rateLimiter.availableTokens(), 0.001)
    }

    // 17. RateLimiter rejects 11th message and releases dedup reservation as EXPIRED
    @Test
    fun test_17_rateLimiter_rejects_11th_message_and_releases_dedup_as_expired() = runTest {
        var now = 9_000_000_000L
        settingsStore.setAutoReplyEnabled(true)
        settingsStore.setReplyDelaySeconds(0)
        settingsStore.setCooldownMinutes(0)
        repository.upsertRule(
            ReplyRuleEntity(keyword = "سؤال", replyText = "إجابة", matchType = MatchType.CONTAINS)
        )

        val rateLimiter = TokenBucketRateLimiter(capacity = 10, refillRatePerMinute = 10, clockMs = { now })
        val (coordinator, _) = buildCoordinator(
            transport = HttpTransport(),
            clockMs = { now },
            rateLimiter = rateLimiter
        )

        for (i in 1..10) {
            val res = coordinator.process(
                NotificationPayload(
                    packageName = "com.whatsapp",
                    sbnKey = "sbn-$i",
                    notificationId = "$i",
                    senderName = "عميل $i",
                    rawTitle = "عميل $i",
                    messageText = "سؤال رقم $i",
                    replyAction = buildDummyReplyAction(),
                    directSendInvoker = { _, _ -> }
                )
            )
            assertTrue("Message #$i should succeed", res.isHandled)
        }

        val eleventh = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-11",
                notificationId = "11",
                senderName = "عميل 11",
                rawTitle = "عميل 11",
                messageText = "سؤال رقم 11",
                replyAction = buildDummyReplyAction(),
                directSendInvoker = { _, _ -> }
            )
        )

        assertEquals(MessageQueueWorker.STATUS_SKIPPED_RATE, eleventh.deliveryMethod)
        assertEquals(DedupKeyEntity.STATE_EXPIRED, db.dedupKeyDao().getByKey(eleventh.msgKey)?.state)

        // Advance clock 10 seconds -> refills >=1 token -> previously EXPIRED 11th message CAN now process!
        now += 10_000L
        val retriedEleventh = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-11",
                notificationId = "11",
                senderName = "عميل 11",
                rawTitle = "عميل 11",
                messageText = "سؤال رقم 11",
                replyAction = buildDummyReplyAction(),
                directSendInvoker = { _, _ -> }
            )
        )
        assertTrue(retriedEleventh.isHandled)
    }

    // 18. LocalOutcome: BLOCKED_* / SEND_FAILED_* NEVER go to Webhook; ONLY NO_MATCH triggers Webhook
    @Test
    fun test_18_localOutcome_transitions_to_external_only_on_noMatch() = runTest {
        val externalCalls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                externalCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"reply":"رد ويب هوك"}""".toResponseBody())
                    .build()
            }
            .build()
        val transport = HttpTransport(client)
        val serverConfig = ServerConfigEntity(isEnabled = true, url = "https://example.com/hook")

        settingsStore.setAutoReplyEnabled(true)
        settingsStore.setReplyDelaySeconds(0)
        settingsStore.setUseAccessibilityFallback(false)
        repository.upsertRule(
            ReplyRuleEntity(keyword = "محلي", replyText = "رد محلي", matchType = MatchType.CONTAINS)
        )

        val (coordinator, _) = buildCoordinator(transport = transport, serverConfig = serverConfig)

        // Case A: local rule matches, send fails (FAILED_CONFIRMED) -> MUST NOT call external!
        val failedLocal = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-local-fail",
                notificationId = "100",
                senderName = "أحمد",
                rawTitle = "أحمد",
                messageText = "أريد محلي",
                replyAction = null,
                contentIntent = null
            )
        )
        assertEquals(MessageQueueWorker.STATUS_FAILED_CONFIRMED, failedLocal.deliveryMethod)
        assertEquals(0, externalCalls.get())

        // Case B: no local rule matches (NO_MATCH) -> transitions to Webhook!
        val matchedExternal = coordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-nomatch",
                notificationId = "101",
                senderName = "أحمد",
                rawTitle = "أحمد",
                messageText = "استفسار غير معروف",
                replyAction = buildDummyReplyAction(),
                directSendInvoker = { _, _ -> }
            )
        )
        assertEquals(1, externalCalls.get())
        assertTrue(matchedExternal.isHandled)
        assertEquals("Webhook-DirectReply", matchedExternal.deliveryMethod)
    }

    // 19. MessageQueueWorker skips COMPLETED / PROCESSING / DELIVERY_UNKNOWN, retries FAILED_CONFIRMED with Idempotency-Key
    @Test
    fun test_19_messageQueueWorker_respects_dedup_states_and_sends_idempotencyKey() = runTest {
        val sentIdemKeys = CopyOnWriteArrayList<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sentIdemKeys += chain.request().header("Idempotency-Key")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"reply":"تمت المعالجة"}""".toResponseBody())
                    .build()
            }
            .build()
        val transport = HttpTransport(client)
        val serverConfig = ServerConfigEntity(isEnabled = true, url = "https://example.com/hook")
        val guard = IdempotencyGuard(db.dedupKeyDao())
        val dedupDao = db.dedupKeyDao()
        val logDao = db.replyLogDao()

        val keyCompleted = "k-completed-1"
        val keyProcessing = "k-processing-2"
        val keyUnknown = "k-unknown-3"
        val keyFailedConfirmed = "k-failed-4"

        dedupDao.insertIgnore(DedupKeyEntity(keyCompleted, state = DedupKeyEntity.STATE_COMPLETED))
        dedupDao.insertIgnore(DedupKeyEntity(keyProcessing, state = DedupKeyEntity.STATE_PROCESSING, leaseUntil = System.currentTimeMillis() + 60_000L))
        dedupDao.insertIgnore(DedupKeyEntity(keyUnknown, state = DedupKeyEntity.STATE_DELIVERY_UNKNOWN))
        dedupDao.insertIgnore(DedupKeyEntity(keyFailedConfirmed, state = DedupKeyEntity.STATE_FAILED_CONFIRMED))

        logDao.insertLog(ReplyLogEntity(senderName = "A", packageName = "com.whatsapp", incomingMessage = "m1", sentReply = "err", isGroup = false, deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED, msgKey = keyCompleted))
        logDao.insertLog(ReplyLogEntity(senderName = "B", packageName = "com.whatsapp", incomingMessage = "m2", sentReply = "err", isGroup = false, deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED, msgKey = keyProcessing))
        logDao.insertLog(ReplyLogEntity(senderName = "C", packageName = "com.whatsapp", incomingMessage = "m3", sentReply = "err", isGroup = false, deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED, msgKey = keyUnknown))
        logDao.insertLog(ReplyLogEntity(senderName = "D", packageName = "com.whatsapp", incomingMessage = "m4", sentReply = "err", isGroup = false, deliveryMethod = MessageQueueWorker.STATUS_FAILED_CONFIRMED, msgKey = keyFailedConfirmed))

        val recovered = mutableListOf<String>()
        MessageQueueWorker.executeQueueRetry(
            logDao = logDao,
            serverConfigRepository = FakeServerConfigRepo(serverConfig),
            webhookClient = WebhookClient(transport, vault),
            smartAdvisorClient = SmartAdvisorClient(transport, saPrefs, vault),
            saEnabled = false,
            dedupKeyDao = dedupDao,
            idempotencyGuard = guard,
            onReplyRecovered = { _, _, reply -> recovered += reply }
        )

        // Only FAILED_CONFIRMED was retried over the network!
        assertEquals(listOf(keyFailedConfirmed), sentIdemKeys.toList())
        assertEquals(listOf("تمت المعالجة"), recovered)
        assertEquals(DedupKeyEntity.STATE_COMPLETED, dedupDao.getByKey(keyFailedConfirmed)?.state)

        val logsByKey = logDao.getLogsSince(0L).associateBy { it.msgKey }
        assertEquals(MessageQueueWorker.STATUS_SUCCESS, logsByKey[keyCompleted]?.deliveryMethod)
        assertEquals(MessageQueueWorker.STATUS_FAILED_CONFIRMED, logsByKey[keyProcessing]?.deliveryMethod)
        assertEquals(MessageQueueWorker.STATUS_DELIVERY_UNKNOWN, logsByKey[keyUnknown]?.deliveryMethod)
        assertEquals(MessageQueueWorker.STATUS_SUCCESS, logsByKey[keyFailedConfirmed]?.deliveryMethod)
    }

    // 20. Room migration 5 -> 6 preserves existing data and validates schemas 5 & 6
    @Test
    fun test_20_room_migration_5_to_6_preserves_data_and_validates_schema() = runTest {
        val schemaV5File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/5.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/5.json")
        ).firstOrNull { it.exists() }
        assertNotNull("5.json schema file must exist", schemaV5File)

        val schemaV6File = listOf(
            File("schemas/com.example.data.local.AutoMateDatabase/6.json"),
            File("app/schemas/com.example.data.local.AutoMateDatabase/6.json")
        ).firstOrNull { it.exists() }
        assertNotNull("6.json schema file must exist", schemaV6File)

        val rootV5 = Json.parseToJsonElement(schemaV5File!!.readText()).jsonObject
        val databaseV5 = rootV5["database"]!!.jsonObject
        val entitiesV5 = databaseV5["entities"]!!.jsonArray
        val setupQueriesV5 = databaseV5["setupQueries"]!!.jsonArray

        val dbFile = File(context.cacheDir, "tg_migration_5_6_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        val v5Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (entity in entitiesV5) {
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
                        for (q in setupQueriesV5) {
                            db.execSQL(q.jsonPrimitive.content)
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        v5Helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO dedup_keys (`key`, createdAt)
                VALUES ('existing-dedup-key', 123456789)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO reply_logs (id, senderName, packageName, incomingMessage, sentReply, isGroup, deliveryMethod, timestamp, retryCount)
                VALUES (77, 'سعد', 'com.whatsapp', 'مرحبا', 'أهلاً', 0, 'DirectReply', 123456789, 0)
                """.trimIndent()
            )
            close()
        }
        v5Helper.close()

        // Opening with Room validates migrated v6 schema against compiled v6 schema identity & tables!
        val migratedDb = Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(
                AutoMateDatabase.MIGRATION_5_6,
                AutoMateDatabase.MIGRATION_6_7,
                AutoMateDatabase.MIGRATION_7_8,
                AutoMateDatabase.MIGRATION_8_9
            )
            .allowMainThreadQueries()
            .build()

        val dedupEntry = migratedDb.dedupKeyDao().getByKey("existing-dedup-key")
        assertNotNull(dedupEntry)
        assertEquals(DedupKeyEntity.STATE_NEW, dedupEntry?.state)
        assertEquals(0L, dedupEntry?.leaseUntil)
        assertEquals(DedupKeyEntity.SEND_NONE, dedupEntry?.sendState)
        assertEquals(0, dedupEntry?.attempts)
        assertEquals(0L, dedupEntry?.updatedAt)

        val migratedLog = migratedDb.replyLogDao().getById(77L)
        assertNotNull(migratedLog)
        assertEquals("سعد", migratedLog?.senderName)
        assertEquals("", migratedLog?.msgKey)

        migratedDb.close()
        dbFile.delete()
    }

    // 21. Lease invariant at callTimeout=60s, maxAttempts=2: auto-expands in coordinator & rejects insufficient 90s lease
    @Test
    fun test_21_lease_invariant_autoExpands_and_rejects_insufficient_lease_at_60s_2attempts() = runTest {
        // 1) Insufficient 90s default lease is explicitly rejected when callTimeout=60s and maxAttempts=2
        // Minimum required = 2 * 60_000 + 2_000 = 122_000 ms
        var rejected = false
        try {
            IdempotencyGuard.validateLeaseDuration(
                leaseDurationMs = IdempotencyGuard.DEFAULT_LEASE_MS, // 90_000 ms
                maxAttempts = 2,
                callTimeoutMs = 60_000L
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("90s lease must be rejected for 2x60s + 2s backoff (122s)", rejected)

        // 2) NotificationCoordinator automatically expands lease > 122_000 ms (to 132_000 ms) in Room SQLite
        val now = 20_000_000_000L
        val serverConfig = ServerConfigEntity(
            isEnabled = true,
            url = "https://example.com/hook",
            timeoutSeconds = 60,
            retryCount = 2
        )
        val observedLeaseUntil = mutableListOf<Long>()
        val spyClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val row = kotlinx.coroutines.runBlocking {
                    db.dedupKeyDao().getByKey(
                        IdempotencyGuard.deriveMsgKey("com.whatsapp", "sbn-lease-60", "اختبار المهلة")
                    )
                }
                if (row != null) observedLeaseUntil += row.leaseUntil
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"reply":"ok"}""".toResponseBody())
                    .build()
            }
            .build()
        val (spyCoordinator, _) = buildCoordinator(
            transport = HttpTransport(spyClient),
            serverConfig = serverConfig,
            clockMs = { now }
        )
        val res = spyCoordinator.process(
            NotificationPayload(
                packageName = "com.whatsapp",
                sbnKey = "sbn-lease-60",
                notificationId = "60",
                senderName = "خالد",
                rawTitle = "خالد",
                messageText = "اختبار المهلة",
                replyAction = buildDummyReplyAction(),
                directSendInvoker = { _, _ -> }
            )
        )
        assertTrue(res.isHandled)
        assertEquals(1, observedLeaseUntil.size)
        val expandedLeaseDurationMs = observedLeaseUntil.first() - now
        assertTrue(
            "Lease ($expandedLeaseDurationMs ms) must exceed 2*60_000 + 2_000 = 122_000 ms",
            expandedLeaseDurationMs > 122_000L
        )
        assertEquals(132_000L, expandedLeaseDurationMs)
    }
}
