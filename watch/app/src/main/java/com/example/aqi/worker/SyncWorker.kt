package com.example.aqi.worker

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aqi.AqiComplicationService
import com.example.aqi.data.AqiRepository

class SyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val repo = AqiRepository(context)
        val success = repo.syncData()

        if (!success) {
            return Result.retry()
        }

        // Trigger a complication update only after a successful sync
        val componentName = ComponentName(context, AqiComplicationService::class.java)
        val updateRequester = ComplicationDataSourceUpdateRequester.create(context, componentName)
        updateRequester.requestUpdateAll()

        return Result.success()
    }
}
