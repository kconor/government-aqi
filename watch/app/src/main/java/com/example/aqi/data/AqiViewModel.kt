package com.example.aqi.data

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.aqi.BuildConfig
import com.example.aqi.worker.SyncWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AqiViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.aqiPrefs
    private val repo = AqiRepository(application)

    val latestData: StateFlow<SensorData?> = prefs.latestSensorDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val locationCacheMinutes: StateFlow<Int> = prefs.locationCacheMinutesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    init {
        // Sync on launch in debug builds to avoid WorkManager delays on emulators
        if (BuildConfig.DEBUG) {
            viewModelScope.launch {
                Log.d("AqiViewModel", "Debug: running initial sync directly")
                repo.syncData()
            }
        }
    }

    fun forceRefresh() {
        if (BuildConfig.DEBUG) {
            viewModelScope.launch {
                Log.d("AqiViewModel", "Debug: running forceRefresh directly")
                repo.syncData()
            }
            return
        }
        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(getApplication<Application>()).enqueueUniqueWork(
            "aqi_manual_sync",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    fun setLocationCacheMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setLocationCacheMinutes(minutes)
        }
    }
}
