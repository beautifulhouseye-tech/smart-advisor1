package com.example.worker

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.dao.FollowUpDao
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FollowUpScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val followUpDao: FollowUpDao
) {
    suspend fun scheduleFollowUp(ruleId: Long, recipient: String, app: String) {
        val followUp = followUpDao.getEnabledByRuleOnce(ruleId).firstOrNull() ?: return
        if (!followUp.enabled) return

        val inputData = workDataOf(
            FollowUpWorker.KEY_RECIPIENT to recipient,
            FollowUpWorker.KEY_APP to app,
            FollowUpWorker.KEY_MESSAGE to followUp.messageTemplate
        )

        val request = OneTimeWorkRequestBuilder<FollowUpWorker>()
            .setInitialDelay(followUp.delayMinutes.toLong().coerceAtLeast(1L), TimeUnit.MINUTES)
            .setInputData(inputData)
            .build()

        workManager.enqueue(request)
    }
}
