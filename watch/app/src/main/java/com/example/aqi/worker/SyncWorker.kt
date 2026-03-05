package com.example.aqi.worker

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aqi.AqiComplicationService
import com.example.aqi.AppLog
import com.example.aqi.data.AqiRepository
import com.example.aqi.data.aqiPrefs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class SyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val triggerReason =
            inputData.getString(SyncWorkScheduler.INPUT_KEY_TRIGGER_REASON) ?: "periodic_or_retry"
        AppLog.d("SyncWorker", "Starting sync. reason=$triggerReason")

        val repo = AqiRepository(context)
        val startMs = System.currentTimeMillis()
        val success = repo.syncData()
        val elapsedMs = System.currentTimeMillis() - startMs
        AppLog.d("SyncWorker", "syncData() returned $success in ${elapsedMs}ms. reason=$triggerReason")

        if (!success) {
            AppLog.w("SyncWorker", "Sync failed. Scheduling retry.")
            return Result.retry()
        }

        coroutineScope {
            // Sync forecast in parallel with complication update + timestamp
            launch { repo.syncForecast() }

            // Update lastSyncAttempt so throttle is accurate after a real sync
            context.aqiPrefs.setLastSyncAttempt(System.currentTimeMillis())

            // Trigger a complication update only after a successful sync
            val componentName = ComponentName(context, AqiComplicationService::class.java)
            val updateRequester = ComplicationDataSourceUpdateRequester.create(context, componentName)
            updateRequester.requestUpdateAll()
            AppLog.d("SyncWorker", "Sync succeeded and requested complication update.")
        }

        return Result.success()
    }
}
