package com.example.aqi.data

import android.app.Application
import android.content.ComponentName
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.aqi.AqiComplicationService
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

    init {
        viewModelScope.launch {
            Log.d("AqiViewModel", "Running initial sync")
            if (repo.syncData()) complicationUpdater.requestUpdateAll()
        }
    }

    fun forceRefresh() {
        viewModelScope.launch {
            Log.d("AqiViewModel", "Running forceRefresh")
            if (repo.syncData()) complicationUpdater.requestUpdateAll()
        }
    }

    fun setLocationCacheMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setLocationCacheMinutes(minutes)
        }
    }
}
