package com.example.aqi.worker

import android.content.Context
import com.example.aqi.AppLog
import com.example.aqi.ComplicationUpdateDispatcher
import com.example.aqi.SyncDiagnostics
import com.example.aqi.bedtime.BedtimeModeHelper
import com.example.aqi.data.AqiRepository
import com.example.aqi.data.aqiPrefs
import com.example.aqi.receiver.SyncAlarmScheduler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncRunner {
    const val SCHEDULED_SYNC_THRESHOLD_MS = 60 * 60 * 1000L

    private val syncMutex = Mutex()

    suspend fun runIfStale(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean = false,
        allowDuringBedtime: Boolean = false,
        freshnessThresholdMs: Long = SCHEDULED_SYNC_THRESHOLD_MS
    ): AqiRepository.SyncOutcome? {
        return syncMutex.withLock {
            val appContext = context.applicationContext
            bedtimeSkipOutcome(
                context = appContext,
                triggerReason = triggerReason,
                manageRetryAlarm = manageRetryAlarm,
                allowDuringBedtime = allowDuringBedtime
            )?.let { return it }

            val prefs = appContext.aqiPrefs
            val now = System.currentTimeMillis()
            val snapshot = prefs.readSnapshot()
            val sensorData = snapshot.latestSensorData
            if (sensorData != null) {
                val dataAgeMs = now - (sensorData.timestamp * 1000)
                if (dataAgeMs < freshnessThresholdMs) {
                    AppLog.d(
                        "SyncRunner",
                        "runIfStale skipped: data only ${dataAgeMs / 60_000}min old (<${freshnessThresholdMs / 60_000} min). reason=$triggerReason"
                    )
                    SyncDiagnostics.record(
                        appContext,
                        category = "sync_skipped",
                        triggerReason = triggerReason,
                        details = "kind=data_fresh dataAgeMin=${dataAgeMs / 60_000} thresholdMin=${freshnessThresholdMs / 60_000}"
                    )
                    return null
                }
            }

            val dataAgeMin = if (sensorData != null) (now - sensorData.timestamp * 1000) / 60_000 else -1
            AppLog.d(
                "SyncRunner",
                "runIfStale: data is ${dataAgeMin}min old (threshold=${freshnessThresholdMs / 60_000}min). Triggering sync. reason=$triggerReason"
            )

            return runNowLocked(appContext, triggerReason, manageRetryAlarm)
        }
    }

    suspend fun runNow(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean = false,
        allowDuringBedtime: Boolean = false
    ): AqiRepository.SyncOutcome {
        return syncMutex.withLock {
            val appContext = context.applicationContext
            bedtimeSkipOutcome(
                context = appContext,
                triggerReason = triggerReason,
                manageRetryAlarm = manageRetryAlarm,
                allowDuringBedtime = allowDuringBedtime
            ) ?: runNowLocked(appContext, triggerReason, manageRetryAlarm)
        }
    }

    private fun bedtimeSkipOutcome(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean,
        allowDuringBedtime: Boolean
    ): AqiRepository.SyncOutcome? {
        if (allowDuringBedtime || !BedtimeModeHelper.isBedtimeModeActive(context)) {
            return null
        }

        AppLog.i("SyncRunner", "Skipping sync during bedtime. reason=$triggerReason")
        SyncDiagnostics.record(
            context,
            category = "sync_skipped",
            triggerReason = triggerReason,
            details = "kind=bedtime manageRetryAlarm=$manageRetryAlarm"
        )
        if (manageRetryAlarm) {
            SyncAlarmScheduler.cancelAlarm(context)
            SyncAlarmScheduler.cancelRetryAlarm(context)
        }

        return AqiRepository.SyncOutcome(
            shouldRetry = false,
            didSaveData = false
        )
    }

    private suspend fun runNowLocked(
        context: Context,
        triggerReason: String,
        manageRetryAlarm: Boolean
    ): AqiRepository.SyncOutcome {
        AppLog.d("SyncRunner", "Starting sync. reason=$triggerReason")
        SyncDiagnostics.record(
            context,
            category = "sync_started",
            triggerReason = triggerReason,
            details = "manageRetryAlarm=$manageRetryAlarm"
        )

        val repo = AqiRepository(context)
        val startMs = System.currentTimeMillis()
        val outcome = repo.syncData()
        val elapsedMs = System.currentTimeMillis() - startMs
        AppLog.d(
            "SyncRunner",
            "syncData(retry=${outcome.shouldRetry}, saved=${outcome.didSaveData}) in ${elapsedMs}ms reason=$triggerReason"
        )
        SyncDiagnostics.record(
            context,
            category = "sync_finished",
            triggerReason = triggerReason,
            details = "retry=${outcome.shouldRetry} saved=${outcome.didSaveData} elapsedMs=$elapsedMs"
        )

        context.aqiPrefs.setLastSyncAttempt(System.currentTimeMillis())

        if (!outcome.shouldRetry || outcome.didSaveData) {
            coroutineScope {
                if (!outcome.shouldRetry) {
                    launch { repo.syncForecast() }
                }

                ComplicationUpdateDispatcher.requestAll(context, triggerReason)
            }
        }

        if (outcome.shouldRetry) {
            if (BedtimeModeHelper.isBedtimeModeActive(context)) {
                AppLog.w("SyncRunner", "Data still stale after fetch, but retry suppressed during bedtime.")
            } else {
                AppLog.w("SyncRunner", "Data still stale after fetch. Scheduling 15-minute retry.")
            }
        }

        return outcome
    }
}
