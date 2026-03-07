package com.sleepy.petcats

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Resurrect services
        ContextCompat.startForegroundService(
            context, Intent(context, MusicService::class.java)
        )
        ContextCompat.startForegroundService(
            context, Intent(context, WatchdogService::class.java)
        )
        // Re-arm immediately for next cycle
        scheduleAlarm(context)
    }

    companion object {
        private const val ACTION = "com.sleepy.petcats.ALARM_RESURRECT"
        private const val REQUEST_CODE = 0xA1
        // 9 minutes in ms
        private const val INTERVAL_MS = 9 * 60 * 1000L

        fun scheduleAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = getPendingIntent(context)
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS

            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else {
                        // Fallback — inexact but still fires in Doze
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                else -> {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
        }

        fun cancelAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(getPendingIntent(context))
        }

        private fun getPendingIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION).apply {
                setClass(context, AlarmReceiver::class.java)
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
