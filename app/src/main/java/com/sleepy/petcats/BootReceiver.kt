package com.sleepy.petcats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON"
        )
        if (intent.action !in validActions) return

        // Start both services immediately
        ContextCompat.startForegroundService(
            context, Intent(context, MusicService::class.java)
        )
        ContextCompat.startForegroundService(
            context, Intent(context, WatchdogService::class.java)
        )

        // Re-arm alarm
        AlarmReceiver.scheduleAlarm(context)
    }
}
