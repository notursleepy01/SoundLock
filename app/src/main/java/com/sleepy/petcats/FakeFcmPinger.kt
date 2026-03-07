package com.sleepy.petcats

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Mimics FCM high-priority data message self-ping.
 * Sends an internal broadcast every 9 minutes → FakeFcmReceiver
 * → startForegroundService() for both services.
 * Zero internet. Zero Firebase. Purely internal.
 */
object FakeFcmPinger {

    private const val ACTION = "com.sleepy.petcats.FAKE_FCM_PING"
    // 9 minutes
    private const val INTERVAL_MS = 9 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private lateinit var appContext: Context

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            appContext.sendBroadcast(Intent(ACTION).apply {
                setPackage(appContext.packageName)
            })
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        handler.post(pingRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pingRunnable)
    }
}
