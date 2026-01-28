package com.kotkit.basic.status

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import android.provider.Settings
import androidx.work.WorkManager
import com.kotkit.basic.R
import com.kotkit.basic.agent.PostingAgent
import com.kotkit.basic.scheduler.SmartScheduler
import com.kotkit.basic.ui.MainActivity
import timber.log.Timber

class FloatingLogoService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("FloatingLogoService onCreate")

        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            Timber.w("No overlay permission, stopping service")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingView()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_logo, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }

        floatingView?.setOnClickListener {
            Timber.d("Floating logo clicked, stopping KotKit completely")

            // 1. Cancel the current posting task
            PostingAgent.getInstance()?.cancelCurrentTask()

            // 2. Cancel all posting WorkManager jobs (not other jobs like heartbeat)
            WorkManager.getInstance(this).cancelAllWorkByTag(SmartScheduler.TAG_POSTING)
            Timber.d("All posting jobs cancelled")

            // 3. Open Accessibility Settings so user can disable the service
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)

            // 4. Stop this service (remove floating logo)
            stopSelf()
        }

        try {
            windowManager.addView(floatingView, params)
            Timber.d("Floating view added to window")
        } catch (e: Exception) {
            Timber.e(e, "Failed to add floating view")
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_STATUS,
                "Status Indicator",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when KotKit is active"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Timber.d("FloatingLogoService onDestroy")
        isRunning = false
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove floating view")
            }
        }
        floatingView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FloatingLogoService"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_STATUS = "status_channel"

        @Volatile
        private var isRunning = false

        fun start(context: Context) {
            if (isRunning) {
                Timber.d("FloatingLogoService already running")
                return
            }

            if (!OverlayPermissionHelper.hasOverlayPermission(context)) {
                Timber.w("Cannot start FloatingLogoService: no overlay permission")
                return
            }

            Timber.d("Starting FloatingLogoService")
            isRunning = true
            val intent = Intent(context, FloatingLogoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!isRunning) {
                Timber.d("FloatingLogoService not running")
                return
            }

            Timber.d("Stopping FloatingLogoService")
            isRunning = false
            val intent = Intent(context, FloatingLogoService::class.java)
            context.stopService(intent)
        }

        fun isServiceRunning(): Boolean = isRunning
    }
}
