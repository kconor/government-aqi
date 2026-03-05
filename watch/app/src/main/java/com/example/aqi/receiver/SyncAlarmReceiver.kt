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

    companion object {
        private val UNCONDITIONAL_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.TIME_SET",
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "alarm_tick"
        AppLog.d("SyncAlarmReceiver", "Received: $action")

        // Always reschedule the next alarm
        SyncAlarmScheduler.scheduleAlarm(context)

        if (action in UNCONDITIONAL_ACTIONS) {
            SyncWorkScheduler.enqueueImmediateSync(context, "receiver:$action")
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                SyncWorkScheduler.enqueueIfStale(context, "receiver:$action")
            }
        }
    }
}
