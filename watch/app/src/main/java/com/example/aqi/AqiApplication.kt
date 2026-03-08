package com.example.aqi

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.work.WorkManager
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
        prefs = AqiPreferences(this)

        // Cancel legacy periodic WorkManager sync from previous versions
        WorkManager.getInstance(this).cancelUniqueWork(SyncWorkScheduler.PERIODIC_SYNC_WORK_NAME)

        // Schedule hourly alarm for reliable background sync
        SyncAlarmScheduler.scheduleAlarm(this)

        // App start is non-user initiated, so only enqueue when data is stale.
        CoroutineScope(Dispatchers.IO).launch {
            SyncWorkScheduler.enqueueIfStale(
                this@AqiApplication,
                "app_start",
                manageRetryAlarm = true
            )
        }

        // Sync on wake from Doze — ACTION_SCREEN_ON must be runtime-registered
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            SyncWorkScheduler.enqueueIfStale(
                                context,
                                "screen_on",
                                manageRetryAlarm = true
                            )
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_ON)
        )
    }
}
