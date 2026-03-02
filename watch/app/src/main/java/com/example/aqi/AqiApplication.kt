package com.example.aqi

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.aqi.data.AqiPreferences
import com.example.aqi.worker.SyncWorker
import java.util.concurrent.TimeUnit

class AqiApplication : Application() {
    lateinit var prefs: AqiPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AqiPreferences(this)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "aqi_sync_work",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
