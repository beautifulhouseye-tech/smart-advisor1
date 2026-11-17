package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.AutoMateApp
import com.example.MainActivity
import com.example.R

class AutoReplyKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val isDriving = intent?.getBooleanExtra(EXTRA_DRIVING_MODE, false) ?: false
        val bodyText = if (isDriving) {
            getString(R.string.notif_service_body_driving)
        } else {
            getString(R.string.notif_service_body_auto)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, AutoMateApp.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(bodyText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 4041
        private const val ACTION_START = "com.example.action.START_KEEP_ALIVE"
        private const val ACTION_STOP = "com.example.action.STOP_KEEP_ALIVE"
        private const val EXTRA_DRIVING_MODE = "extra_driving_mode"

        fun syncState(context: Context, active: Boolean, drivingMode: Boolean) {
            runCatching {
                if (active) {
                    val intent = Intent(context, AutoReplyKeepAliveService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_DRIVING_MODE, drivingMode)
                    }
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.stopService(Intent(context, AutoReplyKeepAliveService::class.java))
                }
            }
        }
    }
}
