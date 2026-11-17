package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.data.local.dao.ReplyLogDao
import com.example.engine.IdempotencyGuard
import com.example.engine.PendingReplyCoordinator
import com.example.service.BluetoothDrivingService
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AutoMateApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var bluetoothDrivingService: BluetoothDrivingService

    @Inject
    lateinit var pendingReplyCoordinator: PendingReplyCoordinator

    @Inject
    lateinit var idempotencyGuard: Lazy<IdempotencyGuard>

    @Inject
    lateinit var replyLogDao: Lazy<ReplyLogDao>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        pendingReplyCoordinator.clear()
        createNotificationChannels()
        appScope.launch {
            bluetoothDrivingService.monitorConnection()
        }
        appScope.launch(Dispatchers.IO) {
            runCatching {
                idempotencyGuard.get().recoverStaleInFlight()
                replyLogDao.get().markInFlightLogsAsDeliveryUnknown()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_service_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID_SERVICE = "automate_service_channel"
    }
}
