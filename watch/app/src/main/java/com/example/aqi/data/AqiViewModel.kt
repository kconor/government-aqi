package com.example.aqi.data

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.aqi.AqiComplicationService
import com.example.aqi.AppLog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AqiViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.aqiPrefs
    private val repo = AqiRepository(application)
    private val complicationUpdater = ComplicationDataSourceUpdateRequester.create(
        application,
        ComponentName(application, AqiComplicationService::class.java)
    )

    val latestData: StateFlow<SensorData?> = prefs.latestSensorDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val locationCacheMinutes: StateFlow<Int> = prefs.locationCacheMinutesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val forecastData: StateFlow<List<ForecastDay>?> = prefs.forecastDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun forceRefresh() {
        viewModelScope.launch {
            AppLog.d("AqiViewModel", "Running forceRefresh")
            val outcome = repo.syncData()
            if (!outcome.shouldRetry || outcome.didSaveData) {
                complicationUpdater.requestUpdateAll()
            }
            if (!outcome.shouldRetry) {
                repo.syncForecast()
            }
        }
    }

    fun setLocationCacheMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setLocationCacheMinutes(minutes)
        }
    }
}
