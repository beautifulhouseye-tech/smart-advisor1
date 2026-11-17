package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.AutoMateRepository
import com.example.service.AutoReplyKeepAliveService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AutoMateRepository,
    private val scheduleCoordinator: ScheduleCoordinator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val targetEnable = inputData.getBoolean(KEY_ENABLE_AUTO_REPLY, false)
        val settings = repository.getCurrentSettings()
        if (!settings.isScheduleEnabled) {
            return Result.success()
        }

        repository.setAutoReplyEnabled(targetEnable)
        AutoReplyKeepAliveService.syncState(
            context = applicationContext,
            active = targetEnable || settings.isDrivingModeEnabled,
            drivingMode = settings.isDrivingModeEnabled
        )

        // Reschedule next daily window
        scheduleCoordinator.syncSchedule(
            enabled = true,
            startHour = settings.scheduleStartHour,
            startMinute = settings.scheduleStartMinute,
            endHour = settings.scheduleEndHour,
            endMinute = settings.scheduleEndMinute
        )
        return Result.success()
    }

    companion object {
        const val KEY_ENABLE_AUTO_REPLY = "key_enable_auto_reply"
    }
}
