package com.example.aqi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.aqi.AppLog
import com.example.aqi.SyncDiagnostics
import com.example.aqi.bedtime.BedtimeModeHelper
import com.example.aqi.worker.SyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val MAX_RETRY_COUNT = 2
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: SyncAlarmScheduler.ACTION_HOURLY_SYNC
        val retryCount = intent.getIntExtra(SyncAlarmScheduler.EXTRA_RETRY_COUNT, 0)
        AppLog.d("SyncAlarmReceiver", "Received: $action")
        SyncDiagnostics.record(
            context,
            category = "alarm_received",
            triggerReason = "receiver:$action",
            details = "retryCount=$retryCount"
        )

        if (action == SyncAlarmScheduler.ACTION_HOURLY_SYNC) {
            SyncAlarmScheduler.scheduleAlarm(context)
        }

        if (action != SyncAlarmScheduler.ACTION_HOURLY_SYNC &&
            action != SyncAlarmScheduler.ACTION_RETRY_SYNC
        ) {
            if (BedtimeModeHelper.isBedtimeModeActive(context)) {
                SyncAlarmScheduler.cancelAlarm(context)
                SyncAlarmScheduler.cancelRetryAlarm(context)
            } else {
                SyncAlarmScheduler.scheduleAlarm(context)
            }
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val triggerReason = "receiver:$action"
                if (BedtimeModeHelper.isBedtimeModeActive(context)) {
                    SyncAlarmScheduler.cancelAlarm(context)
                    SyncAlarmScheduler.cancelRetryAlarm(context)
                    SyncDiagnostics.record(
                        context,
                        category = "sync_skipped",
                        triggerReason = triggerReason,
                        details = "kind=bedtime_receiver_guard"
                    )
                    return@launch
                }

                val outcome = SyncRunner.runIfStale(
                    context = context,
                    triggerReason = triggerReason,
                    manageRetryAlarm = false,
                    allowDuringBedtime = false,
                    freshnessThresholdMs = SyncRunner.SCHEDULED_SYNC_THRESHOLD_MS
                )

                if (action == SyncAlarmScheduler.ACTION_RETRY_SYNC) {
                    SyncAlarmScheduler.cancelRetryAlarm(context)
                }

                if (outcome?.shouldRetry == true && retryCount < MAX_RETRY_COUNT) {
                    SyncAlarmScheduler.scheduleRetryAlarm(context, retryCount + 1)
                    SyncDiagnostics.record(
                        context,
                        category = "retry_alarm",
                        triggerReason = triggerReason,
                        details = "action=scheduled attempt=${retryCount + 1} of $MAX_RETRY_COUNT"
                    )
                } else {
                    SyncAlarmScheduler.cancelRetryAlarm(context)
                    val details = when {
                        outcome?.shouldRetry == true -> "action=limit_reached attempt=$retryCount"
                        outcome == null -> "action=canceled reason=fresh_or_skipped"
                        else -> "action=canceled reason=fresh_data"
                    }
                    SyncDiagnostics.record(
                        context,
                        category = "retry_alarm",
                        triggerReason = triggerReason,
                        details = details
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
