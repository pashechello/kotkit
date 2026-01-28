package com.kotkit.basic.network

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kotkit.basic.App
import com.kotkit.basic.ui.MainActivity
import com.kotkit.basic.R
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Foreground service for Network Worker Mode.
 *
 * Responsibilities:
 * - Show persistent notification while worker mode is active
 * - Poll for scheduled tasks and dispatch execution
 * - Maintain WAKE_LOCK during task execution
 * - Coordinate with HeartbeatWorker
 */
@AndroidEntryPoint
class NetworkWorkerService : Service() {

    companion object {
        private const val TAG = "NetworkWorkerService"
        private const val NOTIFICATION_ID = 10001
        private const val WAKE_LOCK_TAG = "KotKit::NetworkWorker"
        private const val POLL_INTERVAL_MS = 30_000L // 30 seconds
        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L // 30 minutes per task

        const val ACTION_START = "com.kotkit.basic.network.START"
        const val ACTION_STOP = "com.kotkit.basic.network.STOP"

        fun start(context: Context) {
            val intent = Intent(context, NetworkWorkerService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NetworkWorkerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var networkTaskRepository: NetworkTaskRepository
    @Inject lateinit var workerRepository: WorkerRepository
    @Inject lateinit var workManager: WorkManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    @Volatile private var currentlyExecutingTaskId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NetworkWorkerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWorkerMode()
            ACTION_STOP -> stopWorkerMode()
        }
        return START_STICKY
    }

    private fun startWorkerMode() {
        if (isRunning) {
            Log.d(TAG, "Worker mode already running")
            return
        }

        Log.i(TAG, "Starting worker mode")
        isRunning = true

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification(0, 0f))

        // Schedule heartbeat worker
        HeartbeatWorker.schedule(workManager)

        // Start polling for tasks
        startPolling()
    }

    private fun stopWorkerMode() {
        Log.i(TAG, "Stopping worker mode")
        isRunning = false

        // Cancel heartbeat worker
        HeartbeatWorker.cancel(workManager)

        // Stop polling
        pollingJob?.cancel()
        pollingJob = null

        // Release wake lock
        releaseWakeLock()

        // Stop service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive && isRunning) {
                try {
                    checkAndExecuteTask()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkAndExecuteTask() {
        // Check if worker mode is still active
        if (!workerRepository.isWorkerActive()) {
            Log.d(TAG, "Worker mode disabled, stopping service")
            stopWorkerMode()
            return
        }

        // Prevent concurrent task execution
        if (currentlyExecutingTaskId != null) {
            Log.d(TAG, "Task already executing: $currentlyExecutingTaskId")
            return
        }

        // Get next scheduled task
        val task = networkTaskRepository.getNextScheduledTask()
        if (task == null) {
            Log.d(TAG, "No tasks ready for execution")
            updateNotification(0, getTodayEarnings())
            return
        }

        Log.i(TAG, "Found task ready for execution: ${task.id}")

        // Check daily limit
        val completedToday = networkTaskRepository.countCompletedToday()
        // TODO: Get max_daily_posts from app config
        val maxDailyPosts = 5
        if (completedToday >= maxDailyPosts) {
            Log.w(TAG, "Daily limit reached: $completedToday/$maxDailyPosts")
            updateNotification(0, getTodayEarnings(), "Дневной лимит достигнут")
            return
        }

        // Mark task as currently executing
        currentlyExecutingTaskId = task.id

        // Acquire wake lock for execution
        acquireWakeLock()

        // Schedule task execution via WorkManager
        scheduleTaskExecution(task.id)

        // Update notification
        updateNotification(1, getTodayEarnings())
    }

    private fun scheduleTaskExecution(taskId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(NetworkTaskWorker.KEY_TASK_ID, taskId)
            .build()

        val request = OneTimeWorkRequestBuilder<NetworkTaskWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        // Observe work completion to clear currently executing task
        workManager.getWorkInfoByIdLiveData(request.id).observeForever { workInfo ->
            if (workInfo != null && workInfo.state.isFinished) {
                Log.i(TAG, "Task execution finished: $taskId (state=${workInfo.state})")
                if (currentlyExecutingTaskId == taskId) {
                    currentlyExecutingTaskId = null
                    releaseWakeLock()
                }
            }
        }

        workManager.enqueue(request)
        Log.i(TAG, "Scheduled task execution: $taskId")
    }

    private suspend fun getTodayEarnings(): Float {
        return try {
            workerRepository.getTodayEarnings()
        } catch (e: Exception) {
            0f
        }
    }

    private fun createNotification(activeTasksCount: Int, todayEarnings: Float, statusMessage: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (activeTasksCount > 0) {
            "Выполняется задач: $activeTasksCount"
        } else {
            "Режим работника активен"
        }

        val text = statusMessage ?: "Заработано сегодня: $${String.format("%.2f", todayEarnings)}"

        return NotificationCompat.Builder(this, App.CHANNEL_POSTING)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_cancel,
                "Остановить",
                createStopPendingIntent()
            )
            .build()
    }

    private fun updateNotification(activeTasksCount: Int, todayEarnings: Float, statusMessage: String? = null) {
        val notification = createNotification(activeTasksCount, todayEarnings, statusMessage)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createStopPendingIntent(): PendingIntent {
        val intent = Intent(this, NetworkWorkerService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )
        }
        wakeLock?.acquire(MAX_WAKE_LOCK_MS)
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        Log.i(TAG, "NetworkWorkerService destroyed")
        isRunning = false
        pollingJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
