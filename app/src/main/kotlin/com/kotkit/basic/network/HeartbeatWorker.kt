package com.kotkit.basic.network

import android.content.Context
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that sends heartbeat signals to the backend.
 *
 * Purpose:
 * - Keep backend informed that worker is alive
 * - Prevent tasks from being reclaimed as "zombies"
 * - Sync any pending task updates
 *
 * Runs every 5 minutes while worker mode is active.
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val networkTaskRepository: NetworkTaskRepository,
    private val workerRepository: WorkerRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val UNIQUE_WORK_NAME = "network_heartbeat_worker"
        private const val INTERVAL_MINUTES = 5L

        /**
         * Schedule periodic heartbeat worker.
         */
        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Timber.tag(TAG).i("Heartbeat worker scheduled (every $INTERVAL_MINUTES min)")
        }

        /**
         * Cancel heartbeat worker.
         */
        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            Timber.tag(TAG).i("Heartbeat worker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Timber.tag(TAG).d("Heartbeat worker running")

        // Check if worker mode is still active
        val isActive = workerRepository.isWorkerActive()
        if (!isActive) {
            Timber.tag(TAG).d("Worker mode not active, skipping heartbeat")
            return Result.success()
        }

        try {
            // 1. Send WORKER-level heartbeat (indicates worker is alive and available)
            //    This is independent of task heartbeats - even idle workers send this
            val workerHeartbeatResult = workerRepository.sendWorkerHeartbeat()
            if (workerHeartbeatResult.isSuccess) {
                Timber.tag(TAG).d("Worker heartbeat sent successfully")
            } else {
                Timber.tag(TAG).w("Worker heartbeat failed: ${workerHeartbeatResult.exceptionOrNull()?.message}")
            }

            // 2. Send TASK-level heartbeats for all active tasks
            val activeTasks = networkTaskRepository.getActiveTasks()
            var taskHeartbeatsSent = 0
            var staleCleaned = 0

            for (task in activeTasks) {
                val result = networkTaskRepository.sendHeartbeat(task.id)
                if (result.isSuccess) {
                    taskHeartbeatsSent++
                } else {
                    // Clean up stale tasks that server no longer recognizes (400/403)
                    val error = result.exceptionOrNull()
                    if (error is HttpException && error.code() in listOf(400, 403, 404)) {
                        Timber.tag(TAG).w("Task ${task.id} rejected by server (${error.code()}), removing from local DB")
                        networkTaskRepository.removeStaleTask(task.id)
                        staleCleaned++
                    }
                }
            }

            if (activeTasks.isNotEmpty()) {
                Timber.tag(TAG).i("Sent $taskHeartbeatsSent/${activeTasks.size} task heartbeats" +
                    if (staleCleaned > 0) ", cleaned $staleCleaned stale" else "")
            }

            // 3. Sync any pending task updates (completions/failures)
            val synced = networkTaskRepository.syncPendingTasks()
            if (synced > 0) {
                Timber.tag(TAG).i("Synced $synced pending task updates")
            }

            // 4. Refresh balance/stats periodically
            workerRepository.fetchBalance()

            // 5. Safety net: clean up locally-stuck tasks (>30 min with no active execution)
            // Catches cases where NetworkWorkerService.recoverAbandonedTasks() didn't run
            // (e.g. user never reopened the app after MIUI killed it)
            //
            // Note: Backend reclaims stuck tasks after 15 min, so by 30 min the task
            // may already be re-assigned. We handle 403/404 gracefully to avoid log noise.
            try {
                val now = System.currentTimeMillis()
                val stuckThresholdMs = 30 * 60 * 1000L  // 30 minutes
                for (task in activeTasks) {
                    val taskAge = now - (task.assignedAt ?: task.updatedAt ?: 0)
                    if (taskAge > stuckThresholdMs) {
                        Timber.tag(TAG).w("Cleaning abandoned task ${task.id} (age=${taskAge / 1000}s, status=${task.status})")
                        val result = networkTaskRepository.failTask(
                            taskId = task.id,
                            errorMessage = "Task abandoned locally (${taskAge / 60000}min)",
                            errorType = "app_crash",
                            screenshotB64 = null
                        )
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()
                            if (error is HttpException && error.code() in listOf(403, 404)) {
                                // Task was already reclaimed by backend — just clean up locally
                                Timber.tag(TAG).d("Task ${task.id} already reclaimed by server, removing locally")
                                networkTaskRepository.removeStaleTask(task.id)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to clean up stuck tasks")
            }

            return Result.success()

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Heartbeat worker failed")
            // Retry on next interval
            return Result.success() // Don't fail the worker, just log
        }
    }
}
