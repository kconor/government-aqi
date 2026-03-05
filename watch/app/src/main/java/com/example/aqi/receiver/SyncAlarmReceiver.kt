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
        val action = intent.action ?: "alarm_tick"
        AppLog.d("SyncAlarmReceiver", "Received: $action")

        // Always reschedule the next alarm
        SyncAlarmScheduler.scheduleAlarm(context)

        // Receiver triggers are non-user initiated, so gate on stale data.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SyncWorkScheduler.enqueueIfStale(context, "receiver:$action")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
