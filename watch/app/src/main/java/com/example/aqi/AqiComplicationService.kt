package com.example.aqi

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.aqi.data.aqiPrefs
import kotlinx.coroutines.flow.first

class AqiComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return createComplicationData(45, "PM2.5", type)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = applicationContext.aqiPrefs
        val data = prefs.latestSensorDataFlow.first()

        if (data == null || data.metrics.isEmpty()) {
            return createNoDataComplication(request.complicationType)
        }

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
                    max = 500f,
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
