package com.example.aqi.data

import android.content.Context
import android.util.Log
import com.example.aqi.location.LocationHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class AqiRepository(private val context: Context) {
    private val api = AqiApi.instance
    private val prefs = context.aqiPrefs
    private val locationHelper = LocationHelper(context)
    private val feedDateFormatter = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US)

    /** Returns true if sync completed successfully (data saved or intentionally kept). */
    suspend fun syncData(): Boolean {
        try {
            Log.d("AqiRepository", "Fetching master data from network...")
            val masterData = api.getAllData()

            val cacheMinutes = prefs.getLocationCacheMinutes()
            val location = locationHelper.getOptimizedLocation(cacheMinutes)
            if (location == null) {
                Log.e("AqiRepository", "Could not get current location.")
                return false
            }

            if (masterData.sensors.isEmpty()) {
                Log.e("AqiRepository", "No sensors in payload.")
                return false
            }

            val todayDate = LocalDate.now().format(feedDateFormatter)
            val cachedData = prefs.getLatestSensorData()

            // Only consider sensors that include a primary AQI reading.
            val sensorsWithAqi = masterData.sensors.filter { it.primaryAqi != null }
            if (sensorsWithAqi.isEmpty()) {
                Log.w("AqiRepository", "No sensors with a primary AQI in payload. Keeping cached data.")
                return true
            }

            val sensorsWithTodayAqi = sensorsWithAqi.filter { extractDate(it.timestamp) == todayDate }

            val sensorToSave = if (sensorsWithTodayAqi.isNotEmpty()) {
                locationHelper.findNearestSensorFromData(location, sensorsWithTodayAqi)
            } else {
                val cacheIsToday =
                    cachedData != null &&
                    cachedData.primaryAqi != null &&
                    extractDate(cachedData.timestamp) == todayDate

                if (cacheIsToday) {
                    Log.d("AqiRepository", "No nearby AQI sensor with today's date in payload. Keeping cached same-day data.")
                    return true
                }

                Log.w(
                    "AqiRepository",
                    "No AQI sensors with today's date ($todayDate). Falling back to nearest sensor with AQI regardless of date."
                )
                locationHelper.findNearestSensorFromData(location, sensorsWithAqi)
            }

            if (sensorToSave == null) {
                Log.e("AqiRepository", "Could not select a sensor to save.")
                return false
            }

            // Sticky sensor: prefer stale cached data from the same sensor over
            // switching to a different nearby sensor, as long as the cache is from today.
            if (cachedData != null && sensorToSave.name != cachedData.name) {
                val cachedDate = extractDate(cachedData.timestamp)
                if (cachedDate == todayDate && cachedData.primaryAqi != null) {
                    Log.d(
                        "AqiRepository",
                        "Nearest sensor changed from ${cachedData.name} to ${sensorToSave.name}. " +
                            "Keeping cached ${cachedData.name} (still today's data)."
                    )
                    return true
                }
            }

            val selectedDate = extractDate(sensorToSave.timestamp)
            if (selectedDate != todayDate) {
                Log.w(
                    "AqiRepository",
                    "Selected fallback sensor ${sensorToSave.name} from $selectedDate (today is $todayDate)."
                )
            }

            Log.d("AqiRepository", "Selected sensor: ${sensorToSave.name} with AQI: ${sensorToSave.primaryAqi}")
            prefs.saveLatestSensorData(sensorToSave)
            return true

        } catch (e: Exception) {
            Log.e("AqiRepository", "Error syncing data", e)
            return false
        }
    }

    private fun extractDate(timestamp: String): String = timestamp.substringBefore(" ").trim()
}
