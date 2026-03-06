package com.example.aqi.worker

import android.content.Context
import com.example.aqi.AppLog
import com.example.aqi.data.aqiPrefs
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncWorkScheduler {
    const val PERIODIC_SYNC_WORK_NAME = "aqi_sync_work" // kept for cancelling legacy work
    private const val IMMEDIATE_SYNC_WORK_NAME = "aqi_sync_work_immediate"
    const val INPUT_KEY_TRIGGER_REASON = "trigger_reason"

    private const val MIN_SYNC_INTERVAL_MS = 15 * 60 * 1000L      // 15 minutes
    private const val STALE_DATA_THRESHOLD_MS = 90 * 60 * 1000L    // 90 minutes

    private fun connectedNetworkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    suspend fun enqueueIfStale(context: Context, triggerReason: String) {
        val prefs = context.aqiPrefs
        val now = System.currentTimeMillis()
        val snapshot = prefs.readSnapshot()

        val lastAttempt = snapshot.lastSyncAttempt
        if (now - lastAttempt < MIN_SYNC_INTERVAL_MS) {
            AppLog.d("SyncWorkScheduler",
                "enqueueIfStale skipped: last attempt ${(now - lastAttempt) / 1000}s ago (<15 min). reason=$triggerReason")
            return
        }

        val sensorData = snapshot.latestSensorData
        if (sensorData != null) {
            val dataAgeMs = now - (sensorData.timestamp * 1000)
            if (dataAgeMs < STALE_DATA_THRESHOLD_MS) {
                AppLog.d("SyncWorkScheduler",
                    "enqueueIfStale skipped: data only ${dataAgeMs / 60_000}min old (<90 min). reason=$triggerReason")
                return
            }
        }

        val dataAgeMin = if (sensorData != null) (now - sensorData.timestamp * 1000) / 60_000 else -1
        AppLog.d("SyncWorkScheduler",
            "enqueueIfStale: data is ${dataAgeMin}min old (threshold=${STALE_DATA_THRESHOLD_MS / 60_000}min). Enqueueing sync. reason=$triggerReason")
        prefs.setLastSyncAttempt(now)

        val inputData = Data.Builder()
            .putString(INPUT_KEY_TRIGGER_REASON, triggerReason)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setConstraints(connectedNetworkConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, MIN_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            syncRequest
        )
        AppLog.d("SyncWorkScheduler", "Sync enqueued. reason=$triggerReason")
    }
}
