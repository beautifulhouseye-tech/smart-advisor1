package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.autoMateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "automate_settings"
)

enum class ContactFilterMode(val labelAr: String, val descriptionAr: String) {
    ALL("الرد على الجميع", "إرسال رد تلقائي لأي جهة اتصال ما لم يتم استثناؤها"),
    INCLUDE_ONLY("جهات محددة فقط", "الرد فقط على الأسماء المضافة في قائمة السماح"),
    EXCLUDE_LIST("استثناء جهات محددة", "الرد على الجميع باستثناء الأسماء في قائمة الاستثناء")
}

enum class SupportedMessagingApp(val packageName: String, val displayName: String) {
    WHATSAPP("com.whatsapp", "WhatsApp"),
    WHATSAPP_BUSINESS("com.whatsapp.w4b", "WhatsApp Business"),
    TELEGRAM("org.telegram.messenger", "Telegram"),
    MESSENGER("com.facebook.orca", "Messenger"),
    INSTAGRAM("com.instagram.android", "Instagram")
}

data class AutoMateSettings(
    val isAutoReplyEnabled: Boolean = false,
    val replyInGroups: Boolean = false,
    val contactFilterMode: ContactFilterMode = ContactFilterMode.ALL,
    val replyDelaySeconds: Int = 5,
    val cooldownMinutes: Int = 0,
    val isScheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = 9,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 17,
    val scheduleEndMinute: Int = 0,
    val isDrivingModeEnabled: Boolean = false,
    val isAutoDetectDrivingEnabled: Boolean = false,
    val drivingModeReplyText: String = "أنا أقود السيارة حالياً، سأتواصل معك فور وصولي بأمان.",
    val enabledPackages: Set<String> = setOf(
        SupportedMessagingApp.WHATSAPP.packageName,
        SupportedMessagingApp.WHATSAPP_BUSINESS.packageName,
        SupportedMessagingApp.TELEGRAM.packageName,
        SupportedMessagingApp.MESSENGER.packageName
    ),
    val useAccessibilityFallback: Boolean = true,
    val isBiometricLockEnabled: Boolean = false
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val AUTO_REPLY_ENABLED = booleanPreferencesKey("auto_reply_enabled")
        val REPLY_IN_GROUPS = booleanPreferencesKey("reply_in_groups")
        val CONTACT_FILTER_MODE = stringPreferencesKey("contact_filter_mode")
        val REPLY_DELAY_SECONDS = intPreferencesKey("reply_delay_seconds")
        val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_START_HOUR = intPreferencesKey("schedule_start_hour")
        val SCHEDULE_START_MINUTE = intPreferencesKey("schedule_start_minute")
        val SCHEDULE_END_HOUR = intPreferencesKey("schedule_end_hour")
        val SCHEDULE_END_MINUTE = intPreferencesKey("schedule_end_minute")
        val DRIVING_MODE_ENABLED = booleanPreferencesKey("driving_mode_enabled")
        val AUTO_DETECT_DRIVING_ENABLED = booleanPreferencesKey("auto_detect_driving_enabled")
        val DRIVING_REPLY_TEXT = stringPreferencesKey("driving_reply_text")
        val ENABLED_PACKAGES = stringSetPreferencesKey("enabled_packages")
        val USE_ACCESSIBILITY_FALLBACK = booleanPreferencesKey("use_accessibility_fallback")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
    }

    val settingsFlow: Flow<AutoMateSettings> = context.autoMateDataStore.data.map { prefs ->
        AutoMateSettings(
            isAutoReplyEnabled = prefs[Keys.AUTO_REPLY_ENABLED] ?: false,
            replyInGroups = prefs[Keys.REPLY_IN_GROUPS] ?: false,
            contactFilterMode = prefs[Keys.CONTACT_FILTER_MODE]
                ?.let { raw -> runCatching { ContactFilterMode.valueOf(raw) }.getOrNull() }
                ?: ContactFilterMode.ALL,
            replyDelaySeconds = prefs[Keys.REPLY_DELAY_SECONDS] ?: 5,
            cooldownMinutes = prefs[Keys.COOLDOWN_MINUTES] ?: 0,
            isScheduleEnabled = prefs[Keys.SCHEDULE_ENABLED] ?: false,
            scheduleStartHour = prefs[Keys.SCHEDULE_START_HOUR] ?: 9,
            scheduleStartMinute = prefs[Keys.SCHEDULE_START_MINUTE] ?: 0,
            scheduleEndHour = prefs[Keys.SCHEDULE_END_HOUR] ?: 17,
            scheduleEndMinute = prefs[Keys.SCHEDULE_END_MINUTE] ?: 0,
            isDrivingModeEnabled = prefs[Keys.DRIVING_MODE_ENABLED] ?: false,
            isAutoDetectDrivingEnabled = prefs[Keys.AUTO_DETECT_DRIVING_ENABLED] ?: false,
            drivingModeReplyText = prefs[Keys.DRIVING_REPLY_TEXT]
                ?: "أنا أقود السيارة حالياً، سأتواصل معك فور وصولي بأمان.",
            enabledPackages = prefs[Keys.ENABLED_PACKAGES] ?: setOf(
                SupportedMessagingApp.WHATSAPP.packageName,
                SupportedMessagingApp.WHATSAPP_BUSINESS.packageName,
                SupportedMessagingApp.TELEGRAM.packageName,
                SupportedMessagingApp.MESSENGER.packageName
            ),
            useAccessibilityFallback = prefs[Keys.USE_ACCESSIBILITY_FALLBACK] ?: true,
            isBiometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false
        )
    }

    val autoReplyEnabled: Flow<Boolean> = settingsFlow.map { it.isAutoReplyEnabled }

    suspend fun currentSettings(): AutoMateSettings = settingsFlow.first()

    suspend fun setAutoReplyEnabled(enabled: Boolean) {
        context.autoMateDataStore.edit { it[Keys.AUTO_REPLY_ENABLED] = enabled }
    }

    suspend fun setReplyInGroups(enabled: Boolean) {
        context.autoMateDataStore.edit { it[Keys.REPLY_IN_GROUPS] = enabled }
    }

    suspend fun setContactFilterMode(mode: ContactFilterMode) {
        context.autoMateDataStore.edit { it[Keys.CONTACT_FILTER_MODE] = mode.name }
    }

    suspend fun setReplyDelaySeconds(seconds: Int) {
        context.autoMateDataStore.edit { it[Keys.REPLY_DELAY_SECONDS] = seconds.coerceIn(0, 60) }
    }

    suspend fun setCooldownMinutes(minutes: Int) {
        context.autoMateDataStore.edit { it[Keys.COOLDOWN_MINUTES] = minutes.coerceIn(0, 60) }
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        context.autoMateDataStore.edit { it[Keys.SCHEDULE_ENABLED] = enabled }
    }

    suspend fun setScheduleWindow(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ) {
        context.autoMateDataStore.edit {
            it[Keys.SCHEDULE_START_HOUR] = startHour
            it[Keys.SCHEDULE_START_MINUTE] = startMinute
            it[Keys.SCHEDULE_END_HOUR] = endHour
            it[Keys.SCHEDULE_END_MINUTE] = endMinute
        }
    }

    suspend fun setDrivingModeEnabled(enabled: Boolean) {
        context.autoMateDataStore.edit { it[Keys.DRIVING_MODE_ENABLED] = enabled }
    }

    suspend fun setAutoDetectDrivingEnabled(enabled: Boolean) {
        context.autoMateDataStore.edit { it[Keys.AUTO_DETECT_DRIVING_ENABLED] = enabled }
    }

    suspend fun setDrivingModeReplyText(text: String) {
        context.autoMateDataStore.edit { it[Keys.DRIVING_REPLY_TEXT] = text }
    }

    suspend fun toggleSupportedPackage(packageName: String, enabled: Boolean) {
        context.autoMateDataStore.edit { prefs ->
            val current = prefs[Keys.ENABLED_PACKAGES]?.toMutableSet() ?: mutableSetOf(
                SupportedMessagingApp.WHATSAPP.packageName,
                SupportedMessagingApp.WHATSAPP_BUSINESS.packageName,
                SupportedMessagingApp.TELEGRAM.packageName,
                SupportedMessagingApp.MESSENGER.packageName
            )
            if (enabled) current.add(packageName) else current.remove(packageName)
            prefs[Keys.ENABLED_PACKAGES] = current
        }
    }

    suspend fun setUseAccessibilityFallback(enabled: Boolean) {
        context.autoMateDataStore.edit { it[Keys.USE_ACCESSIBILITY_FALLBACK] = enabled }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.autoMateDataStore.edit { it[Keys.BIOMETRIC_LOCK_ENABLED] = enabled }
    }
}
