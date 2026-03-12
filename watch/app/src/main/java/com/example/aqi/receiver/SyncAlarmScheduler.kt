package com.example.aqi.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.aqi.AppLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

object SyncAlarmScheduler {
    const val ACTION_HOURLY_SYNC = "com.example.aqi.action.HOURLY_SYNC"
    const val ACTION_RETRY_SYNC = "com.example.aqi.action.RETRY_SYNC"

    private const val HOURLY_ALARM_REQUEST_CODE = 1001
    private const val RETRY_ALARM_REQUEST_CODE = 1002
    private const val RETRY_DELAY_MS = 15 * 60 * 1000L

    fun scheduleAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = makePendingIntent(
            context = context,
            action = ACTION_HOURLY_SYNC,
            requestCode = HOURLY_ALARM_REQUEST_CODE
        )

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default"
        val jitterSeconds = abs(androidId.hashCode()) % 60

        val now = Calendar.getInstance()
        val trigger = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, jitterSeconds)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) {
                add(Calendar.HOUR_OF_DAY, 1)
            }
        }
        val triggerAt = trigger.timeInMillis

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent
        )

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        AppLog.d("SyncAlarmScheduler", "Next alarm at ${fmt.format(trigger.time)} (jitter=${jitterSeconds}s)")
    }

    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = makePendingIntent(
            context = context,
            action = ACTION_HOURLY_SYNC,
            requestCode = HOURLY_ALARM_REQUEST_CODE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        AppLog.d("SyncAlarmScheduler", "Hourly alarm canceled")
    }

    fun scheduleRetryAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + RETRY_DELAY_MS
        val pendingIntent = makePendingIntent(
            context = context,
            action = ACTION_RETRY_SYNC,
            requestCode = RETRY_ALARM_REQUEST_CODE
        )

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent
        )

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        AppLog.d(
            "SyncAlarmScheduler",
            "Retry alarm scheduled for ${fmt.format(triggerAt)} (+${RETRY_DELAY_MS / 60_000} min)"
        )
    }

    fun cancelRetryAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = makePendingIntent(
            context = context,
            action = ACTION_RETRY_SYNC,
            requestCode = RETRY_ALARM_REQUEST_CODE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        AppLog.d("SyncAlarmScheduler", "Retry alarm canceled")
    }

    private fun makePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, SyncAlarmReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
