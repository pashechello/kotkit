package com.kotkit.basic.network

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.kotkit.basic.App
import com.kotkit.basic.R
import com.kotkit.basic.data.local.db.entities.NetworkTaskStatus
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.WorkerRepository
import com.kotkit.basic.sound.MeowSoundService
import com.kotkit.basic.sound.SoundType
import com.kotkit.basic.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.random.Random

/**
 * WorkManager worker that executes network tasks.
 *
 * Scheduled by NetworkWorkerService when a task is ready to execute
 * (after cooldown period).
 */
@HiltWorker
class NetworkTaskWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val networkTaskRepository: NetworkTaskRepository,
    private val workerRepository: WorkerRepository,
    private val networkTaskExecutor: NetworkTaskExecutor,
    private val meowSoundService: MeowSoundService,
    private val logUploader: LogUploader
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NetworkTaskWorker"
        private const val NOTIFICATION_ID = 20001
        private const val MIN_COOLDOWN_MINUTES = 30
        const val KEY_TASK_ID = "task_id"
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
        if (taskId == null) {
            Timber.tag(TAG).e("No task ID provided")
            return Result.failure()
        }

        Timber.tag(TAG).i("Starting network task: $taskId")

        // Check worker mode is active
        if (!workerRepository.isWorkerActive()) {
            Timber.tag(TAG).w("Worker mode not active, skipping task $taskId")
            return Result.failure()
        }

        // Get task from local DB
        val task = networkTaskRepository.getTaskById(taskId)
        if (task == null) {
            Timber.tag(TAG).e("Task not found: $taskId")
            return Result.failure()
        }

        // Verify task is still assigned to us
        if (!NetworkTaskStatus.isActive(task.status)) {
            Timber.tag(TAG).w("Task $taskId not in active status: ${task.status}")
            return Result.failure()
        }

        // Respect scheduled posting time — don't execute before scheduledFor
        if (task.scheduledFor != null && task.scheduledFor > System.currentTimeMillis()) {
            val waitMs = task.scheduledFor - System.currentTimeMillis()
            val waitMin = waitMs / 1000 / 60
            Timber.tag(TAG).w("Task $taskId not due yet (${waitMin}min). This shouldn't happen — scheduling error. Returning failure.")
            // Don't use Result.retry() here — it creates exponential backoff loop
            // that blocks currentlyExecutingTaskId in NetworkWorkerService.
            // The task will be picked up again by the polling loop when scheduledFor arrives.
            return Result.failure()
        }

        // Cooldown: don't post if last post was less than 30 minutes ago
        val lastCompletedAt = networkTaskRepository.getLastCompletedAt()
        if (lastCompletedAt != null) {
            val minutesSinceLast = (System.currentTimeMillis() - lastCompletedAt) / 1000 / 60
            if (minutesSinceLast < MIN_COOLDOWN_MINUTES) {
                Timber.tag(TAG).i("Task $taskId: cooldown active, ${MIN_COOLDOWN_MINUTES - minutesSinceLast}min remaining")
                return Result.retry()
            }
        }

        // Show foreground notification
        try {
            setForeground(createForegroundInfo(task.caption ?: ""))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Timber.tag(TAG).w(e, "Cannot start foreground from background")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to set foreground")
        }

        // Execute task with progress updates to foreground notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val result = networkTaskExecutor.executeTask(task) { stage, progress ->
            Timber.tag(TAG).d("Task $taskId: $stage ($progress%)")
            notificationManager.notify(NOTIFICATION_ID, createProgressNotification(stage, progress))
        }

        // Handle result
        val workResult = when (result) {
            is ExecutionResult.Success -> {
                Timber.tag(TAG).i("Task $taskId completed: ${result.tiktokVideoId}")
                meowSoundService.playSuccess()
                showCompletionNotification(true, task.priceRub)
                Result.success()
            }

            is ExecutionResult.Failed -> {
                Timber.tag(TAG).e("Task $taskId failed: ${result.message}")
                meowSoundService.playError()
                showCompletionNotification(false, 0f, result.message)

                // Check if should retry based on error type
                if (shouldRetry(result.errorType)) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }

            is ExecutionResult.Retry -> {
                Timber.tag(TAG).w("Task $taskId needs retry: ${result.reason}")
                Result.retry()
            }
        }

        // Best-effort log upload after task execution
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            logUploader.uploadLog(today)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to upload log after task")
        }

        return workResult
    }

    private fun shouldRetry(errorType: String): Boolean {
        // Transient errors that can be retried
        return errorType in listOf(
            "network_timeout",
            "app_crash",
            "device_reboot",
            "video_download_failed"
        )
    }

    private fun createProgressNotification(stage: ExecutionStage, progress: Int): android.app.Notification {
        val title = when (stage) {
            ExecutionStage.GETTING_URL -> "Получение ссылки..."
            ExecutionStage.DOWNLOADING -> "Скачивание видео..."
            ExecutionStage.WAITING_SCREEN_OFF -> "Ожидание блокировки экрана..."
            ExecutionStage.POSTING -> "Публикация в TikTok..."
            ExecutionStage.VERIFYING -> "Проверка публикации..."
            ExecutionStage.COMPLETING -> "Завершение задачи..."
            ExecutionStage.COMPLETED -> "Задача завершена"
        }

        val builder = NotificationCompat.Builder(context, App.CHANNEL_POSTING)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (stage == ExecutionStage.DOWNLOADING && progress in 0..100) {
            builder.setProgress(100, progress, false)
            builder.setContentText("$progress%")
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun createForegroundInfo(caption: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, App.CHANNEL_POSTING)
            .setContentTitle("Выполняется задача")
            .setContentText(caption.take(50) + if (caption.length > 50) "..." else "")
            .setSmallIcon(R.drawable.ic_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(success: Boolean, earnedRub: Float, error: String? = null) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = Random.nextInt(30000, 40000)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (success) {
            val soundUri = Uri.parse("android.resource://${context.packageName}/${SoundType.MEOW_SUCCESS.rawResId}")
            NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
                .setContentTitle("Задача выполнена! +${String.format("%.2f", earnedRub)} ₽")
                .setContentText("Заработок зачислен на ваш баланс")
                .setSmallIcon(R.drawable.ic_check)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setSound(soundUri)
                .build()
        } else {
            val soundUri = Uri.parse("android.resource://${context.packageName}/${SoundType.MEOW_ERROR.rawResId}")
            NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
                .setContentTitle("Задача не выполнена")
                .setContentText(error ?: "Произошла ошибка")
                .setSmallIcon(R.drawable.ic_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setSound(soundUri)
                .build()
        }

        notificationManager.notify(notificationId, notification)
    }
}
