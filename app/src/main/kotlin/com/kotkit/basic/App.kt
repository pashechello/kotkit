package com.kotkit.basic

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kotkit.basic.data.repository.SettingsRepository
import com.kotkit.basic.logging.FileLoggingTree
import com.kotkit.basic.scheduler.DeviceStateChecker
import com.kotkit.basic.sound.SoundType
import dagger.hilt.android.HiltAndroidApp
import org.conscrypt.Conscrypt
import timber.log.Timber
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var deviceStateChecker: DeviceStateChecker

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        initTimber()
        initConscrypt()
        setAppLanguage()
        createNotificationChannels()
        startDeviceStateMonitoring()
    }

    private fun initTimber() {
        // Always plant file tree for persistent logging
        Timber.plant(FileLoggingTree(this))

        // Also plant debug tree for logcat output in debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.tag("App").i("PROCESS START: version=${BuildConfig.VERSION_NAME}, pid=${android.os.Process.myPid()}, " +
            "device=${Build.MANUFACTURER} ${Build.MODEL}, SDK=${Build.VERSION.SDK_INT}, " +
            "aggressiveOEM=${com.kotkit.basic.network.ServiceResurrector.isAggressiveOem()}")
    }

    private fun initConscrypt() {
        // Conscrypt для современного TLS в API-соединениях
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }

    private fun setAppLanguage() {
        val language = settingsRepository.appLanguage
        val localeList = LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(localeList)
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

            // Delete and recreate posting channel to apply sound changes
            notificationManager.deleteNotificationChannel(CHANNEL_POSTING)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Posting channel - shown during active posting
            // IMPORTANCE_DEFAULT required for action buttons to be visible on some OEMs (Samsung, Xiaomi)
            val postingChannel = NotificationChannel(
                CHANNEL_POSTING,
                getString(R.string.notification_channel_posting),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_posting)
                setShowBadge(false)
                setSound(getSoundUri(SoundType.MEOW_STARTING), audioAttributes)
            }

            // Alerts channel - for success/error notifications
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_alerts)
                setShowBadge(true)
                setSound(getSoundUri(SoundType.MEOW_SUCCESS), audioAttributes)
            }

            // Scheduled channel - reminders about upcoming posts
            val scheduledChannel = NotificationChannel(
                CHANNEL_SCHEDULED,
                getString(R.string.notification_channel_scheduled),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_scheduled)
                setShowBadge(false)
                setSound(getSoundUri(SoundType.MEOW_WARNING), audioAttributes)
            }

            notificationManager.createNotificationChannels(
                listOf(postingChannel, alertsChannel, scheduledChannel)
            )
        }
    }

    private fun getSoundUri(soundType: SoundType): Uri {
        return Uri.parse("android.resource://$packageName/${soundType.rawResId}")
    }

    companion object {
        const val CHANNEL_POSTING = "posting_channel"
        const val CHANNEL_ALERTS = "alerts_channel"
        const val CHANNEL_SCHEDULED = "scheduled_channel"
    }
}
