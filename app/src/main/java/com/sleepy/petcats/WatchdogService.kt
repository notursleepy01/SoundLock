package com.sleepy.petcats

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class WatchdogService : Service() {

    companion object {
        private const val CHANNEL_ID = "PetCatsWatchdog"
        private const val NOTIFICATION_ID = 2
        private const val WATCH_INTERVAL_MS = 500L
        private const val TASK_FRONT_INTERVAL_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    // ── Watchdog loop: keep services alive ──────────────────────────────────
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            ensureServicesAlive()
            handler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    // ── Task front loop: drag activity back ─────────────────────────────────
    private val taskFrontRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            bringActivityToFront()
            handler.postDelayed(this, TASK_FRONT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PetCats::WatchdogWakeLock"
        )
        wakeLock?.acquire()

        // Bind dummy network to boost oom_adj on some OEMs
        bindDummyNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!isRunning) {
            isRunning = true
            handler.post(watchdogRunnable)
            handler.post(taskFrontRunnable)

            // Re-arm all resurrection mechanisms
            AlarmReceiver.scheduleAlarm(this)
            ResurrectJobService.schedule(this)
            FakeFcmPinger.start(this)
        }

        return START_STICKY
    }

    private fun ensureServicesAlive() {
        if (!isServiceRunning(MusicService::class.java)) {
            ContextCompat.startForegroundService(
                this, Intent(this, MusicService::class.java)
            )
        }
        // Re-arm alarm + job on every cycle as safety net
        AlarmReceiver.scheduleAlarm(this)
    }

    @Suppress("DEPRECATION")
    private fun bringActivityToFront() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = try {
            am.getRunningTasks(10)
        } catch (e: Exception) {
            return
        }
        if (tasks.isNullOrEmpty()) return

        val topTask = tasks[0]
        val topPackage = topTask.topActivity?.packageName ?: return

        if (topPackage != packageName) {
            // We're not in front — drag back
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(50).any {
            it.service.className == serviceClass.name
        }
    }

    private fun bindDummyNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {})
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null

        // Self-resurrect immediately
        ContextCompat.startForegroundService(
            this, Intent(this, WatchdogService::class.java)
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App swiped from recents — resurrect everything
        ContextCompat.startForegroundService(
            this, Intent(this, MusicService::class.java)
        )
        ContextCompat.startForegroundService(
            this, Intent(this, WatchdogService::class.java)
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Cats Events",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pet Cats game event monitor"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pet Cats")
            .setContentText("Watching for game events…")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setFlag(Notification.FLAG_NO_CLEAR, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
