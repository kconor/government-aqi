package com.example.aqi.data

import android.content.Context
import com.example.aqi.AppLog
import com.example.aqi.location.LocationHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AqiRepository(private val context: Context) {
    private val api = AqiApi.instance
    private val prefs = context.aqiPrefs
    private val locationHelper = LocationHelper(context)

    /** Returns true if the epoch-seconds timestamp falls on today in the device's local timezone. */
    private fun isFromToday(epochSeconds: Long): Boolean {
        val sensorDate = Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return sensorDate == LocalDate.now()
    }

    /** Returns true if sync completed successfully (data saved or intentionally kept). */
    suspend fun syncData(): Boolean {
        try {
            AppLog.d("AqiRepository", "Fetching master data from network...")
            val masterData = api.getAllData()
            AppLog.d("AqiRepository", "Received ${masterData.sensors.size} sensors from API")

            val cacheMinutes = prefs.getLocationCacheMinutes()
            val location = locationHelper.getOptimizedLocation(cacheMinutes)
            if (location == null) {
                AppLog.e("AqiRepository", "Could not get current location.")
                return false
            }

            if (masterData.sensors.isEmpty()) {
                AppLog.e("AqiRepository", "No sensors in payload.")
                return false
            }

            val cachedData = prefs.getLatestSensorData()
            if (cachedData != null) {
                val cacheAgeMin = (System.currentTimeMillis() - cachedData.timestamp * 1000) / 60_000
                AppLog.d("AqiRepository", "Cached data: sensor=${cachedData.name} age=${cacheAgeMin}min aqi=${cachedData.primaryAqi}")
            } else {
                AppLog.d("AqiRepository", "No cached data")
            }

            // Only consider sensors that include a primary AQI reading.
            val sensorsWithAqi = masterData.sensors.filter { it.primaryAqi != null }
            if (sensorsWithAqi.isEmpty()) {
                AppLog.w("AqiRepository", "No sensors with a primary AQI in payload. Keeping cached data.")
                return true
            }

            val sensorsWithTodayAqi = sensorsWithAqi.filter { isFromToday(it.timestamp) }
            AppLog.d("AqiRepository", "Filtered: ${sensorsWithAqi.size} with AQI, ${sensorsWithTodayAqi.size} with today's AQI")

            val sensorToSave = if (sensorsWithTodayAqi.isNotEmpty()) {
                locationHelper.findNearestSensorFromData(location, sensorsWithTodayAqi)
            } else {
                val cacheIsToday =
                    cachedData != null &&
                    cachedData.primaryAqi != null &&
                    isFromToday(cachedData.timestamp)

                if (cacheIsToday) {
                    AppLog.d("AqiRepository", "No nearby AQI sensor with today's date in payload. Keeping cached same-day data.")
                    return true
                }

                AppLog.w(
                    "AqiRepository",
                    "No AQI sensors with today's date. Falling back to nearest sensor with AQI regardless of date."
                )
                locationHelper.findNearestSensorFromData(location, sensorsWithAqi)
            }

            if (sensorToSave == null) {
                AppLog.e("AqiRepository", "Could not select a sensor to save.")
                return false
            }

            // Sticky sensor: prefer stale cached data from the same sensor over
            // switching to a different nearby sensor, as long as the cache is from today.
            if (cachedData != null && sensorToSave.name != cachedData.name) {
                if (isFromToday(cachedData.timestamp) && cachedData.primaryAqi != null) {
                    AppLog.d(
                        "AqiRepository",
                        "Nearest sensor changed from ${cachedData.name} to ${sensorToSave.name}. " +
                            "Keeping cached ${cachedData.name} (still today's data)."
                    )
                    return true
                }
            }

            if (!isFromToday(sensorToSave.timestamp)) {
                AppLog.w(
                    "AqiRepository",
                    "Selected fallback sensor ${sensorToSave.name} is not from today."
                )
            }

            AppLog.d("AqiRepository", "Selected sensor: ${sensorToSave.name} with AQI: ${sensorToSave.primaryAqi}")
            prefs.saveLatestSensorData(sensorToSave)
            return true

        } catch (e: Exception) {
            AppLog.e("AqiRepository", "Error syncing data [${e.javaClass.simpleName}]: ${e.message}", e)
            return false
        }
    }

    /** Sync forecast data. Throttled to once per 24 hours. */
    suspend fun syncForecast() {
        try {
            val lastSync = prefs.getLastForecastSync()
            val hoursSinceLast = (System.currentTimeMillis() - lastSync) / 3_600_000L
            if (hoursSinceLast < 24) {
                AppLog.d("AqiRepository", "Forecast sync skipped — last sync ${hoursSinceLast}h ago")
                return
            }

            AppLog.d("AqiRepository", "Fetching forecast data...")
            val forecastPayload = api.getForecastData()

            if (forecastPayload.locations.isEmpty()) {
                AppLog.w("AqiRepository", "No forecast locations in payload")
                return
            }

            // Try to match by current sensor name
            val currentSensor = prefs.getLatestSensorData()
            val matched = if (currentSensor != null) {
                forecastPayload.locations.find { it.name == currentSensor.name }
                    ?: findNearestForecastLocation(currentSensor.lat, currentSensor.lon, forecastPayload.locations)
            } else {
                // No sensor yet — try device location
                val cacheMinutes = prefs.getLocationCacheMinutes()
                val location = locationHelper.getOptimizedLocation(cacheMinutes)
                if (location != null) {
                    findNearestForecastLocation(location.latitude, location.longitude, forecastPayload.locations)
                } else {
                    null
                }
            }

            if (matched == null) {
                AppLog.w("AqiRepository", "Could not match a forecast location")
                return
            }

            AppLog.d("AqiRepository", "Matched forecast location: ${matched.name} with ${matched.forecasts.size} days")
            prefs.saveForecastData(matched.name, matched.forecasts)

        } catch (e: Exception) {
            AppLog.e("AqiRepository", "Error syncing forecast", e)
        }
    }

    private fun findNearestForecastLocation(
        lat: Double, lon: Double, locations: List<ForecastLocation>
    ): ForecastLocation? {
        if (locations.isEmpty()) return null
        var nearest: ForecastLocation? = null
        var minDist = Double.MAX_VALUE
        for (loc in locations) {
            val dLat = Math.toRadians(loc.lat - lat)
            val dLon = Math.toRadians(loc.lon - lon)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.cos(Math.toRadians(loc.lat)) *
                kotlin.math.sin(dLon / 2).let { it * it }
            val dist = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            if (dist < minDist) {
                minDist = dist
                nearest = loc
            }
        }
        return nearest
    }
}
