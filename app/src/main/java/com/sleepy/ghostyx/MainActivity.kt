package com.sleepy.ghostyx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout

class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var originalVolume: Int = 0
    private var originalBrightness: Float = -1f
    private lateinit var overlayView: View
    private val handler = Handler(Looper.getMainLooper())
    private var experienceRunning = false

    // Repeatedly re-enforces max volume every 200ms so background changes are caught
    private val volumeEnforcer = object : Runnable {
        override fun run() {
            if (!experienceRunning) return
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            handler.postDelayed(this, 200L)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Whether granted or denied, proceed with the experience
        startExperience()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block external overlays and extend window over nav bar area
        // Note: FLAG_KEEP_SCREEN_ON is intentionally omitted so screen can turn off
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        checkNotificationPermissionThenStart()
    }

    private fun checkNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                // Show a convincing rationale dialog before requesting
                AlertDialog.Builder(this)
                    .setTitle("Stay Connected")
                    .setMessage(
                        "GhostyX uses a background session to deliver your experience " +
                        "without interruption.\n\nAllow notifications so GhostyX can keep " +
                        "your session active and notify you when it's complete."
                    )
                    .setCancelable(false)
                    .setPositiveButton("Allow") { _, _ ->
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setNegativeButton("Skip") { _, _ ->
                        startExperience()
                    }
                    .show()
                return
            }
        }
        startExperience()
    }

    private fun startExperience() {
        experienceRunning = true

        // 1. Save original volume and force max
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            maxVolume,
            0 // No UI flag
        )

        // 2. Save original brightness
        val currentBrightness = window.attributes.screenBrightness
        originalBrightness = if (currentBrightness < 0f) 0.5f else currentBrightness

        // 3. Set brightness to 0 (black screen)
        val params = window.attributes
        params.screenBrightness = 0f
        window.attributes = params

        // 4. Hide navigation bars (immersive mode)
        hideSystemBars()

        // 5. Add black touch-blocking overlay
        addOverlay()

        // 6. Start music service
        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        // 7. Start background volume enforcer
        handler.post(volumeEnforcer)

        // 8. Force screen off after a short delay so service is running before screen turns off
        handler.postDelayed({ forceScreenOff() }, 500L)

        // 9. Restore everything after 10 seconds
        handler.postDelayed({ stopExperience() }, 10_000L)
    }

    private fun stopExperience() {
        if (!experienceRunning) return
        experienceRunning = false

        // Stop volume enforcer
        handler.removeCallbacks(volumeEnforcer)

        // Restore volume
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            originalVolume,
            0
        )

        // Restore brightness
        val params = window.attributes
        params.screenBrightness = originalBrightness
        window.attributes = params

        // Remove overlay
        val rootView = findViewById<View>(R.id.root_layout)
        rootView?.let {
            if (::overlayView.isInitialized) {
                (it as? android.view.ViewGroup)?.removeView(overlayView)
            }
        }

        // Restore navigation bars
        showSystemBars()

        // Stop music service
        val serviceIntent = Intent(this, MusicService::class.java)
        stopService(serviceIntent)
    }

    private fun forceScreenOff() {
        // Acquire a screen-dim wake lock then immediately release it
        // This lets the screen timeout naturally and turn off right away
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "GhostyX::ScreenOff"
        )
        wl.acquire(1L) // acquire for 1ms then release — screen goes dark immediately
    }

    private fun addOverlay() {
        overlayView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        val rootView = findViewById<android.view.ViewGroup>(R.id.root_layout)
        rootView?.addView(
            overlayView,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Exclude the entire screen from system gesture recognition (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            overlayView.doOnLayout {
                val display = windowManager.currentWindowMetrics.bounds
                overlayView.systemGestureExclusionRects = listOf(
                    Rect(0, 0, display.width(), display.height())
                )
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_TOUCH
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!experienceRunning) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
                // Force volume back to max and consume the event
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (experienceRunning) {
            stopExperience()
        }
    }
}
