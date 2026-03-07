package com.sleepy.petcats

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class ResurrectJobService : JobService() {

    companion object {
        private const val JOB_ID = 0xB1

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            // Cancel any existing job first
            scheduler.cancel(JOB_ID)

            val component = ComponentName(context, ResurrectJobService::class.java)
            val builder = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true) // survives reboot
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(5_000L)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setImportantWhileForeground(true)
            }

            scheduler.schedule(builder.build())
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        // Resurrect both services
        ContextCompat.startForegroundService(
            this, Intent(this, MusicService::class.java)
        )
        ContextCompat.startForegroundService(
            this, Intent(this, WatchdogService::class.java)
        )

        // Re-schedule immediately for continuous resurrection
        schedule(this)
        AlarmReceiver.scheduleAlarm(this)

        jobFinished(params, true) // true = reschedule if failed
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return true // reschedule
    }
}
