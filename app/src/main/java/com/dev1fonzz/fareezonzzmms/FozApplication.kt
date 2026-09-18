package com.dev1fonzz.fareezonzzmms

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import java.util.concurrent.TimeUnit

class FozApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleAnnouncementPolling()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            getString(R.string.notif_channel_announcements),
            getString(R.string.notif_channel_announcements_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_announcements_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    /**
     * Check ANNOUNCEMENT_BOARD setiap ~15 minit (had minimum Android untuk
     * periodic background work — bukan pilihan kami). Guna KEEP supaya tak
     * re-schedule berulang setiap kali app dibuka (elak drift/duplicate).
     */
    private fun scheduleAnnouncementPolling() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AnnouncementPollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "foz_announcement_poll",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
