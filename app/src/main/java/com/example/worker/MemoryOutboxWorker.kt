package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MemoryOutboxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val outboxCoordinator: MemoryOutboxCoordinator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        outboxCoordinator.flushPending()
        return Result.success()
    }
}
