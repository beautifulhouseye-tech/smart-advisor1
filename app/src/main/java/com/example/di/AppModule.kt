package com.example.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.data.local.AutoMateDatabase
import com.example.data.local.dao.ContactFilterDao
import com.example.data.local.dao.ConversationContextDao
import com.example.data.local.dao.DedupKeyDao
import com.example.data.local.dao.FollowUpDao
import com.example.data.local.dao.MemoryOutboxDao
import com.example.data.local.dao.MenuReplyDao
import com.example.data.local.dao.ReplyLogDao
import com.example.data.local.dao.ReplyRuleDao
import com.example.data.local.dao.ReplyTemplateDao
import com.example.data.local.dao.ServerConfigDao
import com.example.data.repository.ServerConfigRepository
import com.example.data.repository.ServerConfigRepositoryImpl
import com.example.engine.IdempotencyGuard
import com.example.network.HttpTransport
import com.example.network.TokenBucketRateLimiter
import com.example.network.WebhookClient
import com.example.network.security.SecretVault
import okhttp3.OkHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutoMateDatabase {
        return Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            "automate_ai.db"
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
            .build()
    }

    @Provides
    fun provideReplyRuleDao(db: AutoMateDatabase): ReplyRuleDao = db.replyRuleDao()

    @Provides
    fun provideReplyTemplateDao(db: AutoMateDatabase): ReplyTemplateDao = db.replyTemplateDao()

    @Provides
    fun provideContactFilterDao(db: AutoMateDatabase): ContactFilterDao = db.contactFilterDao()

    @Provides
    fun provideReplyLogDao(db: AutoMateDatabase): ReplyLogDao = db.replyLogDao()

    @Provides
    fun provideServerConfigDao(db: AutoMateDatabase): ServerConfigDao = db.serverConfigDao()

    @Provides
    fun provideMenuReplyDao(db: AutoMateDatabase): MenuReplyDao = db.menuReplyDao()

    @Provides
    fun provideFollowUpDao(db: AutoMateDatabase): FollowUpDao = db.followUpDao()

    @Provides
    fun provideDedupKeyDao(db: AutoMateDatabase): DedupKeyDao = db.dedupKeyDao()

    @Provides
    fun provideConversationContextDao(db: AutoMateDatabase): ConversationContextDao =
        db.conversationContextDao()

    @Provides
    fun provideMemoryOutboxDao(db: AutoMateDatabase): MemoryOutboxDao =
        db.memoryOutboxDao()

    @Provides
    @Singleton
    fun provideServerConfigRepository(dao: ServerConfigDao): ServerConfigRepository =
        ServerConfigRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideSecretVault(@ApplicationContext context: Context): SecretVault =
        SecretVault(context)

    @Provides
    @Singleton
    fun provideHttpTransport(): HttpTransport = HttpTransport()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = HttpTransport.defaultClient()

    @Provides
    @Singleton
    fun provideTokenBucketRateLimiter(): TokenBucketRateLimiter =
        TokenBucketRateLimiter(capacity = 10, refillRatePerMinute = 10)

    @Provides
    @Singleton
    fun provideIdempotencyGuard(dedupDao: DedupKeyDao): IdempotencyGuard =
        IdempotencyGuard(dedupDao)

    @Provides
    @Singleton
    fun provideWebhookClient(
        transport: HttpTransport,
        vault: SecretVault,
        smartAdvisorPrefs: com.example.data.security.SmartAdvisorPrefs,
        diagnosticLogger: com.example.engine.SafeDiagnosticLogger
    ): WebhookClient = WebhookClient(transport, vault, smartAdvisorPrefs, diagnosticLogger)

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
