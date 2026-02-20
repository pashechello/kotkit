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
import timber.log.Timber
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
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import com.kotkit.basic.executor.screenshot.MediaProjectionScreenshot
import com.kotkit.basic.ui.components.SnackbarController
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
        private const val KILLED_NOTIFICATION_ID = 10002
        private const val ACCESSIBILITY_DEAD_NOTIFICATION_ID = 10003
        private const val WAKE_LOCK_TAG = "KotKit::NetworkWorker"

        /**
         * Huawei/Honor's hwPfwService has a hardcoded whitelist of wake lock tags.
         * Processes holding wake locks with these tags are exempt from aggressive
         * background killing. Using "AudioMix" keeps our process alive on EMUI/MagicOS.
         * See: https://dontkillmyapp.com/huawei
         */
        private const val HUAWEI_SENTINEL_TAG = "AudioMix"
        private const val POLL_INTERVAL_MS = 60_000L // 60 seconds (reduced, FCM is primary now)
        private const val MAX_WAKE_LOCK_MS = 30 * 60 * 1000L // 30 minutes per task
        private const val MAX_EXECUTION_MS = 25 * 60 * 1000L // 25 min safety timeout for execution flag

        private const val PREFS_NAME = "worker_service_prefs"
        private const val KEY_SHOULD_BE_RUNNING = "should_be_running"

        const val ACTION_START = "com.kotkit.basic.network.START"
        const val ACTION_STOP = "com.kotkit.basic.network.STOP"

        /**
         * Whether the service instance is currently alive in this process.
         * Reset to false when process is killed by OEM — used by ServiceResurrector.
         */
        @Volatile
        var isServiceAlive: Boolean = false
            private set

        /**
         * Whether worker mode was intentionally activated by the user.
         * Persisted to SharedPreferences so it survives process death.
         * Used by ServiceResurrector to decide whether to restart.
         */
        fun shouldBeRunning(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOULD_BE_RUNNING, false)
        }

        private fun setShouldBeRunning(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOULD_BE_RUNNING, value).apply()
        }

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
    private var sentinelWakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    private var stoppedByUser = false
    @Volatile private var currentlyExecutingTaskId: String? = null
    @Volatile private var executionStartedAt: Long = 0
    private var lastAccessibilityAlive = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("NetworkWorkerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).i("onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId, isRunning=$isRunning")

        when (intent?.action) {
            ACTION_START -> startWorkerMode()
            ACTION_STOP -> stopWorkerMode()
            null -> {
                // Null intent = Android restarting service after process kill (START_STICKY).
                // Must re-enter startWorkerMode() to restore polling, heartbeat, resurrector.
                Timber.tag(TAG).w("START_STICKY restart (null intent) — re-initializing worker mode")
                if (shouldBeRunning(this)) {
                    startWorkerMode()
                } else {
                    Timber.tag(TAG).i("shouldBeRunning=false after START_STICKY, stopping self")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startWorkerMode() {
        if (isRunning) {
            Timber.tag(TAG).d("Worker mode already running")
            return
        }

        Timber.tag(TAG).i("Starting worker mode — device: ${Build.MANUFACTURER} ${Build.MODEL}, SDK ${Build.VERSION.SDK_INT}")

        // Reset restart throttler on user-initiated start (so it's never throttled)
        RestartThrottler.reset(this)

        isRunning = true
        isServiceAlive = true
        setShouldBeRunning(this, true)

        // Call startForeground immediately — before any async work — to satisfy the 5s ANR deadline.
        // EMUI and other OEMs may slow down the first DB query enough to miss the deadline.
        // Notification shows zero counts initially; updated asynchronously below.
        val notification = createNotification(activeTasksCount = 0, todayEarnings = 0f, completedToday = 0)

        // Determine foreground service type explicitly.
        // On API 29: include mediaProjection type (if consent granted) for VirtualDisplay screenshot fallback.
        // On API 30+: only dataSync — screenshots use AccessibilityService.takeScreenshot().
        // CRITICAL: Never call startForeground() without an explicit type when the manifest declares
        // mediaProjection — Android 14+ infers ALL manifest types, triggering SecurityException
        // if mediaProjection consent hasn't been obtained.
        val fgsType = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && MediaProjectionScreenshot.isAvailable) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, notification, fgsType)

        // Update notification with real data asynchronously
        serviceScope.launch {
            val todayEarnings = getTodayEarnings()
            val completedToday = networkTaskRepository.countCompletedToday()
            updateNotification(
                activeTasksCount = 0,
                todayEarnings = todayEarnings,
                completedToday = completedToday
            )
        }

        // Schedule heartbeat worker
        HeartbeatWorker.schedule(workManager)

        // Initialize FCM token
        fcmTokenManager.initializeToken()

        // Schedule fallback polling
        FallbackPollingWorker.schedule(workManager)

        // Schedule AlarmManager-based resurrector (survives OEM process kills)
        ServiceResurrector.schedule(this)

        // Schedule periodic log uploads
        LogUploadWorker.schedule(workManager)

        // Upload pending logs on start
        serviceScope.launch(Dispatchers.IO) {
            try {
                logUploader.uploadPendingLogs()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to upload logs on start")
            }
        }

        // Warn if API 29 worker restarted without MediaProjection (process death)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !MediaProjectionScreenshot.isAvailable) {
            Timber.tag(TAG).e("API ${Build.VERSION.SDK_INT} worker started without MediaProjection — " +
                "screenshots unavailable. User must re-toggle Worker Mode to re-acquire consent.")
        }

        // Crash recovery: check for reserved tasks from previous session
        recoverReservedTasks()

        // Crash recovery: fail tasks that were stuck mid-execution when app was killed
        recoverAbandonedTasks()

        // Huawei/Honor: acquire sentinel wake lock to prevent hwPfwService from killing us
        acquireSentinelWakeLock()

        // Start polling for tasks
        startPolling()
    }

    private fun stopWorkerMode() {
        Timber.tag(TAG).i("Stopping worker mode")
        stoppedByUser = true
        isRunning = false
        isServiceAlive = false
        setShouldBeRunning(this, false)

        // Release MediaProjection (API 29 screenshot fallback)
        MediaProjectionScreenshot.release()

        // Cancel AlarmManager resurrector
        ServiceResurrector.cancel(this)

        // Cancel heartbeat worker
        HeartbeatWorker.cancel(workManager)

        // Cancel fallback polling
        FallbackPollingWorker.cancel(workManager)

        // Cancel log upload worker
        LogUploadWorker.cancel(workManager)

        // Stop polling
        pollingJob?.cancel()
        pollingJob = null

        // Release wake locks
        releaseWakeLock()
        releaseSentinelWakeLock()

        // Clear accessibility watchdog notification if shown
        clearAccessibilityDeadNotification()

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
                    Timber.tag(TAG).e(e, "Error in polling loop")
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
                        Timber.tag(TAG).i("Crash recovery: found ${reservedTasks.size} reserved tasks")
                        for (taskResponse in reservedTasks) {
                            val taskId = taskResponse.id
                            // Use coordinator to prevent race with FCM handler
                            if (!TaskAcceptanceCoordinator.tryStartAccepting(taskId)) {
                                Timber.tag(TAG).d("Crash recovery: task $taskId already being accepted, skipping")
                                continue
                            }
                            try {
                                val acceptResult = networkTaskRepository.acceptTask(taskId)
                                if (acceptResult.isSuccess) {
                                    Timber.tag(TAG).i("Crash recovery: accepted task $taskId")
                                } else {
                                    Timber.tag(TAG).w("Crash recovery: failed to accept $taskId: ${acceptResult.exceptionOrNull()?.message}")
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Crash recovery: error accepting task $taskId")
                            } finally {
                                TaskAcceptanceCoordinator.finishAccepting(taskId)
                            }
                        }
                    } else {
                        Timber.tag(TAG).d("Crash recovery: no reserved tasks found")
                    }
                } else {
                    Timber.tag(TAG).w("Crash recovery: failed to get reserved tasks: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Crash recovery: error")
            }
        }
    }

    /**
     * Crash recovery: fail tasks that were stuck mid-execution when app process was killed.
     *
     * If MIUI kills the process during task execution, WorkManager loses the job
     * and the task stays in assigned/downloading/posting locally but never completes.
     * This proactively reports failure so the server can reassign immediately
     * instead of waiting 15-20 min for zombie detection.
     */
    private fun recoverAbandonedTasks() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val activeTasks = networkTaskRepository.getActiveTasks()
                if (activeTasks.isEmpty()) return@launch

                for (task in activeTasks) {
                    // Don't fail tasks that are currently being executed by this service instance
                    if (task.id == currentlyExecutingTaskId) continue

                    Timber.tag(TAG).w("Crash recovery: found abandoned task ${task.id} in status ${task.status}")
                    networkTaskRepository.failTask(
                        taskId = task.id,
                        errorMessage = "App process killed during execution",
                        errorType = "app_crash",
                        screenshotB64 = null
                    )
                }

                if (activeTasks.isNotEmpty()) {
                    Timber.tag(TAG).i("Crash recovery: reported ${activeTasks.size} abandoned tasks as failed")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Crash recovery: error cleaning abandoned tasks")
            }
        }
    }

    private suspend fun checkAndExecuteTask() {
        // Accessibility watchdog: detect if OEM killed the service
        val accessibilityAlive = TikTokAccessibilityService.getInstance() != null
        if (lastAccessibilityAlive && !accessibilityAlive) {
            Timber.tag(TAG).e("WATCHDOG: Accessibility service DIED (killed by system)")
            showAccessibilityDeadNotification()
            SnackbarController.showError(getString(R.string.worker_accessibility_dead_title))
        } else if (!lastAccessibilityAlive && accessibilityAlive) {
            Timber.tag(TAG).i("WATCHDOG: Accessibility service recovered")
            clearAccessibilityDeadNotification()
        }
        lastAccessibilityAlive = TikTokAccessibilityService.getInstance() != null

        // Check if worker mode is still active
        if (!workerRepository.isWorkerActive()) {
            Timber.tag(TAG).d("Worker mode disabled, stopping service")
            stopWorkerMode()
            return
        }

        // Prevent concurrent task execution (with safety timeout)
        if (currentlyExecutingTaskId != null) {
            val executionAge = System.currentTimeMillis() - executionStartedAt
            if (executionAge > MAX_EXECUTION_MS) {
                Timber.tag(TAG).w("Execution timeout: clearing stuck flag for $currentlyExecutingTaskId (${executionAge / 1000}s)")
                currentlyExecutingTaskId = null
                releaseWakeLock()
            } else {
                Timber.tag(TAG).d("Task already executing: $currentlyExecutingTaskId (${executionAge / 1000}s)")
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
                            Timber.tag(TAG).i("Poll: accepted reserved task $taskId")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Poll: error accepting task $taskId")
                    } finally {
                        TaskAcceptanceCoordinator.finishAccepting(taskId)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Poll: failed to check reserved tasks")
        }

        // Get next scheduled task from local DB
        val task = networkTaskRepository.getNextScheduledTask()

        // Check daily limit
        val completedToday = networkTaskRepository.countCompletedToday()
        val maxDailyPosts = 5  // TODO: Get from app config
        val todayEarnings = getTodayEarnings()

        if (task == null) {
            Timber.tag(TAG).d("No tasks ready for execution")
            updateNotification(
                activeTasksCount = 0,
                todayEarnings = todayEarnings,
                completedToday = completedToday,
                maxDaily = maxDailyPosts,
                nextPollSeconds = (POLL_INTERVAL_MS / 1000).toInt()
            )
            return
        }

        Timber.tag(TAG).i("Found task ready for execution: ${task.id}")

        if (completedToday >= maxDailyPosts) {
            Timber.tag(TAG).w("Daily limit reached: $completedToday/$maxDailyPosts")
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
                    Timber.tag(TAG).i("Task execution finished: $taskId (state=$state)")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error/timeout observing task completion: $taskId")
            } finally {
                if (currentlyExecutingTaskId == taskId) {
                    currentlyExecutingTaskId = null
                    releaseWakeLock()
                }
            }
        }
        Timber.tag(TAG).i("Scheduled task execution: $taskId (unique=$uniqueWorkName)")
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
            .setOnlyAlertOnce(true)
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
            .setSilent(true)  // Suppress channel sound (meow) — money button has its own sound
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
        Timber.tag(TAG).d("Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.tag(TAG).d("Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Acquire a sentinel wake lock for the entire worker mode session.
     * Only on Huawei/Honor: uses whitelisted tag "AudioMix" so hwPfwService
     * exempts this process from aggressive background killing.
     *
     * Battery impact: partial wake lock (CPU only, no screen).
     * Trade-off is acceptable since worker mode is opt-in for earning money.
     */
    private fun acquireSentinelWakeLock() {
        if (!isHuaweiOrHonor()) return
        if (sentinelWakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        sentinelWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            HUAWEI_SENTINEL_TAG
        ).apply {
            acquire()
        }
        Timber.tag(TAG).i("Sentinel wake lock acquired (Huawei/Honor protection, tag=$HUAWEI_SENTINEL_TAG)")
    }

    private fun releaseSentinelWakeLock() {
        sentinelWakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.tag(TAG).i("Sentinel wake lock released")
            }
        }
        sentinelWakeLock = null
    }

    private fun isHuaweiOrHonor(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        return manufacturer.contains("huawei") || manufacturer.contains("honor")
    }

    override fun onDestroy() {
        val wasRunning = isRunning
        val persistedShouldRun = shouldBeRunning(this)
        Timber.tag(TAG).w("NetworkWorkerService DESTROYED: wasRunning=$wasRunning, stoppedByUser=$stoppedByUser, " +
            "shouldBeRunning=$persistedShouldRun, pid=${android.os.Process.myPid()}")
        isRunning = false
        isServiceAlive = false
        // Note: DON'T clear shouldBeRunning here — if OEM killed the process,
        // ServiceResurrector needs this flag to know it should restart the service.
        pollingJob?.cancel()
        releaseWakeLock()
        releaseSentinelWakeLock()

        // Release MediaProjection if still held (API 29 fallback)
        MediaProjectionScreenshot.release()

        // Show notification if service was killed by system (not by user)
        if (wasRunning && !stoppedByUser) {
            showServiceKilledNotification()
        }

        // Best-effort: try to notify backend we're going offline
        // Not guaranteed (Android can kill process without callback)
        // Use GlobalScope to ensure this runs independently of serviceScope cancellation
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                withTimeout(3000) {  // 3 second timeout
                    workerRepository.sendWorkerOffline()
                    Timber.tag(TAG).i("Sent offline notification to backend")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to send offline notification (expected)")
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showServiceKilledNotification() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, KILLED_NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
                .setContentTitle("Воркер остановлен")
                .setContentText("Система остановила сервис. Нажмите, чтобы перезапустить.")
                .setSmallIcon(R.drawable.ic_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(KILLED_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to show service-killed notification")
        }
    }

    private fun showAccessibilityDeadNotification() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, ACCESSIBILITY_DEAD_NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
                .setContentTitle(getString(R.string.worker_accessibility_dead_title))
                .setContentText(getString(R.string.worker_accessibility_dead_message))
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.worker_accessibility_dead_instructions)))
                .setSmallIcon(R.drawable.ic_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(ACCESSIBILITY_DEAD_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to show accessibility-dead notification")
        }
    }

    private fun clearAccessibilityDeadNotification() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(ACCESSIBILITY_DEAD_NOTIFICATION_ID)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to clear accessibility-dead notification")
        }
    }
}
