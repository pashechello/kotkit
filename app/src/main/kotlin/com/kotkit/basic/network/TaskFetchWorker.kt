package com.kotkit.basic.network

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotkit.basic.data.repository.NetworkTaskRepository
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker that fetches available tasks from backend.
 * Can be triggered by:
 * - FCM notification
 * - Periodic fallback
 * - Manual user action
 *
 * After claiming a task, directly schedules NetworkTaskWorker for execution
 * (doesn't rely on NetworkWorkerService polling which may be killed by MIUI).
 */
@HiltWorker
class TaskFetchWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val networkTaskRepository: NetworkTaskRepository,
    private val workerRepository: WorkerRepository,
    private val workManager: WorkManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "TaskFetchWorker running (tags: ${tags.joinToString()})")

        // Check worker mode active
        if (!workerRepository.isWorkerActive()) {
            Log.d(TAG, "Worker mode not active, skipping")
            return Result.success()
        }

        try {
            // Fetch available tasks from backend
            val result = networkTaskRepository.fetchAvailableTasks(limit = 10)

            val tasks = result.getOrNull()
            if (tasks == null) {
                Log.w(TAG, "Failed to fetch tasks: ${result.exceptionOrNull()?.message}")
                return Result.retry()
            }

            if (tasks.isEmpty()) {
                Log.d(TAG, "No tasks available")
                return Result.success()
            }

            Log.i(TAG, "Found ${tasks.size} available tasks")

            // Auto-claim first task that's NOT already in local DB
            for (task in tasks) {
                val existing = networkTaskRepository.getTaskById(task.id)
                if (existing != null) {
                    Log.d(TAG, "Task ${task.id} already in local DB (status=${existing.status}), skipping")
                    continue
                }

                val claimResult = networkTaskRepository.claimTask(task.id)
                claimResult.onSuccess { claimed ->
                    Log.i(TAG, "Auto-claimed task: ${claimed.id} (scheduledFor=${claimed.scheduledFor})")
                    // Schedule execution directly via WorkManager
                    // (don't rely on NetworkWorkerService polling - MIUI may kill it)
                    scheduleExecution(claimed.id, claimed.scheduledFor)
                }.onFailure { e ->
                    Log.d(TAG, "Could not claim task ${task.id}: ${e.message}")
                }
                break // Only claim one task per cycle
            }

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Task fetch failed", e)
            return Result.retry()
        }
    }

    private fun scheduleExecution(taskId: String, scheduledFor: Long?) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = Data.Builder()
            .putString(NetworkTaskWorker.KEY_TASK_ID, taskId)
            .build()

        val requestBuilder = OneTimeWorkRequestBuilder<NetworkTaskWorker>()
            .setConstraints(constraints)
            .setInputData(data)

        // Respect scheduled posting time from behavior profile
        if (scheduledFor != null && scheduledFor > System.currentTimeMillis()) {
            val delayMs = scheduledFor - System.currentTimeMillis()
            requestBuilder.setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            Log.i(TAG, "Task $taskId delayed by ${delayMs / 1000 / 60}min (scheduledFor=$scheduledFor)")
        }

        workManager.enqueueUniqueWork(
            "network_task_$taskId",
            ExistingWorkPolicy.KEEP,
            requestBuilder.build()
        )
        Log.i(TAG, "Scheduled execution for claimed task $taskId")
    }

    companion object {
        private const val TAG = "TaskFetchWorker"
    }
}
