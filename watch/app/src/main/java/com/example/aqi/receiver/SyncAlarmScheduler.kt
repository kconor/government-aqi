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
    private const val ALARM_REQUEST_CODE = 1001

    fun scheduleAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = makePendingIntent(context)

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

    private fun makePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SyncAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
