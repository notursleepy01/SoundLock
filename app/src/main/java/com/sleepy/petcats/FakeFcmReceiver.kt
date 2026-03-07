package com.sleepy.petcats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Receives internal "fake FCM" pings sent by FakeFcmPinger.
 * Mimics onMessageReceived() — immediately starts foreground services.
 */
class FakeFcmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(
            context, Intent(context, MusicService::class.java)
        )
        ContextCompat.startForegroundService(
            context, Intent(context, WatchdogService::class.java)
        )
    }
}
