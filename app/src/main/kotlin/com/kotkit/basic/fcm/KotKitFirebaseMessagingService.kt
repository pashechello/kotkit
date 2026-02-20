package com.kotkit.basic.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.net.Uri
import com.kotkit.basic.App
import com.kotkit.basic.R
import com.kotkit.basic.network.NetworkTaskCoordinator
import com.kotkit.basic.network.NetworkWorkerService
import com.kotkit.basic.network.TaskAcceptWorker
import com.kotkit.basic.sound.SoundType
import com.kotkit.basic.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for receiving push notifications.
 *
 * Handles:
 * - Task reserved notifications (push-based queue system)
 * - New task available notifications (legacy/fallback)
 * - FCM token refresh
 * - Data payload processing
 */
@AndroidEntryPoint
class KotKitFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var taskCoordinator: NetworkTaskCoordinator
    @Inject lateinit var fcmTokenManager: FCMTokenManager

    // Atomic counter for unique notification IDs
    private val notificationIdCounter = AtomicInteger(0)

    override fun onMessageReceived(message: RemoteMessage) {
        Timber.tag(TAG).i("FCM message received from: ${message.from}")

        // Extract data payload
        val data = message.data
        val notificationType = data["type"] ?: run {
            Timber.tag(TAG).w("No notification type in message")
            return
        }

        when (notificationType) {
            "task_reserved" -> handleTaskReserved(data)
            "task_available" -> handleTaskAvailable(data)
            "task_completed" -> handleTaskCompleted(data)
            "task_cancelled" -> handleTaskCancelled(data)
            "payout_processed" -> handlePayoutProcessed(data)
            "url_submission_needed" -> handleUrlSubmissionNeeded(data)
            "announcement" -> handleAnnouncement(message)
            else -> Timber.tag(TAG).w("Unknown notification type: $notificationType")
        }
    }

    /**
     * Handle task_reserved - Push-based queue system.
     *
     * Backend has reserved a task specifically for this worker.
     * Delegates accept to WorkManager (survives FCM service destruction).
     *
     * Previous approach used serviceScope.launch which got canceled when
     * Android (especially MIUI) destroyed the FCM service after onMessageReceived().
     */
    private fun handleTaskReserved(data: Map<String, String>) {
        val taskId = data["task_id"] ?: run {
            Timber.tag(TAG).w("No task_id in task_reserved message")
            return
        }
        val campaignName = data["campaign_name"] ?: "New Task"
        val priceRub = data["price_rub"]?.toFloatOrNull() ?: 0f
        val expiresAt = data["expires_at"]?.toLongOrNull()

        Timber.tag(TAG).i("Task reserved: $taskId - $campaignName - ${priceRub} ₽ (expires: $expiresAt)")

        // Delegate accept to WorkManager - resilient to service lifecycle destruction.
        // WorkManager has its own coroutine scope that survives service onDestroy().
        TaskAcceptWorker.enqueue(applicationContext, taskId, expiresAt)

        // Ensure foreground service is running for task execution
        try {
            NetworkWorkerService.start(applicationContext)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not start NetworkWorkerService")
        }

        // Show notification
        showNotification(
            title = "Task Reserved",
            body = "$campaignName - ${priceRub} ₽"
        )
    }

    /**
     * Handle task_available - Legacy/fallback notification.
     */
    private fun handleTaskAvailable(data: Map<String, String>) {
        val taskId = data["task_id"]
        val campaignName = data["campaign_name"] ?: "New Task"
        val priceRub = data["price_rub"]?.toFloatOrNull() ?: 0f

        Timber.tag(TAG).i("Task available: $taskId - $campaignName - ${priceRub} ₽")

        // Trigger immediate task check
        taskCoordinator.triggerTaskCheck(immediate = true, reason = "fcm_notification")

        // Show notification to user
        showNotification(
            title = "New Task Available",
            body = "$campaignName - ${priceRub} ₽"
        )
    }

    private fun handleTaskCompleted(data: Map<String, String>) {
        val earnedRub = data["earned_rub"]?.toFloatOrNull() ?: 0f
        showNotification(
            title = "Task Completed!",
            body = "Вы заработали ${earnedRub} ₽"
        )
    }

    private fun handlePayoutProcessed(data: Map<String, String>) {
        val amount = data["amount_rub"]?.toFloatOrNull() ?: 0f
        val method = data["method"] ?: "wallet"
        showNotification(
            title = "Payout Processing",
            body = "${amount} ₽ через $method — обрабатывается"
        )
    }

    private fun handleTaskCancelled(data: Map<String, String>) {
        val taskId = data["task_id"]
        Timber.tag(TAG).i("Task cancelled by server: $taskId")

        // Trigger task check — coordinator will see the task is gone and clean up
        taskCoordinator.triggerTaskCheck(immediate = true, reason = "task_cancelled")
    }

    private fun handleUrlSubmissionNeeded(data: Map<String, String>) {
        val count = data["count"]?.toIntOrNull() ?: 1
        val title = data["title"] ?: "Отправьте ссылку на видео"
        val body = data["body"] ?: "Пожалуйста, вставьте ссылку на ваш TikTok пост"

        Timber.tag(TAG).i("URL submission needed: $count task(s)")

        showAlertNotification(
            title = title,
            body = body,
            navigateTo = "completed_tasks"
        )
    }

    private fun handleAnnouncement(message: RemoteMessage) {
        val title = message.notification?.title ?: "System Announcement"
        val body = message.notification?.body ?: ""
        showNotification(title, body)
    }

    private fun showAlertNotification(title: String, body: String, navigateTo: String? = null) {
        val notificationId = notificationIdCounter.incrementAndGet()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (navigateTo != null) {
                putExtra("navigate_to", navigateTo)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = Uri.parse("android.resource://$packageName/${SoundType.MEOW_WARNING.rawResId}")

        val notification = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(soundUri)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    private fun showNotification(title: String, body: String, navigateTo: String? = null) {
        val notificationId = notificationIdCounter.incrementAndGet()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (navigateTo != null) {
                putExtra("navigate_to", navigateTo)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_POSTING)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    override fun onNewToken(token: String) {
        Timber.tag(TAG).i("New FCM token: ${token.take(20)}...")

        // Register token with backend
        fcmTokenManager.updateToken(token)
    }

    companion object {
        private const val TAG = "FCMService"
    }
}
