package com.example.aqi

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.aqi.data.aqiPrefs
import com.example.aqi.worker.SyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class AqiComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return createComplicationData(45, "PM2.5", type)
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        CoroutineScope(Dispatchers.IO).launch {
            SyncWorkScheduler.enqueueIfStale(
                applicationContext,
                "complication_activated:$complicationInstanceId:$type",
                manageRetryAlarm = true
            )
        }
        AppLog.d(
            "AqiComplicationService",
            "Complication activated. id=$complicationInstanceId type=$type"
        )
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        AppLog.d(
            "AqiComplicationService",
            "onComplicationRequest id=${request.complicationInstanceId} type=${request.complicationType}"
        )

        val prefs = applicationContext.aqiPrefs
        val snapshot = prefs.complicationSnapshot ?: runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(300) { prefs.awaitComplicationSnapshot() }
        }
        AppLog.d(
            "AqiComplSvc",
            "onRequest: snapshot=${snapshot != null} memory=${prefs.complicationSnapshot != null}"
        )

        val complicationData = if (snapshot == null) {
            AppLog.d("AqiComplSvc", "No snapshot available for complication request")
            createNoDataComplication(request.complicationType)
        } else {
            val dataAgeMin = (System.currentTimeMillis() - snapshot.timestamp * 1000) / 60_000
            AppLog.d(
                "AqiComplSvc",
                "Serving: sensor=${snapshot.sensorName} age=${dataAgeMin}min metric=${snapshot.metric} value=${snapshot.value}"
            )
            createComplicationData(snapshot.value, snapshot.metric, request.complicationType)
        }

        complicationData?.let(listener::onComplicationData)
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
                RangedValueComplicationData.Builder(
                    value = 0f,
                    min = 0f,
                    max = 1f,
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
