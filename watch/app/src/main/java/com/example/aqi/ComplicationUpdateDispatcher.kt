package com.example.aqi

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

object ComplicationUpdateDispatcher {
    fun requestAll(context: Context, reason: String) {
        val appContext = context.applicationContext
        val componentName = ComponentName(appContext, AqiComplicationService::class.java)
        val updateRequester = ComplicationDataSourceUpdateRequester.create(appContext, componentName)
        updateRequester.requestUpdateAll()
        AppLog.d("ComplicationUpdate", "requestUpdateAll() reason=$reason")
        SyncDiagnostics.record(
            appContext,
            category = "complication_update_requested",
            triggerReason = reason
        )
    }
}
