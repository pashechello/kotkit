package com.kotkit.basic.scheduler

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kotkit.basic.App
import com.kotkit.basic.R
import com.kotkit.basic.sound.SoundType
import com.kotkit.basic.ui.MainActivity

/**
 * Helper object for Smart Scheduler notifications.
 * All notifications have cute cat-themed messages and the KotKit logo! 🐱
 *
 * Includes Android 13+ POST_NOTIFICATIONS permission check.
 */
object SchedulerNotifications {

    private const val TAG = "SchedulerNotifications"

    // Notification ID offsets
    private const val WARNING_NOTIFICATION_OFFSET = 50_000
    private const val POSTING_NOTIFICATION_OFFSET = 60_000
    private const val RESULT_NOTIFICATION_OFFSET = 70_000
    private const val FORCE_PUBLISH_REQUEST_OFFSET = 80_000

    // Max value for safe notification ID calculation
    private const val NOTIFICATION_ID_MODULO = 9_000

    // 🐱 Cat-themed messages for all notification types

    // Warning messages (before posting)
    private val warningMessages10Min = listOf(
        "Мур! Через 10 минут начну постить. Положи телефон! 🐱",
        "Мяу~ Скоро буду работать! Оставь телефон в покое 😺",
        "Муррр... 10 минут до поста. Не трогай меня! 🐾"
    )

    private val warningMessages2Min = listOf(
        "Мяу! Почти пора! Ещё пару минут... 😸",
        "Мур-мур! Готовлюсь к прыжку на TikTok! 🐱",
        "Мяяяу! Скоро начну, не мешай! 🐾"
    )

    private val warningMessages1Min = listOf(
        "МЯУ! ОДНА МИНУТА! Не трогай!!! 🙀",
        "МУРРР! Сейчас начну! Лапы прочь от телефона! 😼",
        "Мяу-мяу-мяу! Уже бегу постить! 🏃‍♂️🐱"
    )

    // Starting messages
    private val startingMessages = listOf(
        "Мур! Побежал в TikTok! 🏃‍♂️🐱",
        "Мяу~ Начинаю колдовать... ✨🐾",
        "Муррр... Работаю, не мешай! 😺",
        "Мяу! Захватываю TikTok! 🐱💪"
    )

    // Busy messages (device in use)
    private val busyMessages = listOf(
        "Мур! Хотел выложить пост, но подожду пока освободишься 🐱",
        "Мур-мур! Ты занят, выложу попозже 😺",
        "Мур~ Вижу что занят, не буду мешать! 🐾",
        "Мяу! Пост готов, но ты занят. Подожду! 😸",
        "Мяу-мяу! Хотел запостить, но ты в деле 🐱",
        "Мяяяу! Ладно-ладно, выложу когда освободишься 😺",
        "Мур-мяу! Пост ждёт, а ты занят 🐾",
        "Муррр... Ничего, запощу позже! 😸",
        "Мяу! Телефон занят, подождём вместе 🐱"
    )

    // Success messages
    private val successMessages = listOf(
        "МЯЯЯУ! Готово! Пост улетел в TikTok! 🎉🐱",
        "Муррр~ Сделано! Я молодец! 😸✨",
        "Мяу! Запостил! Можешь гладить! 🐾💕",
        "Мур-мур-мур! Успех! Видео в TikTok! 🚀🐱",
        "Мяу~ Всё получилось! Дай вкусняшку! 😺🍪"
    )

    // Error messages
    private val errorMessages = listOf(
        "Мяу... Что-то пошло не так... 😿",
        "Мур... Не получилось, простите... 🙀",
        "Мяяяу~ Ошибка! Попробуй ещё раз? 😾",
        "Муррр... Провал. Но я старался! 😿🐾"
    )

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
     * Get the KotKit logo as a large icon for notifications.
     */
    private fun getLargeIcon(context: Context) =
        BitmapFactory.decodeResource(context.resources, R.drawable.logo)

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
                title = "⏰ $minutesBefore минут до поста"
                text = warningMessages10Min.random()
                priority = NotificationCompat.PRIORITY_DEFAULT
            }
            minutesBefore >= 2 -> {
                title = "⏰ $minutesBefore минут!"
                text = warningMessages2Min.random()
                priority = NotificationCompat.PRIORITY_DEFAULT
            }
            else -> {
                title = "🚨 1 МИНУТА!"
                text = warningMessages1Min.random()
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
            .setLargeIcon(getLargeIcon(context))
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(priority)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(R.drawable.ic_cancel, "Отменить 🐾", cancelPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSound(getSoundUri(context, SoundType.MEOW_WARNING))
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

        val message = startingMessages.random()

        // Stop action - backup stop button in case foreground notification doesn't show
        val stopIntent = Intent(context, StopPostingReceiver::class.java).apply {
            action = StopPostingReceiver.ACTION_STOP_POSTING
            putExtra("post_id", postId)  // Pass postId to cancel notification
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            calculateNotificationId(POSTING_NOTIFICATION_OFFSET, postId),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, App.CHANNEL_POSTING)
            .setSmallIcon(R.drawable.ic_upload)
            .setLargeIcon(getLargeIcon(context))
            .setContentTitle("🚀 Поехали!")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(R.drawable.ic_cancel, "Остановить 🐾", stopPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSound(getSoundUri(context, SoundType.MEOW_STARTING))
            .build()

        notificationManager.notify(
            calculateNotificationId(POSTING_NOTIFICATION_OFFSET, postId),
            notification
        )
    }

    /**
     * Show notification when device is busy and retry is scheduled.
     * Shows a cute cat message with "Publish now" button.
     */
    fun showDeviceBusyNotification(
        context: Context,
        postId: Long
    ) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val message = busyMessages.random()

        // "Publish now" action - force publish even if device is busy
        val forcePublishIntent = Intent(context, SchedulerReceiver::class.java).apply {
            action = SmartScheduler.ACTION_FORCE_PUBLISH
            putExtra(SmartScheduler.EXTRA_POST_ID, postId)
        }
        val forcePublishPendingIntent = PendingIntent.getBroadcast(
            context,
            calculateNotificationId(FORCE_PUBLISH_REQUEST_OFFSET, postId),
            forcePublishIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, App.CHANNEL_SCHEDULED)
            .setSmallIcon(R.drawable.ic_schedule)
            .setLargeIcon(getLargeIcon(context))
            .setContentTitle("😺 Подожду...")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(R.drawable.ic_upload, "Постить сейчас! 🚀", forcePublishPendingIntent)
            .setSound(getSoundUri(context, SoundType.MEOW_UI))
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

        val catMessage = successMessages.random()

        val notification = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_check)
            .setLargeIcon(getLargeIcon(context))
            .setContentTitle("🎉 Мяу! Успех!")
            .setContentText(catMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(catMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setSound(getSoundUri(context, SoundType.MEOW_SUCCESS))
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

        val catMessage = errorMessages.random()
        val fullMessage = "$catMessage\n\n$reason"

        val notification = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_error)
            .setLargeIcon(getLargeIcon(context))
            .setContentTitle("😿 Мяу... Ошибка")
            .setContentText(catMessage)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullMessage))
            .setSound(getSoundUri(context, SoundType.MEOW_ERROR))
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

    /**
     * Get URI for notification sound.
     */
    fun getSoundUri(context: Context, soundType: SoundType): Uri {
        return Uri.parse("android.resource://${context.packageName}/${soundType.rawResId}")
    }
}
