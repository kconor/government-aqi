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
        val outcome = repo.syncData()
        val elapsedMs = System.currentTimeMillis() - startMs
        AppLog.d(
            "SyncWorker",
            "syncData(retry=${outcome.shouldRetry}, saved=${outcome.didSaveData}) in ${elapsedMs}ms reason=$triggerReason"
        )

        if (!outcome.shouldRetry || outcome.didSaveData) {
            coroutineScope {
                if (!outcome.shouldRetry) {
                    // Sync forecast in parallel with complication update + timestamp.
                    launch { repo.syncForecast() }
                }

                // Update lastSyncAttempt after any successful data write or completed sync.
                context.aqiPrefs.setLastSyncAttempt(System.currentTimeMillis())

                // Push any newly saved data to complications immediately.
                val componentName = ComponentName(context, AqiComplicationService::class.java)
                val updateRequester = ComplicationDataSourceUpdateRequester.create(context, componentName)
                updateRequester.requestUpdateAll()
                AppLog.d("SyncWorker", "requestUpdateAll() called")
            }
        }

        if (outcome.shouldRetry) {
            AppLog.w("SyncWorker", "Data still stale after fetch. Scheduling 15-minute retry.")
            return Result.retry()
        }

        return Result.success()
    }
}
