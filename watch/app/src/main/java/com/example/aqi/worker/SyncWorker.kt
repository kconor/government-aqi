package com.example.aqi.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val triggerReason =
            inputData.getString(SyncWorkScheduler.INPUT_KEY_TRIGGER_REASON) ?: "periodic_or_retry"
        val outcome = SyncRunner.runNow(context, triggerReason, manageRetryAlarm = false)

        if (outcome.shouldRetry) {
            return Result.retry()
        }

        return Result.success()
    }
}
