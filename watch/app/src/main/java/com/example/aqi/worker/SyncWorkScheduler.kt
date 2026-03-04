package com.example.aqi.worker

import android.content.Context
import com.example.aqi.AppLog
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncWorkScheduler {
    const val PERIODIC_SYNC_WORK_NAME = "aqi_sync_work"
    private const val IMMEDIATE_SYNC_WORK_NAME = "aqi_sync_work_immediate"
    const val INPUT_KEY_TRIGGER_REASON = "trigger_reason"

    private fun connectedNetworkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun schedulePeriodicSync(context: Context) {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(connectedNetworkConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
        AppLog.d("SyncWorkScheduler", "Periodic sync scheduled/updated.")
    }

    fun enqueueImmediateSync(context: Context, triggerReason: String) {
        val inputData = Data.Builder()
            .putString(INPUT_KEY_TRIGGER_REASON, triggerReason)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setConstraints(connectedNetworkConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
        AppLog.d("SyncWorkScheduler", "Immediate sync enqueued. reason=$triggerReason")
    }
}
