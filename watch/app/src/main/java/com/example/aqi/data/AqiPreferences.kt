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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        val COMPLICATION_VALUE_KEY = intPreferencesKey("complication_value")
        val COMPLICATION_METRIC_KEY = stringPreferencesKey("complication_metric")
        val COMPLICATION_SENSOR_NAME_KEY = stringPreferencesKey("complication_sensor_name")
        val COMPLICATION_TIMESTAMP_KEY = longPreferencesKey("complication_timestamp")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialLoad = CompletableDeferred<Unit>()
    private val _snapshotState = MutableStateFlow(defaultSnapshot())
    private val complicationPrefs = context.getSharedPreferences("aqi_complication_snapshot", Context.MODE_PRIVATE)

    val snapshotState: StateFlow<Snapshot> = _snapshotState

    val latestSensorDataFlow: Flow<SensorData?> = snapshotState
        .map { it.latestSensorData }
        .distinctUntilChanged()

    val locationCacheMinutesFlow: Flow<Int> = snapshotState
        .map { it.locationCacheMinutes }
        .distinctUntilChanged()

    val forecastDataFlow: Flow<List<ForecastDay>?> = snapshotState
        .map { it.forecastData }
        .distinctUntilChanged()

    val cachedSensorData: SensorData?
        get() = if (initialLoad.isCompleted) snapshotState.value.latestSensorData else null

    val complicationSnapshot: ComplicationSnapshot?
        get() = if (initialLoad.isCompleted) {
            snapshotState.value.complicationSnapshot ?: readComplicationSnapshotFromHotPrefs()
        } else {
            readComplicationSnapshotFromHotPrefs()
        }

    init {
        scope.launch {
            context.dataStore.data.collect { prefs ->
                val snapshot = prefs.toSnapshot()
                _snapshotState.value = snapshot
                saveComplicationSnapshotToHotPrefs(snapshot.complicationSnapshot)
                if (!initialLoad.isCompleted) {
                    initialLoad.complete(Unit)
                }
            }
        }
    }

    /** Batch-read all prefs in a single DataStore access. */
    suspend fun readSnapshot(): Snapshot {
        initialLoad.await()
        return snapshotState.value
    }

    suspend fun awaitComplicationSnapshot(): ComplicationSnapshot? {
        complicationSnapshot?.let { return it }
        initialLoad.await()
        return complicationSnapshot
    }

    suspend fun getLocationCacheMinutes(): Int {
        return readSnapshot().locationCacheMinutes
    }

    suspend fun setLocationCacheMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOCATION_CACHE_MINUTES_KEY] = minutes
        }
        _snapshotState.update { it.copy(locationCacheMinutes = minutes) }
    }

    suspend fun getLatestSensorData(): SensorData? {
        return readSnapshot().latestSensorData
    }

    suspend fun saveLatestSensorData(data: SensorData) {
        val complicationSnapshot = data.toComplicationSnapshot()
        val json = gson.toJson(data)
        context.dataStore.edit { prefs ->
            prefs[LATEST_SENSOR_DATA_KEY] = json
            if (complicationSnapshot == null) {
                prefs.remove(COMPLICATION_VALUE_KEY)
                prefs.remove(COMPLICATION_METRIC_KEY)
                prefs.remove(COMPLICATION_SENSOR_NAME_KEY)
                prefs.remove(COMPLICATION_TIMESTAMP_KEY)
            } else {
                prefs[COMPLICATION_VALUE_KEY] = complicationSnapshot.value
                prefs[COMPLICATION_METRIC_KEY] = complicationSnapshot.metric
                prefs[COMPLICATION_SENSOR_NAME_KEY] = complicationSnapshot.sensorName
                prefs[COMPLICATION_TIMESTAMP_KEY] = complicationSnapshot.timestamp
            }
        }
        saveComplicationSnapshotToHotPrefs(complicationSnapshot)
        _snapshotState.update {
            it.copy(
                latestSensorData = data,
                complicationSnapshot = complicationSnapshot
            )
        }
    }

    suspend fun getLastSyncAttempt(): Long {
        return readSnapshot().lastSyncAttempt
    }

    suspend fun setLastSyncAttempt(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SYNC_ATTEMPT_KEY] = timeMillis
        }
        _snapshotState.update { it.copy(lastSyncAttempt = timeMillis) }
    }

    suspend fun saveForecastData(locationName: String, forecasts: List<ForecastDay>) {
        val lastForecastSync = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            prefs[FORECAST_DATA_KEY] = gson.toJson(forecasts)
            prefs[FORECAST_LOCATION_KEY] = locationName
            prefs[LAST_FORECAST_SYNC_KEY] = lastForecastSync
        }
        _snapshotState.update {
            it.copy(
                forecastLocationName = locationName,
                forecastData = forecasts,
                lastForecastSync = lastForecastSync
            )
        }
    }

    suspend fun getForecastData(): Pair<String, List<ForecastDay>>? {
        val snapshot = readSnapshot()
        val name = snapshot.forecastLocationName ?: return null
        val forecastData = snapshot.forecastData ?: return null
        return name to forecastData
    }

    suspend fun getLastForecastSync(): Long {
        return readSnapshot().lastForecastSync
    }

    private fun defaultSnapshot() = Snapshot(
        latestSensorData = null,
        complicationSnapshot = null,
        locationCacheMinutes = 60,
        lastSyncAttempt = 0L,
        forecastLocationName = null,
        forecastData = null,
        lastForecastSync = 0L
    )

    private fun Preferences.toSnapshot(): Snapshot {
        val latestSensorData = this[LATEST_SENSOR_DATA_KEY]?.let {
            gson.fromJson(it, SensorData::class.java)
        }
        val complicationSnapshot = complicationSnapshotFromPrefs(this)
            ?: latestSensorData?.toComplicationSnapshot()

        return Snapshot(
            latestSensorData = latestSensorData,
            complicationSnapshot = complicationSnapshot,
            locationCacheMinutes = this[LOCATION_CACHE_MINUTES_KEY] ?: 60,
            lastSyncAttempt = this[LAST_SYNC_ATTEMPT_KEY] ?: 0L,
            forecastLocationName = this[FORECAST_LOCATION_KEY],
            forecastData = this[FORECAST_DATA_KEY]?.let {
                gson.fromJson<List<ForecastDay>>(it, forecastListType)
            },
            lastForecastSync = this[LAST_FORECAST_SYNC_KEY] ?: 0L
        )
    }

    private fun complicationSnapshotFromPrefs(prefs: Preferences): ComplicationSnapshot? {
        val value = prefs[COMPLICATION_VALUE_KEY] ?: return null
        val metric = prefs[COMPLICATION_METRIC_KEY] ?: return null
        val sensorName = prefs[COMPLICATION_SENSOR_NAME_KEY] ?: return null
        val timestamp = prefs[COMPLICATION_TIMESTAMP_KEY] ?: return null
        return ComplicationSnapshot(
            value = value,
            metric = metric,
            sensorName = sensorName,
            timestamp = timestamp
        )
    }

    private fun readComplicationSnapshotFromHotPrefs(): ComplicationSnapshot? {
        if (!complicationPrefs.contains("value")) return null

        val metric = complicationPrefs.getString("metric", null) ?: return null
        val sensorName = complicationPrefs.getString("sensor_name", null) ?: return null
        val timestamp = complicationPrefs.getLong("timestamp", 0L)
        if (timestamp == 0L) return null

        return ComplicationSnapshot(
            value = complicationPrefs.getInt("value", 0),
            metric = metric,
            sensorName = sensorName,
            timestamp = timestamp
        )
    }

    private fun saveComplicationSnapshotToHotPrefs(snapshot: ComplicationSnapshot?) {
        complicationPrefs.edit().apply {
            if (snapshot == null) {
                clear()
            } else {
                putInt("value", snapshot.value)
                putString("metric", snapshot.metric)
                putString("sensor_name", snapshot.sensorName)
                putLong("timestamp", snapshot.timestamp)
            }
        }.apply()
    }

    private fun SensorData.toComplicationSnapshot(): ComplicationSnapshot? {
        val worstMetric = metrics.maxByOrNull { it.value } ?: return null
        return ComplicationSnapshot(
            value = worstMetric.value,
            metric = worstMetric.key,
            sensorName = name,
            timestamp = timestamp
        )
    }

    data class ComplicationSnapshot(
        val value: Int,
        val metric: String,
        val sensorName: String,
        val timestamp: Long
    )

    data class Snapshot(
        val latestSensorData: SensorData?,
        val complicationSnapshot: ComplicationSnapshot?,
        val locationCacheMinutes: Int,
        val lastSyncAttempt: Long,
        val forecastLocationName: String?,
        val forecastData: List<ForecastDay>?,
        val lastForecastSync: Long
    )
}
