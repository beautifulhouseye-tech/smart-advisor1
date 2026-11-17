package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.repository.ServerConfigRepository
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.security.SecretVault
import com.example.network.smartadvisor.SmartAdvisorClient
import com.example.worker.MessageQueueWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageQueueWorkerTest {

    private lateinit var context: Context
    private lateinit var db: AutoMateDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AutoMateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeTransport(
        private val result: HttpTransport.Result
    ) : HttpTransport() {
        override suspend fun execute(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
            contentType: String,
            maxAttempts: Int,
            timeoutSeconds: Int,
            idempotencyKey: String?
        ): Result = result
    }

    private class FakeServerConfigRepo(
        private val config: ServerConfigEntity?
    ) : ServerConfigRepository {
        override fun observe(): Flow<ServerConfigEntity?> = flowOf(config)
        override suspend fun get(): ServerConfigEntity? = config
        override suspend fun save(config: ServerConfigEntity) = Unit
    }

    private suspend fun runRetry(
        transport: HttpTransport,
        onRecovered: (String, String, String) -> Unit = { _, _, _ -> }
    ) {
        val prefs = context.getSharedPreferences("mq_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        val vault = SecretVault(context, prefs)
        val webhookClient = WebhookClient(transport, vault)
        val smartAdvisorClient = SmartAdvisorClient(transport, vault, { "" }, { "" })
        val config = ServerConfigEntity(
            isEnabled = true,
            url = "https://example.com/hook",
            responseReplyKey = "reply"
        )

        MessageQueueWorker.executeQueueRetry(
            logDao = db.replyLogDao(),
            serverConfigRepository = FakeServerConfigRepo(config),
            webhookClient = webhookClient,
            smartAdvisorClient = smartAdvisorClient,
            saEnabled = false,
            onReplyRecovered = onRecovered
        )
    }

    @Test
    fun `executeQueueRetry marks failed log as SUCCESS and dispatches reply when webhook succeeds`() = runTest {
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "خالد",
                packageName = "com.whatsapp",
                incomingMessage = "بكم المنتج؟",
                sentReply = "Timeout",
                isGroup = false,
                deliveryMethod = MessageQueueWorker.STATUS_FAILED,
                retryCount = 0
            )
        )

        val dispatched = mutableListOf<Triple<String, String, String>>()
        runRetry(
            transport = FakeTransport(HttpTransport.Result.Success("""{"reply":"السعر 99 ريال"}""", 200, 12L)),
            onRecovered = { pkg, sender, reply -> dispatched.add(Triple(pkg, sender, reply)) }
        )

        val logs = db.replyLogDao().getLogsSince(0L)
        assertEquals(1, logs.size)
        assertEquals(MessageQueueWorker.STATUS_SUCCESS, logs[0].deliveryMethod)
        assertEquals(1, logs[0].retryCount)
        assertEquals("السعر 99 ريال", logs[0].sentReply)
        assertEquals(listOf(Triple("com.whatsapp", "خالد", "السعر 99 ريال")), dispatched)
    }

    @Test
    fun `executeQueueRetry marks log as PERMANENTLY_FAILED on third failed attempt`() = runTest {
        db.replyLogDao().insertLog(
            ReplyLogEntity(
                senderName = "فهد",
                packageName = "com.whatsapp",
                incomingMessage = "أهلاً",
                sentReply = "HTTP 503",
                isGroup = false,
                deliveryMethod = MessageQueueWorker.STATUS_FAILED,
                retryCount = 2
            )
        )

        runRetry(FakeTransport(HttpTransport.Result.Failure("Still Down", 503)))

        val logs = db.replyLogDao().getLogsSince(0L)
        assertEquals(1, logs.size)
        assertEquals(MessageQueueWorker.STATUS_PERMANENTLY_FAILED, logs[0].deliveryMethod)
        assertEquals(3, logs[0].retryCount)
    }
}
