package com.example.aqi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.example.aqi.AppLog
import com.example.aqi.AppLogControl
import com.example.aqi.AqiDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isShellOrRootSender()) {
            AppLog.w(
                "AdbControlReceiver",
                "Ignoring non-adb broadcast action=${intent.action} sdk=${Build.VERSION.SDK_INT} sentFromUid=$sentFromUid"
            )
            return
        }

        when (intent.action) {
            ACTION_ENABLE_RELEASE_LOGS -> enableReleaseLogs(context, intent)
            ACTION_DISABLE_RELEASE_LOGS -> disableReleaseLogs(context)
            ACTION_DUMP_SYNC_STATE -> dumpSyncState(context, intent)
            else -> AppLog.w("AdbControlReceiver", "Unknown action=${intent.action}")
        }
    }

    private fun enableReleaseLogs(context: Context, intent: Intent) {
        val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, DEFAULT_DURATION_MINUTES)
            .coerceIn(1, MAX_DURATION_MINUTES)
        val enabledUntil = AppLogControl.enableFor(context, durationMinutes * 60_000L)
        AppLog.i(
            "AdbControlReceiver",
            "Release logging enabled for ${durationMinutes}m until=$enabledUntil"
        )
    }

    private fun disableReleaseLogs(context: Context) {
        AppLog.i("AdbControlReceiver", "Release logging disabled")
        AppLogControl.disable(context)
    }

    private fun dumpSyncState(context: Context, intent: Intent) {
        val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, DUMP_DURATION_MINUTES)
            .coerceIn(1, MAX_DURATION_MINUTES)
        AppLogControl.enableFor(context, durationMinutes * 60_000L)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AqiDiagnostics.dumpState(context, "adb_dump")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isShellOrRootSender(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }

        return when (sentFromUid) {
            Process.SHELL_UID,
            Process.ROOT_UID,
            Process.SYSTEM_UID,
            -1 -> true
            else -> false
        }
    }

    companion object {
        const val ACTION_ENABLE_RELEASE_LOGS = "com.example.aqi.action.ENABLE_RELEASE_LOGS"
        const val ACTION_DISABLE_RELEASE_LOGS = "com.example.aqi.action.DISABLE_RELEASE_LOGS"
        const val ACTION_DUMP_SYNC_STATE = "com.example.aqi.action.DUMP_SYNC_STATE"

        const val EXTRA_DURATION_MINUTES = "duration_minutes"

        private const val DEFAULT_DURATION_MINUTES = 10
        private const val DUMP_DURATION_MINUTES = 2
        private const val MAX_DURATION_MINUTES = 60
    }
}
