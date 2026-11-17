package com.example.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleCoordinator @Inject constructor(
    private val workManager: WorkManager
) {
    fun syncSchedule(
        enabled: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ) {
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_START)
            workManager.cancelUniqueWork(WORK_END)
            return
        }

        val startDelayMs = computeDelayUntilNextOccurrenceMs(startHour, startMinute)
        val endDelayMs = computeDelayUntilNextOccurrenceMs(endHour, endMinute)

        val startWork = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(startDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ScheduleWorker.KEY_ENABLE_AUTO_REPLY to true))
            .build()

        val endWork = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(endDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ScheduleWorker.KEY_ENABLE_AUTO_REPLY to false))
            .build()

        workManager.enqueueUniqueWork(WORK_START, ExistingWorkPolicy.REPLACE, startWork)
        workManager.enqueueUniqueWork(WORK_END, ExistingWorkPolicy.REPLACE, endWork)
    }

    private fun computeDelayUntilNextOccurrenceMs(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
    }

    companion object {
        private const val WORK_START = "automate_schedule_start"
        private const val WORK_END = "automate_schedule_end"
    }
}
