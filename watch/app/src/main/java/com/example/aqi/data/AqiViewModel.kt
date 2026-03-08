package com.example.aqi.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aqi.AppLog
import com.example.aqi.worker.SyncRunner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AqiViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.aqiPrefs

    val latestData: StateFlow<SensorData?> = prefs.latestSensorDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val locationCacheMinutes: StateFlow<Int> = prefs.locationCacheMinutesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val forecastData: StateFlow<List<ForecastDay>?> = prefs.forecastDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun forceRefresh() {
        viewModelScope.launch {
            AppLog.d("AqiViewModel", "Running forceRefresh")
            SyncRunner.runNow(getApplication(), "manual_refresh", manageRetryAlarm = true)
        }
    }

    fun setLocationCacheMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setLocationCacheMinutes(minutes)
        }
    }
}
