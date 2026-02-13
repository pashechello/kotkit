package com.kotkit.basic.network

import android.content.Context
import android.util.Log
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

            Log.i(TAG, "Heartbeat worker scheduled (every $INTERVAL_MINUTES min)")
        }

        /**
         * Cancel heartbeat worker.
         */
        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.i(TAG, "Heartbeat worker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Heartbeat worker running")

        // Check if worker mode is still active
        val isActive = workerRepository.isWorkerActive()
        if (!isActive) {
            Log.d(TAG, "Worker mode not active, skipping heartbeat")
            return Result.success()
        }

        try {
            // 1. Send WORKER-level heartbeat (indicates worker is alive and available)
            //    This is independent of task heartbeats - even idle workers send this
            val workerHeartbeatResult = workerRepository.sendWorkerHeartbeat()
            if (workerHeartbeatResult.isSuccess) {
                Log.d(TAG, "Worker heartbeat sent successfully")
            } else {
                Log.w(TAG, "Worker heartbeat failed: ${workerHeartbeatResult.exceptionOrNull()?.message}")
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
                        Log.w(TAG, "Task ${task.id} rejected by server (${error.code()}), removing from local DB")
                        networkTaskRepository.removeStaleTask(task.id)
                        staleCleaned++
                    }
                }
            }

            if (activeTasks.isNotEmpty()) {
                Log.i(TAG, "Sent $taskHeartbeatsSent/${activeTasks.size} task heartbeats" +
                    if (staleCleaned > 0) ", cleaned $staleCleaned stale" else "")
            }

            // 3. Sync any pending task updates (completions/failures)
            val synced = networkTaskRepository.syncPendingTasks()
            if (synced > 0) {
                Log.i(TAG, "Synced $synced pending task updates")
            }

            // 4. Refresh balance/stats periodically
            workerRepository.fetchBalance()

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat worker failed", e)
            // Retry on next interval
            return Result.success() // Don't fail the worker, just log
        }
    }
}
