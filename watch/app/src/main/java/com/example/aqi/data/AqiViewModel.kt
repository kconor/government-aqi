package com.example.aqi.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aqi.AppLog
import com.example.aqi.SyncDiagnostics
import com.example.aqi.worker.SyncRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AqiViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.aqiPrefs
    private val _isRefreshing = MutableStateFlow(false)

    val latestData: StateFlow<SensorData?> = prefs.latestSensorDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val locationCacheMinutes: StateFlow<Int> = prefs.locationCacheMinutesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val forecastData: StateFlow<List<ForecastDay>?> = prefs.forecastDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun forceRefresh() {
        if (_isRefreshing.value) {
            SyncDiagnostics.record(
                getApplication(),
                category = "manual_ignored",
                triggerReason = "manual_refresh",
                details = "already_refreshing=true"
            )
            return
        }

        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                AppLog.d("AqiViewModel", "Running forceRefresh")
                SyncDiagnostics.record(
                    getApplication(),
                    category = "manual_request",
                    triggerReason = "manual_refresh",
                    details = "source=ui"
                )
                SyncRunner.runNow(
                    getApplication(),
                    "manual_refresh",
                    manageRetryAlarm = false,
                    allowDuringBedtime = true
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setLocationCacheMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setLocationCacheMinutes(minutes)
        }
    }
}
