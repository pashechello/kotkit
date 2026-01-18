package com.autoposter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.autoposter.scheduler.DeviceStateChecker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var deviceStateChecker: DeviceStateChecker

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startDeviceStateMonitoring()
    }

    private fun startDeviceStateMonitoring() {
        // Start monitoring device state for Smart Scheduler
        deviceStateChecker.startMonitoring()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Posting channel - shown during active posting
            val postingChannel = NotificationChannel(
                CHANNEL_POSTING,
                "Posting Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status while posting to TikTok"
                setShowBadge(false)
            }

            // Alerts channel - for success/error notifications
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Post Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for completed or failed posts"
                setShowBadge(true)
            }

            // Scheduled channel - reminders about upcoming posts
            val scheduledChannel = NotificationChannel(
                CHANNEL_SCHEDULED,
                "Scheduled Posts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders about scheduled posts"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(
                listOf(postingChannel, alertsChannel, scheduledChannel)
            )
        }
    }

    companion object {
        const val CHANNEL_POSTING = "posting_channel"
        const val CHANNEL_ALERTS = "alerts_channel"
        const val CHANNEL_SCHEDULED = "scheduled_channel"
    }
}
