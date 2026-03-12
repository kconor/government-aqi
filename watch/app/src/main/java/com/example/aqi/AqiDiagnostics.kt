package com.example.aqi

import com.example.aqi.bedtime.BedtimeModeHelper
import com.example.aqi.data.aqiPrefs
import com.example.aqi.location.LocationHelper

object AqiDiagnostics {
    suspend fun dumpState(context: android.content.Context, reason: String) {
        val appContext = context.applicationContext
        val snapshot = appContext.aqiPrefs.readSnapshot()
        val nowMs = System.currentTimeMillis()
        val sensorData = snapshot.latestSensorData
        val sensorAgeMin = sensorData?.let { (nowMs - (it.timestamp * 1000)) / 60_000 }
        val lastAttemptAgeMin = if (snapshot.lastSyncAttempt == 0L) {
            -1L
        } else {
            (nowMs - snapshot.lastSyncAttempt) / 60_000
        }
        val forecastAgeHours = if (snapshot.lastForecastSync == 0L) {
            -1L
        } else {
            (nowMs - snapshot.lastForecastSync) / 3_600_000L
        }

        AppLog.i("AqiDiagnostics", "Dump start. reason=$reason releaseLogsRemainingMs=${AppLogControl.remainingMs()}")
        AppLog.i(
            "AqiDiagnostics",
            "Bedtime access=${BedtimeModeHelper.hasPolicyAccess(appContext)} active=${BedtimeModeHelper.isBedtimeModeActive(appContext)}"
        )
        AppLog.i(
            "AqiDiagnostics",
            "Snapshot lastSyncAttemptAgeMin=$lastAttemptAgeMin locationCacheMinutes=${snapshot.locationCacheMinutes} forecastAgeHours=$forecastAgeHours"
        )

        if (sensorData == null) {
            AppLog.i("AqiDiagnostics", "Latest sensor: none")
        } else {
            AppLog.i(
                "AqiDiagnostics",
                "Latest sensor=${sensorData.name} ageMin=$sensorAgeMin timestamp=${sensorData.timestamp} aqi=${sensorData.primaryAqi} metrics=${sensorData.metrics}"
            )
        }

        val location = LocationHelper(appContext).getOptimizedLocation(snapshot.locationCacheMinutes)
        if (location == null) {
            AppLog.i("AqiDiagnostics", "Current location unavailable")
        } else {
            AppLog.i(
                "AqiDiagnostics",
                "Current location lat=${location.latitude} lon=${location.longitude} provider=${location.provider}"
            )
        }

        AppLog.i(
            "AqiDiagnostics",
            "Build baseUrl=${BuildConfig.AQI_BASE_URL} debug=${BuildConfig.DEBUG}"
        )
        AppLog.i("AqiDiagnostics", "Dump end. reason=$reason")
    }
}
