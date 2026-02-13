package com.kotkit.basic.network

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that accepts reserved tasks.
 *
 * Uses WorkManager instead of coroutine scope in FCM handler to survive
 * service lifecycle destruction (especially aggressive on MIUI/Xiaomi).
 *
 * After successful accept, schedules NetworkTaskWorker for execution.
 */
@HiltWorker
class TaskAcceptWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val networkTaskRepository: NetworkTaskRepository,
    private val workerRepository: WorkerRepository,
    private val workManager: WorkManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
        if (taskId == null) {
            Log.e(TAG, "No task_id in input data")
            return Result.failure()
        }

        // Check if worker mode is active
        if (!workerRepository.isWorkerActive()) {
            Log.w(TAG, "Worker mode not active, skipping accept for $taskId")
            return Result.failure()
        }

        // Check expiration
        val expiresAt = inputData.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt > 0) {
            val now = System.currentTimeMillis() / 1000
            if (now > expiresAt) {
                Log.w(TAG, "Reservation expired for $taskId (expired=$expiresAt, now=$now)")
                return Result.failure()
            }
        }

        Log.i(TAG, "Accepting reserved task: $taskId (attempt $runAttemptCount)")

        val result = networkTaskRepository.acceptTask(taskId)
        if (result.isSuccess) {
            val entity = result.getOrThrow()
            Log.i(TAG, "Accepted task $taskId, scheduling execution (scheduledFor=${entity.scheduledFor})")
            scheduleExecution(taskId, entity.scheduledFor)
            return Result.success()
        }

        val error = result.exceptionOrNull()
        Log.w(TAG, "Failed to accept $taskId: ${error?.message}")

        // Don't retry on 4xx client errors
        if (error is retrofit2.HttpException && error.code() in 400..499) {
            Log.w(TAG, "Client error ${error.code()}, not retrying")
            return Result.failure()
        }

        // Retry on network/5xx errors (WorkManager handles backoff)
        return if (runAttemptCount < MAX_RETRIES) {
            Log.d(TAG, "Will retry accept for $taskId")
            Result.retry()
        } else {
            Log.e(TAG, "Max retries reached for $taskId")
            Result.failure()
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
            requestBuilder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            Log.i(TAG, "Task $taskId delayed by ${delayMs / 1000 / 60}min (scheduledFor=$scheduledFor)")
        }

        workManager.enqueueUniqueWork(
            "network_task_$taskId",
            ExistingWorkPolicy.KEEP,
            requestBuilder.build()
        )
        Log.i(TAG, "Scheduled execution for task $taskId")
    }

    companion object {
        private const val TAG = "TaskAcceptWorker"
        private const val MAX_RETRIES = 3
        const val KEY_TASK_ID = "task_id"
        const val KEY_EXPIRES_AT = "expires_at"

        /**
         * Enqueue accept work for a reserved task.
         * Resilient to service lifecycle - survives FCM service destruction.
         */
        fun enqueue(context: Context, taskId: String, expiresAt: Long? = null) {
            val data = Data.Builder()
                .putString(KEY_TASK_ID, taskId)
                .apply {
                    if (expiresAt != null) putLong(KEY_EXPIRES_AT, expiresAt)
                }
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<TaskAcceptWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    5, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "accept_task_$taskId",
                ExistingWorkPolicy.KEEP, // Don't replace if already accepting
                request
            )

            Log.i(TAG, "Enqueued accept for task $taskId")
        }
    }
}
