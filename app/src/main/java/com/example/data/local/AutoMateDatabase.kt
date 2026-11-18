package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.example.data.local.entity.ContactFilterEntity
import com.example.data.local.entity.ConversationContextEntity
import com.example.data.local.entity.DedupKeyEntity
import com.example.data.local.entity.FollowUpEntity
import com.example.data.local.entity.MemoryOutboxEntity
import com.example.data.local.entity.MenuReplyEntity
import com.example.data.local.entity.ReplyLogEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ReplyTemplateEntity
import com.example.data.local.entity.ServerConfigEntity

@Database(
    entities = [
        ReplyRuleEntity::class,
        ContactFilterEntity::class,
        ReplyLogEntity::class,
        ServerConfigEntity::class,
        MenuReplyEntity::class,
        FollowUpEntity::class,
        DedupKeyEntity::class,
        ConversationContextEntity::class,
        MemoryOutboxEntity::class,
        ReplyTemplateEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(AutoMateTypeConverters::class, ServerConverters::class)
abstract class AutoMateDatabase : RoomDatabase() {
    abstract fun replyRuleDao(): ReplyRuleDao
    abstract fun replyTemplateDao(): ReplyTemplateDao
    abstract fun contactFilterDao(): ContactFilterDao
    abstract fun replyLogDao(): ReplyLogDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun menuReplyDao(): MenuReplyDao
    abstract fun followUpDao(): FollowUpDao
    abstract fun dedupKeyDao(): DedupKeyDao
    abstract fun conversationContextDao(): ConversationContextDao
    abstract fun memoryOutboxDao(): MemoryOutboxDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `server_config` (
                        `id` INTEGER NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `url` TEXT NOT NULL,
                        `method` TEXT NOT NULL,
                        `contentType` TEXT NOT NULL,
                        `headersJson` TEXT NOT NULL,
                        `requestFieldsJson` TEXT NOT NULL,
                        `responseReplyKey` TEXT NOT NULL,
                        `timeoutSeconds` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `sendOnGroup` TEXT NOT NULL,
                        `sendOnPrivate` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `menu_replies` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trigger` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `optionsJson` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_menu_replies_trigger` ON `menu_replies` (`trigger`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `follow_ups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ruleId` INTEGER NOT NULL,
                        `delayMinutes` INTEGER NOT NULL,
                        `messageTemplate` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`ruleId`) REFERENCES `reply_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_follow_ups_ruleId` ON `follow_ups` (`ruleId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dedup_keys` (
                        `key` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reply_rules` ADD COLUMN `replyType` TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("ALTER TABLE `reply_rules` ADD COLUMN `imageUri` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `reply_logs` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `dedup_keys` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'NEW'")
                db.execSQL("ALTER TABLE `dedup_keys` ADD COLUMN `leaseUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `dedup_keys` ADD COLUMN `sendState` TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE `dedup_keys` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `dedup_keys` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reply_logs` ADD COLUMN `msgKey` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reply_logs` ADD COLUMN `conversationKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `reply_logs` ADD COLUMN `sourceCategory` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `reply_logs` ADD COLUMN `matchedRuleKeyword` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_contexts` (
                        `conversationKey` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `isGroup` INTEGER NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `customerKey` TEXT NOT NULL,
                        `turnsJson` TEXT NOT NULL,
                        `currentTopic` TEXT NOT NULL,
                        `lastProductDiscussed` TEXT NOT NULL,
                        `temporaryPreferencesJson` TEXT NOT NULL,
                        `unansweredQuestionsJson` TEXT NOT NULL,
                        `pendingAction` TEXT NOT NULL,
                        `pendingAssistantTurnJson` TEXT NOT NULL,
                        `consentGranted` INTEGER NOT NULL,
                        `consentUpdatedAt` INTEGER NOT NULL,
                        `lastMemoryRevision` INTEGER NOT NULL,
                        `lastSyncedSummary` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_outbox` (
                        `eventId` TEXT NOT NULL,
                        `conversationKey` TEXT NOT NULL,
                        `customerKey` TEXT NOT NULL,
                        `encryptedPayload` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `lastError` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_outbox_state` ON `memory_outbox` (`state`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_outbox_conversationKey` ON `memory_outbox` (`conversationKey`)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reply_rules` ADD COLUMN `templateId` INTEGER DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reply_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `replyType` TEXT NOT NULL,
                        `imageUri` TEXT DEFAULT NULL,
                        `usageCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reply_templates_category` ON `reply_templates` (`category`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reply_templates_updatedAt` ON `reply_templates` (`updatedAt`)"
                )
            }
        }
    }
}
