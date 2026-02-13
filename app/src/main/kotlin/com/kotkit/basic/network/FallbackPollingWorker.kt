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
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Fallback periodic polling worker.
 *
 * Runs every 30 minutes (Android OS manages actual timing).
 * Only runs when:
 * - Device not in Doze mode (or during maintenance window)
 * - Network available
 * - Worker mode active
 *
 * This acts as a safety net for missed FCM notifications.
 */
@HiltWorker
class FallbackPollingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val taskCoordinator: NetworkTaskCoordinator,
    private val workerRepository: WorkerRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Fallback polling worker running")

        if (!workerRepository.isWorkerActive()) {
            Log.d(TAG, "Worker mode not active, skipping")
            return Result.success()
        }

        // Trigger task check via coordinator
        taskCoordinator.triggerTaskCheck(
            immediate = true,
            reason = "fallback_polling"
        )

        return Result.success()
    }

    companion object {
        private const val TAG = "FallbackPolling"
        private const val UNIQUE_WORK_NAME = "fallback_polling"
        private const val INTERVAL_MINUTES = 30L

        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .build()

            val request = PeriodicWorkRequestBuilder<FallbackPollingWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Fallback polling scheduled (every $INTERVAL_MINUTES min)")
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.i(TAG, "Fallback polling cancelled")
        }
    }
}
