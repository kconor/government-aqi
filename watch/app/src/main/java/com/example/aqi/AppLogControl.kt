package com.example.aqi

import android.content.Context

object AppLogControl {
    private const val PREFS_NAME = "aqi_release_log_control"
    private const val KEY_ENABLED_UNTIL_MS = "enabled_until_ms"

    @Volatile
    private var enabledUntilMs: Long = 0L

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabledUntilMs = prefs.getLong(KEY_ENABLED_UNTIL_MS, 0L)
    }

    fun isEnabled(): Boolean = System.currentTimeMillis() < enabledUntilMs

    fun enableFor(context: Context, durationMs: Long): Long {
        val now = System.currentTimeMillis()
        val requestedUntil = now + durationMs
        val newUntil = maxOf(enabledUntilMs, requestedUntil)
        enabledUntilMs = newUntil
        persist(context, newUntil)
        return newUntil
    }

    fun disable(context: Context) {
        enabledUntilMs = 0L
        persist(context, 0L)
    }

    fun remainingMs(): Long = (enabledUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun persist(context: Context, enabledUntilMs: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ENABLED_UNTIL_MS, enabledUntilMs)
            .apply()
    }
}
