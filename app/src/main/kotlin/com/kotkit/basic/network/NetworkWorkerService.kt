package com.kotkit.basic.network

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        private const val POLL_INTERVAL_MS = 60_000L // 60 seconds (reduced, FCM is primary now)
        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L // 30 minutes per task
        private const val MAX_EXECUTION_MS = 25 * 60 * 1000L // 25 min safety timeout for execution flag

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
    @Inject lateinit var fcmTokenManager: com.kotkit.basic.fcm.FCMTokenManager
    @Inject lateinit var logUploader: LogUploader

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    @Volatile private var currentlyExecutingTaskId: String? = null
    @Volatile private var executionStartedAt: Long = 0

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

        Log.i(TAG, "Starting worker mode — device: ${Build.MANUFACTURER} ${Build.MODEL}, SDK ${Build.VERSION.SDK_INT}")
        isRunning = true

        // Start foreground with notification
        serviceScope.launch {
            val todayEarnings = getTodayEarnings()
            val completedToday = networkTaskRepository.countCompletedToday()
            startForeground(
                NOTIFICATION_ID,
                createNotification(
                    activeTasksCount = 0,
                    todayEarnings = todayEarnings,
                    completedToday = completedToday
                )
            )
        }

        // Schedule heartbeat worker
        HeartbeatWorker.schedule(workManager)

        // Initialize FCM token
        fcmTokenManager.initializeToken()

        // Schedule fallback polling
        FallbackPollingWorker.schedule(workManager)

        // Schedule periodic log uploads
        LogUploadWorker.schedule(workManager)

        // Upload pending logs on start
        serviceScope.launch(Dispatchers.IO) {
            try {
                logUploader.uploadPendingLogs()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload logs on start", e)
            }
        }

        // Crash recovery: check for reserved tasks from previous session
        recoverReservedTasks()

        // Start polling for tasks
        startPolling()
    }

    private fun stopWorkerMode() {
        Log.i(TAG, "Stopping worker mode")
        isRunning = false

        // Cancel heartbeat worker
        HeartbeatWorker.cancel(workManager)

        // Cancel fallback polling
        FallbackPollingWorker.cancel(workManager)

        // Cancel log upload worker
        LogUploadWorker.cancel(workManager)

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

    /**
     * Crash recovery: check for reserved tasks that were assigned while app was killed.
     *
     * FCM notifications might have arrived while the app was not running,
     * so we check the backend for any pending reservations on startup.
     */
    private fun recoverReservedTasks() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val result = networkTaskRepository.getReservedTasks()
                if (result.isSuccess) {
                    val reservedTasks = result.getOrThrow()
                    if (reservedTasks.isNotEmpty()) {
                        Log.i(TAG, "Crash recovery: found ${reservedTasks.size} reserved tasks")
                        for (taskResponse in reservedTasks) {
                            val taskId = taskResponse.id
                            // Use coordinator to prevent race with FCM handler
                            if (!TaskAcceptanceCoordinator.tryStartAccepting(taskId)) {
                                Log.d(TAG, "Crash recovery: task $taskId already being accepted, skipping")
                                continue
                            }
                            try {
                                val acceptResult = networkTaskRepository.acceptTask(taskId)
                                if (acceptResult.isSuccess) {
                                    Log.i(TAG, "Crash recovery: accepted task $taskId")
                                } else {
                                    Log.w(TAG, "Crash recovery: failed to accept $taskId: ${acceptResult.exceptionOrNull()?.message}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Crash recovery: error accepting task $taskId", e)
                            } finally {
                                TaskAcceptanceCoordinator.finishAccepting(taskId)
                            }
                        }
                    } else {
                        Log.d(TAG, "Crash recovery: no reserved tasks found")
                    }
                } else {
                    Log.w(TAG, "Crash recovery: failed to get reserved tasks: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Crash recovery: error", e)
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

        // Prevent concurrent task execution (with safety timeout)
        if (currentlyExecutingTaskId != null) {
            val executionAge = System.currentTimeMillis() - executionStartedAt
            if (executionAge > MAX_EXECUTION_MS) {
                Log.w(TAG, "Execution timeout: clearing stuck flag for $currentlyExecutingTaskId (${executionAge / 1000}s)")
                currentlyExecutingTaskId = null
                releaseWakeLock()
            } else {
                Log.d(TAG, "Task already executing: $currentlyExecutingTaskId (${executionAge / 1000}s)")
                return
            }
        }

        // Poll backend for reserved tasks (FCM fallback - critical for Xiaomi/MIUI)
        try {
            val reservedResult = networkTaskRepository.getReservedTasks()
            if (reservedResult.isSuccess) {
                val reservedTasks = reservedResult.getOrThrow()
                for (taskResponse in reservedTasks) {
                    val taskId = taskResponse.id
                    if (!TaskAcceptanceCoordinator.tryStartAccepting(taskId)) {
                        continue
                    }
                    try {
                        val acceptResult = networkTaskRepository.acceptTask(taskId)
                        if (acceptResult.isSuccess) {
                            Log.i(TAG, "Poll: accepted reserved task $taskId")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Poll: error accepting task $taskId", e)
                    } finally {
                        TaskAcceptanceCoordinator.finishAccepting(taskId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Poll: failed to check reserved tasks", e)
        }

        // Get next scheduled task from local DB
        val task = networkTaskRepository.getNextScheduledTask()

        // Check daily limit
        val completedToday = networkTaskRepository.countCompletedToday()
        val maxDailyPosts = 5  // TODO: Get from app config
        val todayEarnings = getTodayEarnings()

        if (task == null) {
            Log.d(TAG, "No tasks ready for execution")
            updateNotification(
                activeTasksCount = 0,
                todayEarnings = todayEarnings,
                completedToday = completedToday,
                maxDaily = maxDailyPosts,
                nextPollSeconds = (POLL_INTERVAL_MS / 1000).toInt()
            )
            return
        }

        Log.i(TAG, "Found task ready for execution: ${task.id}")

        if (completedToday >= maxDailyPosts) {
            Log.w(TAG, "Daily limit reached: $completedToday/$maxDailyPosts")
            updateNotification(
                activeTasksCount = 0,
                todayEarnings = todayEarnings,
                completedToday = completedToday,
                maxDaily = maxDailyPosts,
                statusMessage = "Дневной лимит достигнут"
            )
            return
        }

        // Mark task as currently executing
        currentlyExecutingTaskId = task.id
        executionStartedAt = System.currentTimeMillis()

        // Acquire wake lock for execution
        acquireWakeLock()

        // Schedule task execution via WorkManager
        scheduleTaskExecution(task.id)

        // Update notification
        updateNotification(
            activeTasksCount = 1,
            todayEarnings = todayEarnings,
            completedToday = completedToday,
            maxDaily = maxDailyPosts
        )
    }

    private fun scheduleTaskExecution(taskId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(NetworkTaskWorker.KEY_TASK_ID, taskId)
            .build()

        val uniqueWorkName = "network_task_$taskId"

        val request = OneTimeWorkRequestBuilder<NetworkTaskWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        // Enqueue first, then observe — KEEP policy may discard request if already running
        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request)

        // Observe by unique work name (not request.id) — works even if KEEP discarded our request
        serviceScope.launch {
            try {
                withTimeout(MAX_EXECUTION_MS) {
                    val finishedWorkInfo = workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
                        .first { infos -> infos.all { it.state.isFinished } }

                    val state = finishedWorkInfo.firstOrNull()?.state
                    Log.i(TAG, "Task execution finished: $taskId (state=$state)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error/timeout observing task completion: $taskId", e)
            } finally {
                if (currentlyExecutingTaskId == taskId) {
                    currentlyExecutingTaskId = null
                    releaseWakeLock()
                }
            }
        }
        Log.i(TAG, "Scheduled task execution: $taskId (unique=$uniqueWorkName)")
    }

    private suspend fun getTodayEarnings(): Float {
        return try {
            workerRepository.getTodayEarnings()
        } catch (e: Exception) {
            0f
        }
    }

    private fun createNotification(
        activeTasksCount: Int,
        todayEarnings: Float,
        completedToday: Int = 0,
        maxDaily: Int = 5,
        nextPollSeconds: Int = 30,
        statusMessage: String? = null
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dynamic title based on state
        val title = when {
            activeTasksCount > 0 -> getString(R.string.worker_notif_executing)
            completedToday >= maxDaily -> getString(R.string.worker_notif_limit_reached, completedToday, maxDaily)
            else -> getString(R.string.worker_notif_active)
        }

        // Dynamic text based on state
        val text = when {
            statusMessage != null -> statusMessage
            activeTasksCount > 0 -> getString(R.string.worker_notif_posting)
            completedToday > 0 -> getString(R.string.worker_notif_progress, completedToday, maxDaily, todayEarnings)
            else -> getString(R.string.worker_notif_waiting, nextPollSeconds)
        }

        // Expanded big text with full details
        val status = getString(if (activeTasksCount > 0) R.string.worker_status_working else R.string.worker_status_waiting)
        val bigText = getString(
            R.string.worker_notif_big_text,
            status,
            completedToday,
            maxDaily,
            todayEarnings
        )

        return NotificationCompat.Builder(this, App.CHANNEL_POSTING)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setShowWhen(true)
            .setUsesChronometer(activeTasksCount > 0)  // Shows running timer during execution
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_cancel,
                "Остановить",
                createStopPendingIntent()
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Less intrusive (no sound/vibration)
            .build()
    }

    private fun updateNotification(
        activeTasksCount: Int,
        todayEarnings: Float,
        completedToday: Int = 0,
        maxDaily: Int = 5,
        nextPollSeconds: Int = 30,
        statusMessage: String? = null
    ) {
        val notification = createNotification(
            activeTasksCount,
            todayEarnings,
            completedToday,
            maxDaily,
            nextPollSeconds,
            statusMessage
        )
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
        releaseWakeLock()

        // Best-effort: try to notify backend we're going offline
        // Not guaranteed (Android can kill process without callback)
        // Use GlobalScope to ensure this runs independently of serviceScope cancellation
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                withTimeout(3000) {  // 3 second timeout
                    workerRepository.sendWorkerOffline()
                    Log.i(TAG, "Sent offline notification to backend")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send offline notification (expected)", e)
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }
}
