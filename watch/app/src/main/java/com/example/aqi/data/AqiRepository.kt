package com.example.aqi.data

import android.content.Context
import com.example.aqi.AppLog
import com.example.aqi.location.LocationHelper
import com.example.aqi.location.LocationHelper.Companion.distanceKm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AqiRepository(private val context: Context) {
    private val api = AqiApi.instance
    private val prefs = context.aqiPrefs
    private val locationHelper = LocationHelper(context)

    companion object {
        /** If the device is within this distance of the cached sensor, skip nearest-sensor recomputation. */
        private const val SENSOR_REUSE_THRESHOLD_KM = 8.0 // ~5 miles
        /** Scheduled syncs treat data older than one hour as stale and worth retrying. */
        private const val RETRY_STALE_DATA_THRESHOLD_MS = 60 * 60 * 1000L
    }

    data class SyncOutcome(
        val shouldRetry: Boolean,
        val didSaveData: Boolean
    )

    /** Returns true if the epoch-seconds timestamp falls on today in the device's local timezone. */
    private fun isFromToday(epochSeconds: Long): Boolean {
        val sensorDate = Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return sensorDate == LocalDate.now()
    }

    private fun dataAgeMs(epochSeconds: Long, nowMs: Long): Long = nowMs - (epochSeconds * 1000)

    private fun success(didSaveData: Boolean = false): SyncOutcome =
        SyncOutcome(shouldRetry = false, didSaveData = didSaveData)

    private fun retry(didSaveData: Boolean = false): SyncOutcome =
        SyncOutcome(shouldRetry = true, didSaveData = didSaveData)

    private fun retryIfStale(
        sensorData: SensorData?,
        nowMs: Long,
        didSaveData: Boolean,
        reason: String
    ): SyncOutcome? {
        if (sensorData == null) return null

        val ageMs = dataAgeMs(sensorData.timestamp, nowMs)
        if (ageMs < RETRY_STALE_DATA_THRESHOLD_MS) return null

        AppLog.w(
            "AqiRepository",
            "$reason sensor=${sensorData.name} age=${ageMs / 60_000}min; retrying in 15 min for fresher data."
        )
        return retry(didSaveData)
    }

    private suspend fun saveAndReturnFreshnessOutcome(
        sensorData: SensorData,
        nowMs: Long,
        reason: String
    ): SyncOutcome {
        prefs.saveLatestSensorData(sensorData)
        return retryIfStale(
            sensorData = sensorData,
            nowMs = nowMs,
            didSaveData = true,
            reason = reason
        ) ?: success(didSaveData = true)
    }

    private suspend fun refreshWithoutCurrentLocation(
        cachedData: SensorData?,
        sensorsWithAqi: List<SensorData>,
        nowMs: Long
    ): SyncOutcome {
        if (cachedData == null) {
            AppLog.e("AqiRepository", "Could not get current location and no cached sensor is available.")
            return retry()
        }

        val updatedCachedSensor = sensorsWithAqi.find { it.name == cachedData.name }
        if (updatedCachedSensor != null) {
            AppLog.w(
                "AqiRepository",
                "Location unavailable. Refreshing cached sensor ${cachedData.name} without a new fix."
            )
            return saveAndReturnFreshnessOutcome(
                sensorData = updatedCachedSensor,
                nowMs = nowMs,
                reason = "Refreshed cached sensor without a fresh location."
            )
        }

        val cacheStillFresh =
            cachedData.primaryAqi != null &&
                isFromToday(cachedData.timestamp) &&
                dataAgeMs(cachedData.timestamp, nowMs) < RETRY_STALE_DATA_THRESHOLD_MS
        if (cacheStillFresh) {
            AppLog.w(
                "AqiRepository",
                "Location unavailable and cached sensor ${cachedData.name} was not in the payload. Keeping cached same-day data."
            )
            return retryIfStale(
                sensorData = cachedData,
                nowMs = nowMs,
                didSaveData = false,
                reason = "Location unavailable and cached sensor could not be refreshed."
            ) ?: success()
        }

        val nearestByCachedSensor = sensorsWithAqi.minByOrNull {
            distanceKm(cachedData.lat, cachedData.lon, it.lat, it.lon)
        }
        if (nearestByCachedSensor != null) {
            AppLog.w(
                "AqiRepository",
                "Location unavailable. Falling back to the sensor nearest cached sensor ${cachedData.name}: ${nearestByCachedSensor.name}."
            )
            return saveAndReturnFreshnessOutcome(
                sensorData = nearestByCachedSensor,
                nowMs = nowMs,
                reason = "Selected fallback sensor using cached sensor coordinates."
            )
        }

        AppLog.e("AqiRepository", "Location unavailable and no fallback sensor could be selected.")
        return retry()
    }

    /** Returns the sync outcome and whether a follow-up retry is needed for freshness. */
    suspend fun syncData(): SyncOutcome {
        try {
            AppLog.d("AqiRepository", "Fetching master data from network...")
            val masterData = withContext(Dispatchers.IO) { api.getAllData() }
            AppLog.d("AqiRepository", "Received ${masterData.sensors.size} sensors from API")
            val snapshot = prefs.readSnapshot()
            val nowMs = System.currentTimeMillis()
            val cachedData = snapshot.latestSensorData

            if (cachedData != null) {
                val cacheAgeMin = dataAgeMs(cachedData.timestamp, nowMs) / 60_000
                AppLog.d("AqiRepository", "Cached data: sensor=${cachedData.name} age=${cacheAgeMin}min aqi=${cachedData.primaryAqi}")
            } else {
                AppLog.d("AqiRepository", "No cached data")
            }

            if (masterData.sensors.isEmpty()) {
                AppLog.e("AqiRepository", "No sensors in payload.")
                return retry()
            }

            // Only consider sensors that include a primary AQI reading.
            val sensorsWithAqi = masterData.sensors.filter { it.primaryAqi != null }
            if (sensorsWithAqi.isEmpty()) {
                AppLog.w("AqiRepository", "No sensors with a primary AQI in payload. Keeping cached data.")
                return retryIfStale(
                    sensorData = cachedData,
                    nowMs = nowMs,
                    didSaveData = false,
                    reason = "Payload contains no sensors with AQI."
                ) ?: success()
            }

            val cacheMinutes = snapshot.locationCacheMinutes
            val location = locationHelper.getOptimizedLocation(cacheMinutes)
            if (location == null) {
                return refreshWithoutCurrentLocation(
                    cachedData = cachedData,
                    sensorsWithAqi = sensorsWithAqi,
                    nowMs = nowMs
                )
            }

            AppLog.d(
                "AqiRepository",
                "Using device location lat=${location.latitude}, lon=${location.longitude}"
            )

            if (cachedData != null) {
                val distKm = distanceKm(location.latitude, location.longitude, cachedData.lat, cachedData.lon)
                if (distKm < SENSOR_REUSE_THRESHOLD_KM) {
                    val updated = sensorsWithAqi.find { it.name == cachedData.name }
                    if (updated != null && isFromToday(updated.timestamp)) {
                        AppLog.d(
                            "AqiRepository",
                            "Within ${distKm.toInt()}km of ${cachedData.name}, reusing same sensor"
                        )
                        return saveAndReturnFreshnessOutcome(
                            sensorData = updated,
                            nowMs = nowMs,
                            reason = "Reused same sensor but payload timestamp has not advanced yet."
                        )
                    }
                }
            }

            val sensorsWithTodayAqi = sensorsWithAqi.filter { isFromToday(it.timestamp) }
            AppLog.d("AqiRepository", "Filtered: ${sensorsWithAqi.size} with AQI, ${sensorsWithTodayAqi.size} with today's AQI")

            val sensorToSave = if (sensorsWithTodayAqi.isNotEmpty()) {
                locationHelper.findNearestSensorFromData(location, sensorsWithTodayAqi)
            } else {
                val cacheStillFresh =
                    cachedData != null &&
                        cachedData.primaryAqi != null &&
                        isFromToday(cachedData.timestamp) &&
                        dataAgeMs(cachedData.timestamp, nowMs) < RETRY_STALE_DATA_THRESHOLD_MS

                if (cacheStillFresh) {
                    AppLog.d("AqiRepository", "No nearby AQI sensor with today's date in payload. Keeping cached same-day data.")
                    return retryIfStale(
                        sensorData = cachedData,
                        nowMs = nowMs,
                        didSaveData = false,
                        reason = "Payload did not contain a fresh same-day AQI update."
                    ) ?: success()
                }

                AppLog.w(
                    "AqiRepository",
                    "No AQI sensors with today's date. Falling back to nearest sensor with AQI regardless of date."
                )
                locationHelper.findNearestSensorFromData(location, sensorsWithAqi)
            }

            if (sensorToSave == null) {
                AppLog.e("AqiRepository", "Could not select a sensor to save.")
                return retry()
            }

            // Sticky sensor: prefer stale cached data from the same sensor over
            // switching to a different nearby sensor, as long as the cache is still reasonably fresh.
            if (cachedData != null && sensorToSave.name != cachedData.name) {
                val cachedDataStillFresh =
                    isFromToday(cachedData.timestamp) &&
                        cachedData.primaryAqi != null &&
                        dataAgeMs(cachedData.timestamp, nowMs) < RETRY_STALE_DATA_THRESHOLD_MS
                if (cachedDataStillFresh) {
                    AppLog.d(
                        "AqiRepository",
                        "Nearest sensor changed from ${cachedData.name} to ${sensorToSave.name}. " +
                            "Keeping cached ${cachedData.name} (still today's data)."
                    )
                    return retryIfStale(
                        sensorData = cachedData,
                        nowMs = nowMs,
                        didSaveData = false,
                        reason = "Keeping sticky cached sensor until the expected hourly update arrives."
                    ) ?: success()
                }
            }

            if (!isFromToday(sensorToSave.timestamp)) {
                AppLog.w(
                    "AqiRepository",
                    "Selected fallback sensor ${sensorToSave.name} is not from today."
                )
            }

            AppLog.d("AqiRepository", "Selected sensor: ${sensorToSave.name} with AQI: ${sensorToSave.primaryAqi}")
            return saveAndReturnFreshnessOutcome(
                sensorData = sensorToSave,
                nowMs = nowMs,
                reason = "Selected sensor is still older than the expected hourly freshness window."
            )
        } catch (e: Exception) {
            AppLog.e("AqiRepository", "Error syncing data [${e.javaClass.simpleName}]: ${e.message}", e)
            return retry()
        }
    }

    /** Sync forecast data. Throttled to once per 24 hours. */
    suspend fun syncForecast() {
        try {
            val snapshot = prefs.readSnapshot()
            val lastSync = snapshot.lastForecastSync
            val hoursSinceLast = (System.currentTimeMillis() - lastSync) / 3_600_000L
            if (hoursSinceLast < 24) {
                AppLog.d("AqiRepository", "Forecast sync skipped — last sync ${hoursSinceLast}h ago")
                return
            }

            AppLog.d("AqiRepository", "Fetching forecast data...")
            val forecastPayload = withContext(Dispatchers.IO) { api.getForecastData() }

            if (forecastPayload.locations.isEmpty()) {
                AppLog.w("AqiRepository", "No forecast locations in payload")
                return
            }

            // Try to match by current sensor name
            val currentSensor = snapshot.latestSensorData
            val matched = if (currentSensor != null) {
                forecastPayload.locations.find { it.name == currentSensor.name }
                    ?: findNearestForecastLocation(currentSensor.lat, currentSensor.lon, forecastPayload.locations)
            } else {
                // No sensor yet — try device location
                val cacheMinutes = snapshot.locationCacheMinutes
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
        return locations.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }
    }
}
