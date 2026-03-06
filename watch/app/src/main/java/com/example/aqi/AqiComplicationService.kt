package com.example.aqi

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.aqi.data.aqiPrefs
import com.example.aqi.worker.SyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AqiComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return createComplicationData(45, "PM2.5", type)
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        CoroutineScope(Dispatchers.IO).launch {
            SyncWorkScheduler.enqueueIfStale(
                applicationContext,
                "complication_activated:$complicationInstanceId:$type"
            )
        }
        AppLog.d(
            "AqiComplicationService",
            "Complication activated. id=$complicationInstanceId type=$type"
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        AppLog.d(
            "AqiComplicationService",
            "onComplicationRequest id=${request.complicationInstanceId} type=${request.complicationType}"
        )

        val prefs = applicationContext.aqiPrefs
        val cached = prefs.cachedSensorData
        val data = cached ?: prefs.getLatestSensorData()
        AppLog.d(
            "AqiComplSvc",
            "onRequest: cached=${cached != null} data=${data != null} metrics=${data?.metrics?.size}"
        )

        if (data == null || data.metrics.isEmpty()) {
            AppLog.d("AqiComplSvc", "No data or empty metrics")
            return createNoDataComplication(request.complicationType)
        }

        val dataAgeMin = (System.currentTimeMillis() - data.timestamp * 1000) / 60_000
        AppLog.d("AqiComplSvc", "Serving: sensor=${data.name} age=${dataAgeMin}min aqi=${data.primaryAqi}")

        // Find the pollutant with the highest AQI value
        val worst = data.metrics.maxByOrNull { it.value }
        if (worst == null) {
            return createNoDataComplication(request.complicationType)
        }

        return createComplicationData(worst.value, worst.key, request.complicationType)
    }

    private fun createNoDataComplication(type: ComplicationType): ComplicationData? {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = PlainComplicationText.Builder("--").build()
        val title = PlainComplicationText.Builder("AQI").build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(text, text)
                    .setTitle(title)
                    .setTapAction(tapPendingIntent)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                // Show as SHORT_TEXT to avoid a misleading gauge at 0
                ShortTextComplicationData.Builder(text, text)
                    .setTitle(title)
                    .setTapAction(tapPendingIntent)
                    .build()
            }
            else -> null
        }
    }

    private fun createComplicationData(value: Int, metric: String, type: ComplicationType): ComplicationData? {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val textStr = if (metric == "--") "--" else value.toString()
        val text = PlainComplicationText.Builder(textStr).build()
        val title = PlainComplicationText.Builder(metric).build()
        
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(text, text)
                    .setTitle(title)
                    .setTapAction(tapPendingIntent)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = value.toFloat(),
                    min = 0f,
                    max = 50f,
                    contentDescription = text
                )
                    .setText(text)
                    .setTitle(title)
                    .setTapAction(tapPendingIntent)
                    .build()
            }
            else -> null
        }
    }
}
