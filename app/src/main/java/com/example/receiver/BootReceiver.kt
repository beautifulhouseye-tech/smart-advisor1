package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.data.preferences.SettingsDataStore
import com.example.service.AutoReplyKeepAliveService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun settingsDataStore(): SettingsDataStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val appContext = context.applicationContext ?: context
        val settingsDataStore = runCatching {
            EntryPointAccessors.fromApplication(
                appContext,
                BootReceiverEntryPoint::class.java
            ).settingsDataStore()
        }.getOrNull() ?: return

        val pendingResult = runCatching { goAsync() }.getOrNull()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (settingsDataStore.autoReplyEnabled.first()) {
                    val serviceIntent = Intent(appContext, AutoReplyKeepAliveService::class.java)
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
