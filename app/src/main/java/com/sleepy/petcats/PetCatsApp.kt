package com.sleepy.petcats

import android.app.Activity
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

class PetCatsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}

            override fun onActivityPaused(a: Activity) {
                // Any activity paused — ensure services are alive
                ensureServicesRunning()
            }

            override fun onActivityStopped(a: Activity) {
                ensureServicesRunning()
            }

            override fun onActivityDestroyed(a: Activity) {
                ensureServicesRunning()
                // Relaunch MainActivity if it was destroyed
                if (a is MainActivity) {
                    val intent = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                    }
                    applicationContext.startActivity(intent)
                }
            }

            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
    }

    fun ensureServicesRunning() {
        ContextCompat.startForegroundService(
            this, Intent(this, MusicService::class.java)
        )
        ContextCompat.startForegroundService(
            this, Intent(this, WatchdogService::class.java)
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ensureServicesRunning()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ensureServicesRunning()
    }
}
