package com.example.aqi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aqi.AqiApplication
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aqi_prefs")

/** Get the singleton AqiPreferences from any Context. */
val Context.aqiPrefs: AqiPreferences
    get() = (applicationContext as AqiApplication).prefs

class AqiPreferences(private val context: Context) {

    companion object {
        private val gson = Gson()
        val LATEST_SENSOR_DATA_KEY = stringPreferencesKey("latest_sensor_data_json")
        val LOCATION_CACHE_MINUTES_KEY = intPreferencesKey("location_cache_minutes")
    }

    val latestSensorDataFlow: Flow<SensorData?> = context.dataStore.data.map { prefs ->
        val json = prefs[LATEST_SENSOR_DATA_KEY]
        if (json != null) {
            gson.fromJson(json, SensorData::class.java)
        } else {
            null
        }
    }

    val locationCacheMinutesFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LOCATION_CACHE_MINUTES_KEY] ?: 60 // Default to 60 minutes
    }

    suspend fun getLocationCacheMinutes(): Int {
        val prefs = context.dataStore.data.first()
        return prefs[LOCATION_CACHE_MINUTES_KEY] ?: 60
    }

    suspend fun setLocationCacheMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOCATION_CACHE_MINUTES_KEY] = minutes
        }
    }

    suspend fun getLatestSensorData(): SensorData? {
        val prefs = context.dataStore.data.first()
        val json = prefs[LATEST_SENSOR_DATA_KEY]
        return json?.let { gson.fromJson(it, SensorData::class.java) }
    }

    suspend fun saveLatestSensorData(data: SensorData) {
        val json = gson.toJson(data)
        context.dataStore.edit { prefs ->
            prefs[LATEST_SENSOR_DATA_KEY] = json
        }
    }
}
