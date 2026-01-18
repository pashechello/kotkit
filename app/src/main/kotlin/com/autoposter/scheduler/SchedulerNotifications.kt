package com.autoposter.scheduler

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoposter.App
import com.autoposter.R
import com.autoposter.ui.MainActivity

/**
 * Helper object for Smart Scheduler notifications.
 *
 * Notification types:
 * - Warning (10 min before): "Публикация через 10 минут. Оставьте телефон в покое"
 * - Final warning (1 min before): "Публикация через 1 минуту!"
 * - Starting: "Начинаю публикацию..."
 * - Device busy: "Телефон занят. Повтор через 5 минут"
 * - Success: "Готово! Видео опубликовано"
 * - Failed: "Не удалось опубликовать"
 *
 * Includes Android 13+ POST_NOTIFICATIONS permission check.
 */
object SchedulerNotifications {

    private const val TAG = "SchedulerNotifications"

    // Notification ID offsets
    private const val WARNING_NOTIFICATION_OFFSET = 50_000
    private const val POSTING_NOTIFICATION_OFFSET = 60_000
    private const val RESULT_NOTIFICATION_OFFSET = 70_000

    // Max value for safe notification ID calculation
    private const val NOTIFICATION_ID_MODULO = 9_000

    /**
     * Check if notifications can be posted.
     * On Android 13+, requires POST_NOTIFICATIONS runtime permission.
     */
    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Calculate safe notification ID that won't overflow.
     */
    private fun calculateNotificationId(offset: Int, postId: Long): Int {
        val postIdPart = ((postId.hashCode() and 0x7FFFFFFF) % NOTIFICATION_ID_MODULO)
        return offset + postIdPart
    }

    /**
     * Show warning notification before scheduled posting.
     */
    fun showWarningNotification(context: Context, postId: Long, minutesBefore: Int) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val title: String
        val text: String
        val priority: Int

        when {
            minutesBefore >= 10 -> {
                title = "Публикация через $minutesBefore минут"
                text = "Оставьте телефон в покое для автоматической публикации"
                priority = NotificationCompat.PRIORITY_DEFAULT
            }
            minutesBefore >= 2 -> {
                title = "Публикация через $minutesBefore минут"
                text = "Скоро начнётся автоматическая публикация"
                priority = NotificationCompat.PRIORITY_DEFAULT
            }
            else -> {
                title = "Публикация через 1 минуту!"
                text = "Не трогайте телефон"
                priority = NotificationCompat.PRIORITY_HIGH
            }
        }

        // Cancel action
        val cancelIntent = Intent(context, SchedulerReceiver::class.java).apply {
            action = SmartScheduler.ACTION_CANCEL_POST
            putExtra(SmartScheduler.EXTRA_POST_ID, postId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            calculateNotificationId(WARNING_NOTIFICATION_OFFSET, postId),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, App.CHANNEL_SCHEDULED)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(priority)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(R.drawable.ic_cancel, "Отменить", cancelPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()

        notificationManager.notify(
            calculateNotificationId(WARNING_NOTIFICATION_OFFSET, postId),
            notification
        )
    }

    /**
     * Show notification when posting is starting.
     */
    fun showStartingNotification(context: Context, postId: Long) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Cancel warning notification
        notificationManager.cancel(calculateNotificationId(WARNING_NOTIFICATION_OFFSET, postId))

        val notification = NotificationCompat.Builder(context, App.CHANNEL_POSTING)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle("Начинаю публикацию")
            .setContentText("Не трогайте телефон...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        notificationManager.notify(
            calculateNotificationId(POSTING_NOTIFICATION_OFFSET, postId),
            notification
        )
    }

    /**
     * Show notification when device is busy and retry is scheduled.
     */
    fun showDeviceBusyNotification(
        context: Context,
        postId: Long,
        reasons: List<String>,
        retryInMinutes: Int
    ) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val reasonsText = reasons.joinToString("; ")

        val notification = NotificationCompat.Builder(context, App.CHANNEL_SCHEDULED)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle("Телефон занят")
            .setContentText("Повтор через $retryInMinutes мин")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Причины: $reasonsText\n\nПовтор через $retryInMinutes минут. Оставьте телефон в покое.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        notificationManager.notify(
            calculateNotificationId(WARNING_NOTIFICATION_OFFSET, postId),
            notification
        )
    }

    /**
     * Show success notification after posting completes.
     */
    fun showSuccessNotification(context: Context, postId: Long, message: String? = null) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Cancel posting notification
        notificationManager.cancel(calculateNotificationId(POSTING_NOTIFICATION_OFFSET, postId))
        notificationManager.cancel(calculateNotificationId(WARNING_NOTIFICATION_OFFSET, postId))

        // Open app intent
        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            calculateNotificationId(RESULT_NOTIFICATION_OFFSET, postId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("Готово!")
            .setContentText(message ?: "Видео опубликовано в TikTok")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        notificationManager.notify(
            calculateNotificationId(RESULT_NOTIFICATION_OFFSET, postId),
            notification
        )
    }

    /**
     * Show failure notification.
     */
    fun showFailedNotification(context: Context, postId: Long, reason: String) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Cancel ongoing notifications
        notificationManager.cancel(calculateNotificationId(POSTING_NOTIFICATION_OFFSET, postId))
        notificationManager.cancel(calculateNotificationId(WARNING_NOTIFICATION_OFFSET, postId))

        // Open app intent
        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            calculateNotificationId(RESULT_NOTIFICATION_OFFSET, postId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle("Не удалось опубликовать")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .build()

        notificationManager.notify(
            calculateNotificationId(RESULT_NOTIFICATION_OFFSET, postId),
            notification
        )
    }

    /**
     * Cancel all notifications for a post.
     */
    fun cancelAllNotifications(context: Context, postId: Long) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        notificationManager.cancel(calculateNotificationId(WARNING_NOTIFICATION_OFFSET, postId))
        notificationManager.cancel(calculateNotificationId(POSTING_NOTIFICATION_OFFSET, postId))
        notificationManager.cancel(calculateNotificationId(RESULT_NOTIFICATION_OFFSET, postId))
    }
}
