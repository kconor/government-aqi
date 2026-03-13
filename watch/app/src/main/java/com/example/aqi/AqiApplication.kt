package com.example.aqi

import android.app.NotificationManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.work.WorkManager
import com.example.aqi.bedtime.BedtimeModeHelper
import com.example.aqi.data.AqiPreferences
import com.example.aqi.receiver.SyncAlarmScheduler
import com.example.aqi.worker.SyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AqiApplication : Application() {
    lateinit var prefs: AqiPreferences
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLogControl.init(this)
        prefs = AqiPreferences(this)

        // Cancel legacy periodic WorkManager sync from previous versions
        WorkManager.getInstance(this).cancelUniqueWork(SyncWorkScheduler.PERIODIC_SYNC_WORK_NAME)

        appScope.launch {
            val snapshot = prefs.readSnapshot()
            if (snapshot.complicationSnapshot != null) {
                ComplicationUpdateDispatcher.requestAll(
                    this@AqiApplication,
                    reason = "app_start_snapshot"
                )
            }
        }

        refreshBackgroundSyncState(triggerReason = "app_start")

        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: Intent) {
                    refreshBackgroundSyncState(
                        triggerReason = "policy:${intent.action}"
                    )
                }
            },
            IntentFilter().apply {
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            }
        )
    }

    fun refreshBackgroundSyncState(triggerReason: String) {
        if (BedtimeModeHelper.isBedtimeModeActive(this)) {
            AppLog.i(
                "AqiApplication",
                "Bedtime active. Canceling background sync alarms. reason=$triggerReason"
            )
            SyncDiagnostics.record(
                this,
                category = "policy_skip",
                triggerReason = triggerReason,
                details = "bedtime=true alarms=canceled"
            )
            SyncAlarmScheduler.cancelAlarm(this)
            SyncAlarmScheduler.cancelRetryAlarm(this)
            return
        }

        SyncDiagnostics.record(
            this,
            category = "policy_refresh",
            triggerReason = triggerReason,
            details = "bedtime=false"
        )
        SyncAlarmScheduler.scheduleAlarm(this)
    }
}
