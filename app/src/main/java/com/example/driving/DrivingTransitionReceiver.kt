package com.example.driving

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.repository.AutoMateRepository
import com.example.service.AutoReplyKeepAliveService
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DrivingTransitionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun repository(): AutoMateRepository
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || !ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverEntryPoint::class.java
        )
        val repository = entryPoint.repository()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = repository.getCurrentSettings()
                if (!settings.isAutoDetectDrivingEnabled) return@launch

                for (event in result.transitionEvents) {
                    if (event.activityType == DetectedActivity.IN_VEHICLE) {
                        val enteredVehicle = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                        repository.setDrivingModeEnabled(enteredVehicle)
                        AutoReplyKeepAliveService.syncState(
                            context = context.applicationContext,
                            active = enteredVehicle || settings.isAutoReplyEnabled,
                            drivingMode = enteredVehicle
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
