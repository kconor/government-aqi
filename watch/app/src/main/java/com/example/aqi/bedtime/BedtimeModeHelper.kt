package com.example.aqi.bedtime

import android.app.NotificationManager
import android.content.Context

object BedtimeModeHelper {
    fun hasPolicyAccess(context: Context): Boolean {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        return notificationManager?.isNotificationPolicyAccessGranted == true
    }

    fun isBedtimeModeActive(context: Context): Boolean {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        return when (notificationManager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            else -> false
        }
    }
}
