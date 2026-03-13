package com.example.aqi

import android.content.Context
import androidx.annotation.Keep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Keep
object SyncDiagnostics {
    private const val PREFS_NAME = "aqi_sync_diagnostics"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 48
    private const val DUMP_ENTRIES = 12

    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun record(
        context: Context,
        category: String,
        triggerReason: String? = null,
        details: String? = null
    ) {
        val entry = buildString {
            append(formatter.format(Instant.now()))
            append(" ")
            append(category)
            if (!triggerReason.isNullOrBlank()) {
                append(" reason=")
                append(triggerReason)
            }
            if (!details.isNullOrBlank()) {
                append(" ")
                append(details)
            }
        }

        synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_ENTRIES, "").orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toMutableList()
            existing.add(entry)
            prefs.edit()
                .putString(KEY_ENTRIES, existing.takeLast(MAX_ENTRIES).joinToString("\n"))
                .apply()
        }

        AppLog.i("SyncTrace", entry)
    }

    fun dumpRecent(context: Context, trigger: String) {
        val entries: List<String> = synchronized(this) {
            val allEntries = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, "")
                .orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            allEntries.takeLast(DUMP_ENTRIES)
        }

        if (entries.isEmpty()) {
            AppLog.i("SyncTrace", "history empty trigger=$trigger")
            return
        }

        AppLog.i("SyncTrace", "history dump trigger=$trigger count=${entries.size}")
        entries.forEachIndexed { index, entry ->
            AppLog.i("SyncTrace", "#${index + 1} $entry")
        }
    }
}
