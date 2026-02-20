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
import com.kotkit.basic.data.repository.WorkerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that uploads device logs every 15 minutes.
 * Only runs when worker mode is active and network is available.
 */
@HiltWorker
class LogUploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val logUploader: LogUploader,
    private val workerRepository: WorkerRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.tag(TAG).d("Log upload worker running")

        if (!workerRepository.isWorkerActive()) {
            Timber.tag(TAG).d("Worker mode not active, skipping log upload")
            return Result.success()
        }

        logUploader.uploadPendingLogs()
        return Result.success()
    }

    companion object {
        private const val TAG = "LogUploadWorker"
        private const val UNIQUE_WORK_NAME = "log_upload_periodic"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build()

            val request = PeriodicWorkRequestBuilder<LogUploadWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Timber.tag(TAG).i("Log upload scheduled (every $INTERVAL_MINUTES min)")
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            Timber.tag(TAG).i("Log upload cancelled")
        }
    }
}
