package com.example.aqi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aqi.AqiApplication
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aqi_prefs")

/** Get the singleton AqiPreferences from any Context. */
val Context.aqiPrefs: AqiPreferences
    get() = (applicationContext as AqiApplication).prefs

class AqiPreferences(private val context: Context) {

    companion object {
        private val gson = Gson()
        private val forecastListType = object : TypeToken<List<ForecastDay>>() {}.type
        val LATEST_SENSOR_DATA_KEY = stringPreferencesKey("latest_sensor_data_json")
        val LOCATION_CACHE_MINUTES_KEY = intPreferencesKey("location_cache_minutes")
        val LAST_SYNC_ATTEMPT_KEY = longPreferencesKey("last_sync_attempt")
        val FORECAST_DATA_KEY = stringPreferencesKey("forecast_data_json")
        val FORECAST_LOCATION_KEY = stringPreferencesKey("forecast_location_name")
        val LAST_FORECAST_SYNC_KEY = longPreferencesKey("last_forecast_sync")
    }

    /** In-memory cache for non-suspending reads (e.g. complication service). */
    @Volatile
    var cachedSensorData: SensorData? = null
        private set

    val latestSensorDataFlow: Flow<SensorData?> = context.dataStore.data
        .map { prefs -> prefs[LATEST_SENSOR_DATA_KEY] }
        .distinctUntilChanged()
        .map { json -> json?.let { gson.fromJson(it, SensorData::class.java) } }

    val locationCacheMinutesFlow: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[LOCATION_CACHE_MINUTES_KEY] ?: 60 }

    val forecastDataFlow: Flow<List<ForecastDay>?> = context.dataStore.data
        .map { prefs -> prefs[FORECAST_DATA_KEY] }
        .distinctUntilChanged()
        .map { json -> json?.let { gson.fromJson<List<ForecastDay>>(it, forecastListType) } }

    /** Batch-read all prefs in a single DataStore access. */
    suspend fun readSnapshot(): Snapshot {
        val prefs = context.dataStore.data.first()
        return Snapshot(
            latestSensorData = prefs[LATEST_SENSOR_DATA_KEY]?.let { gson.fromJson(it, SensorData::class.java) },
            locationCacheMinutes = prefs[LOCATION_CACHE_MINUTES_KEY] ?: 60,
            lastSyncAttempt = prefs[LAST_SYNC_ATTEMPT_KEY] ?: 0L,
            forecastLocationName = prefs[FORECAST_LOCATION_KEY],
            forecastData = prefs[FORECAST_DATA_KEY]?.let { gson.fromJson<List<ForecastDay>>(it, forecastListType) },
            lastForecastSync = prefs[LAST_FORECAST_SYNC_KEY] ?: 0L
        )
    }

    suspend fun getLocationCacheMinutes(): Int {
        return context.dataStore.data.first()[LOCATION_CACHE_MINUTES_KEY] ?: 60
    }

    suspend fun setLocationCacheMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOCATION_CACHE_MINUTES_KEY] = minutes
        }
    }

    suspend fun getLatestSensorData(): SensorData? {
        val json = context.dataStore.data.first()[LATEST_SENSOR_DATA_KEY] ?: return null
        return gson.fromJson(json, SensorData::class.java)
    }

    suspend fun saveLatestSensorData(data: SensorData) {
        cachedSensorData = data
        val json = gson.toJson(data)
        context.dataStore.edit { prefs ->
            prefs[LATEST_SENSOR_DATA_KEY] = json
        }
    }

    suspend fun getLastSyncAttempt(): Long {
        return context.dataStore.data.first()[LAST_SYNC_ATTEMPT_KEY] ?: 0L
    }

    suspend fun setLastSyncAttempt(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SYNC_ATTEMPT_KEY] = timeMillis
        }
    }

    suspend fun saveForecastData(locationName: String, forecasts: List<ForecastDay>) {
        context.dataStore.edit { prefs ->
            prefs[FORECAST_DATA_KEY] = gson.toJson(forecasts)
            prefs[FORECAST_LOCATION_KEY] = locationName
            prefs[LAST_FORECAST_SYNC_KEY] = System.currentTimeMillis()
        }
    }

    suspend fun getForecastData(): Pair<String, List<ForecastDay>>? {
        val prefs = context.dataStore.data.first()
        val name = prefs[FORECAST_LOCATION_KEY] ?: return null
        val json = prefs[FORECAST_DATA_KEY] ?: return null
        return name to gson.fromJson(json, forecastListType)
    }

    suspend fun getLastForecastSync(): Long {
        return context.dataStore.data.first()[LAST_FORECAST_SYNC_KEY] ?: 0L
    }

    data class Snapshot(
        val latestSensorData: SensorData?,
        val locationCacheMinutes: Int,
        val lastSyncAttempt: Long,
        val forecastLocationName: String?,
        val forecastData: List<ForecastDay>?,
        val lastForecastSync: Long
    )
}
