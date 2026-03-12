package com.example.aqi

import android.app.NotificationManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.work.WorkManager
import com.example.aqi.bedtime.BedtimeModeHelper
import com.example.aqi.data.AqiPreferences
import com.example.aqi.receiver.SyncAlarmScheduler
import com.example.aqi.worker.SyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AqiApplication : Application() {
    lateinit var prefs: AqiPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        AppLogControl.init(this)
        prefs = AqiPreferences(this)

        // Cancel legacy periodic WorkManager sync from previous versions
        WorkManager.getInstance(this).cancelUniqueWork(SyncWorkScheduler.PERIODIC_SYNC_WORK_NAME)

        refreshBackgroundSyncState(triggerReason = "app_start", enqueueIfStale = true)

        // Sync on wake from Doze — ACTION_SCREEN_ON must be runtime-registered
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            refreshBackgroundSyncState(
                                triggerReason = "screen_on",
                                enqueueIfStale = true
                            )
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_ON)
        )

        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            refreshBackgroundSyncState(
                                triggerReason = "policy:${intent.action}",
                                enqueueIfStale = true
                            )
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            },
            IntentFilter().apply {
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            }
        )
    }

    private fun refreshBackgroundSyncState(triggerReason: String, enqueueIfStale: Boolean) {
        if (BedtimeModeHelper.isBedtimeModeActive(this)) {
            AppLog.i(
                "AqiApplication",
                "Bedtime active. Canceling background sync alarms. reason=$triggerReason"
            )
            SyncAlarmScheduler.cancelAlarm(this)
            SyncAlarmScheduler.cancelRetryAlarm(this)
            return
        }

        SyncAlarmScheduler.scheduleAlarm(this)

        if (!enqueueIfStale) return

        CoroutineScope(Dispatchers.IO).launch {
            SyncWorkScheduler.enqueueIfStale(
                this@AqiApplication,
                triggerReason,
                manageRetryAlarm = true
            )
        }
    }
}
