package com.example.aqi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.aqi.AppLog
import com.example.aqi.worker.SyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: SyncAlarmScheduler.ACTION_HOURLY_SYNC
        AppLog.d("SyncAlarmReceiver", "Received: $action")

        if (action != SyncAlarmScheduler.ACTION_RETRY_SYNC) {
            SyncAlarmScheduler.scheduleAlarm(context)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SyncWorkScheduler.enqueueIfStale(
                    context = context,
                    triggerReason = "receiver:$action",
                    manageRetryAlarm = true
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
