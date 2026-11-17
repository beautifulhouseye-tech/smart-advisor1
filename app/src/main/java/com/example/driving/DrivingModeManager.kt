package com.example.driving

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.preferences.SettingsDataStore
import com.example.service.AutoReplyKeepAliveService
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DrivingModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) {
    suspend fun setDrivingMode(enabled: Boolean) {
        settingsDataStore.setDrivingModeEnabled(enabled)
        val current = settingsDataStore.currentSettings()
        AutoReplyKeepAliveService.syncState(
            context = context,
            active = current.isAutoReplyEnabled || enabled,
            drivingMode = enabled
        )
    }

    fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun setAutoDrivingDetection(enabled: Boolean): Boolean {
        val client = ActivityRecognition.getClient(context)
        val pendingIntent = buildTransitionPendingIntent()

        if (!enabled) {
            runCatching { client.removeActivityTransitionUpdates(pendingIntent) }
            return true
        }

        if (!hasActivityRecognitionPermission()) {
            return false
        }

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
        val request = ActivityTransitionRequest(transitions)

        return runCatching {
            client.requestActivityTransitionUpdates(request, pendingIntent)
                .addOnFailureListener { err ->
                    Log.w(TAG, "ActivityRecognition unavailable on this device", err)
                }
            true
        }.getOrDefault(false)
    }

    private fun buildTransitionPendingIntent(): PendingIntent {
        val intent = Intent(context, DrivingTransitionReceiver::class.java).apply {
            action = ACTION_DRIVING_TRANSITION
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    companion object {
        private const val TAG = "DrivingModeManager"
        private const val REQUEST_CODE = 5521
        const val ACTION_DRIVING_TRANSITION = "com.example.action.DRIVING_TRANSITION"
    }
}
