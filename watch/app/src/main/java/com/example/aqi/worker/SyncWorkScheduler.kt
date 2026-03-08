package com.example.aqi.worker

import android.content.Context

object SyncWorkScheduler {
    const val PERIODIC_SYNC_WORK_NAME = "aqi_sync_work" // kept for cancelling legacy work
    const val INPUT_KEY_TRIGGER_REASON = "trigger_reason"

    suspend fun enqueueIfStale(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean = false
    ) {
        SyncRunner.runIfStale(context, triggerReason, manageRetryAlarm)
    }
}
