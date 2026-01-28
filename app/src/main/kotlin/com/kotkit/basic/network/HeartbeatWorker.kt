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
            // 1. Send heartbeat for all active tasks
            val activeTasks = networkTaskRepository.getActiveTasks()
            var heartbeatsSent = 0

            for (task in activeTasks) {
                val result = networkTaskRepository.sendHeartbeat(task.id)
                if (result.isSuccess) {
                    heartbeatsSent++
                }
            }

            if (activeTasks.isNotEmpty()) {
                Log.i(TAG, "Sent $heartbeatsSent/${activeTasks.size} heartbeats")
            }

            // 2. Sync any pending task updates (completions/failures)
            val synced = networkTaskRepository.syncPendingTasks()
            if (synced > 0) {
                Log.i(TAG, "Synced $synced pending task updates")
            }

            // 3. Refresh balance/stats periodically
            workerRepository.fetchBalance()

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat worker failed", e)
            // Retry on next interval
            return Result.success() // Don't fail the worker, just log
        }
    }
}
