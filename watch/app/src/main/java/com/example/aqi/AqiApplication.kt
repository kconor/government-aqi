package com.example.aqi

import android.app.Application
import com.example.aqi.data.AqiPreferences
import com.example.aqi.worker.SyncWorkScheduler

class AqiApplication : Application() {
    lateinit var prefs: AqiPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AqiPreferences(this)

        SyncWorkScheduler.schedulePeriodicSync(this)
        SyncWorkScheduler.enqueueImmediateSync(this, "app_start")
    }
}
