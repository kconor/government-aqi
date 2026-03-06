package com.example.aqi

import android.util.Log
import androidx.annotation.Keep

@Keep
object AppLog {
    private const val GLOBAL_OVERRIDE_TAG = "AQI"

    private fun globalOverrideEnabled(priority: Int): Boolean {
        return Log.isLoggable(GLOBAL_OVERRIDE_TAG, priority)
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val useGlobalOverride = globalOverrideEnabled(priority)
        val useOriginalTag = BuildConfig.DEBUG || Log.isLoggable(tag, priority)
        if (!useGlobalOverride && !useOriginalTag) return

        val outputTag = if (useGlobalOverride) GLOBAL_OVERRIDE_TAG else tag
        val outputMessage = if (useGlobalOverride) "[$tag] $message" else message

        if (throwable == null) {
            Log.println(priority, outputTag, outputMessage)
        } else {
            Log.println(priority, outputTag, "$outputMessage\n${Log.getStackTraceString(throwable)}")
        }
    }

    fun d(tag: String, message: String) {
        log(Log.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        log(Log.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        log(Log.WARN, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Log.ERROR, tag, message, throwable)
    }
}
