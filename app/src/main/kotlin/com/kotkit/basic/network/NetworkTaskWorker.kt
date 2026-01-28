package com.kotkit.basic.network

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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
    private val meowSoundService: MeowSoundService
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NetworkTaskWorker"
        private const val NOTIFICATION_ID = 20001
        const val KEY_TASK_ID = "task_id"
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
        if (taskId == null) {
            Log.e(TAG, "No task ID provided")
            return Result.failure()
        }

        Log.i(TAG, "Starting network task: $taskId")

        // Check worker mode is active
        if (!workerRepository.isWorkerActive()) {
            Log.w(TAG, "Worker mode not active, skipping task $taskId")
            return Result.failure()
        }

        // Get task from local DB
        val task = networkTaskRepository.getTaskById(taskId)
        if (task == null) {
            Log.e(TAG, "Task not found: $taskId")
            return Result.failure()
        }

        // Verify task is still assigned to us
        if (!NetworkTaskStatus.isActive(task.status)) {
            Log.w(TAG, "Task $taskId not in active status: ${task.status}")
            return Result.failure()
        }

        // Show foreground notification
        try {
            setForeground(createForegroundInfo(task.caption))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Cannot start foreground from background", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set foreground", e)
        }

        // Execute task
        val result = networkTaskExecutor.executeTask(task) { stage, progress ->
            Log.d(TAG, "Task $taskId: $stage ($progress%)")
        }

        // Handle result
        return when (result) {
            is ExecutionResult.Success -> {
                Log.i(TAG, "Task $taskId completed: ${result.tiktokVideoId}")
                meowSoundService.playSuccess()
                showCompletionNotification(true, task.priceUsd)
                Result.success()
            }

            is ExecutionResult.Failed -> {
                Log.e(TAG, "Task $taskId failed: ${result.message}")
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
                Log.w(TAG, "Task $taskId needs retry: ${result.reason}")
                Result.retry()
            }
        }
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

    private fun createForegroundInfo(caption: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, App.CHANNEL_POSTING)
            .setContentTitle("Выполняется задача")
            .setContentText(caption.take(50) + if (caption.length > 50) "..." else "")
            .setSmallIcon(R.drawable.ic_upload)
            .setOngoing(true)
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

    private fun showCompletionNotification(success: Boolean, earnedUsd: Float, error: String? = null) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val notification = if (success) {
            NotificationCompat.Builder(context, App.CHANNEL_POSTING)
                .setContentTitle("Задача выполнена! +$${String.format("%.2f", earnedUsd)}")
                .setContentText("Заработок зачислен на ваш баланс")
                .setSmallIcon(R.drawable.ic_check)
                .setAutoCancel(true)
                .build()
        } else {
            NotificationCompat.Builder(context, App.CHANNEL_POSTING)
                .setContentTitle("Задача не выполнена")
                .setContentText(error ?: "Произошла ошибка")
                .setSmallIcon(R.drawable.ic_error)
                .setAutoCancel(true)
                .build()
        }

        notificationManager.notify(Random.nextInt(30000, 40000), notification)
    }
}
