package com.example.aqi.worker

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.aqi.AqiComplicationService
import com.example.aqi.AppLog
import com.example.aqi.data.AqiRepository
import com.example.aqi.data.aqiPrefs
import com.example.aqi.receiver.SyncAlarmScheduler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncRunner {
    private const val MIN_SYNC_INTERVAL_MS = 15 * 60 * 1000L
    const val STALE_DATA_THRESHOLD_MS = 90 * 60 * 1000L

    private val syncMutex = Mutex()

    suspend fun runIfStale(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean = false
    ): AqiRepository.SyncOutcome? {
        return syncMutex.withLock {
            val appContext = context.applicationContext
            val prefs = appContext.aqiPrefs
            val now = System.currentTimeMillis()
            val snapshot = prefs.readSnapshot()
            val lastAttemptAgeMs = now - snapshot.lastSyncAttempt
            if (lastAttemptAgeMs < MIN_SYNC_INTERVAL_MS) {
                AppLog.d(
                    "SyncRunner",
                    "runIfStale skipped: last attempt ${lastAttemptAgeMs / 1000}s ago (<15 min). reason=$triggerReason"
                )
                return null
            }

            val sensorData = snapshot.latestSensorData
            if (sensorData != null) {
                val dataAgeMs = now - (sensorData.timestamp * 1000)
                if (dataAgeMs < STALE_DATA_THRESHOLD_MS) {
                    AppLog.d(
                        "SyncRunner",
                        "runIfStale skipped: data only ${dataAgeMs / 60_000}min old (<90 min). reason=$triggerReason"
                    )
                    if (manageRetryAlarm) {
                        SyncAlarmScheduler.cancelRetryAlarm(appContext)
                    }
                    return null
                }
            }

            val dataAgeMin = if (sensorData != null) (now - sensorData.timestamp * 1000) / 60_000 else -1
            AppLog.d(
                "SyncRunner",
                "runIfStale: data is ${dataAgeMin}min old (threshold=${STALE_DATA_THRESHOLD_MS / 60_000}min). Triggering sync. reason=$triggerReason"
            )

            return runNowLocked(appContext, triggerReason, manageRetryAlarm)
        }
    }

    suspend fun runNow(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean = false
    ): AqiRepository.SyncOutcome {
        return syncMutex.withLock {
            runNowLocked(context.applicationContext, triggerReason, manageRetryAlarm)
        }
    }

    private suspend fun runNowLocked(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean
    ): AqiRepository.SyncOutcome {
        AppLog.d("SyncRunner", "Starting sync. reason=$triggerReason")

        val repo = AqiRepository(context)
        val startMs = System.currentTimeMillis()
        val outcome = repo.syncData()
        val elapsedMs = System.currentTimeMillis() - startMs
        AppLog.d(
            "SyncRunner",
            "syncData(retry=${outcome.shouldRetry}, saved=${outcome.didSaveData}) in ${elapsedMs}ms reason=$triggerReason"
        )

        context.aqiPrefs.setLastSyncAttempt(System.currentTimeMillis())

        if (!outcome.shouldRetry || outcome.didSaveData) {
            coroutineScope {
                if (!outcome.shouldRetry) {
                    launch { repo.syncForecast() }
                }

                val componentName = ComponentName(context, AqiComplicationService::class.java)
                val updateRequester = ComplicationDataSourceUpdateRequester.create(context, componentName)
                updateRequester.requestUpdateAll()
                AppLog.d("SyncRunner", "requestUpdateAll() called")
            }
        }

        if (manageRetryAlarm) {
            if (outcome.shouldRetry) {
                SyncAlarmScheduler.scheduleRetryAlarm(context)
            } else {
                SyncAlarmScheduler.cancelRetryAlarm(context)
            }
        }

        if (outcome.shouldRetry) {
            AppLog.w("SyncRunner", "Data still stale after fetch. Scheduling 15-minute retry.")
        }

        return outcome
    }
}
