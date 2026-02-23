package com.kotkit.basic.warmup

import android.content.Context
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.WarmupReportRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that runs a single warmup session.
 *
 * Enqueued by NetworkWorkerService when warmup conditions are met.
 * Uses ExistingWorkPolicy.KEEP with unique name "warmup_session"
 * to prevent concurrent sessions.
 */
@HiltWorker
class WarmupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val warmupAgent: WarmupAgent,
    private val warmupScheduler: WarmupScheduler,
    private val apiService: ApiService
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WarmupWorker"
        const val UNIQUE_WORK_NAME = "warmup_session"
    }

    override suspend fun doWork(): Result {
        Timber.tag(TAG).i("WarmupWorker started")
        warmupScheduler.isWarmupRunning = true

        try {
            val result = warmupAgent.execute()

            when (result) {
                is WarmupResult.Success -> {
                    Timber.tag(TAG).i("Warmup completed: ${result.stats}")
                    warmupScheduler.recordSessionComplete()
                    reportToServer("completed", result.stats)
                }
                is WarmupResult.Cancelled -> {
                    Timber.tag(TAG).i("Warmup cancelled: ${result.stats}")
                    // Cancelled by task arrival — still counts as a partial success
                    // Don't increment failure counter, but don't count as full session either
                    reportToServer("cancelled", result.stats)
                }
                is WarmupResult.Failed -> {
                    Timber.tag(TAG).w("Warmup failed: ${result.reason}")
                    warmupScheduler.recordSessionFailed(result.reason)
                    reportToServer("failed", result.stats, result.reason)
                }
            }

            return Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "WarmupWorker error")
            warmupScheduler.recordSessionFailed(e.message ?: "Unknown error")
            reportToServer("failed", WarmupStats(), e.message)
            return Result.failure()
        } finally {
            warmupScheduler.isWarmupRunning = false
            Timber.tag(TAG).i("WarmupWorker finished")
        }
    }

    private suspend fun reportToServer(
        status: String,
        stats: WarmupStats,
        errorMessage: String? = null
    ) {
        try {
            val response = apiService.reportWarmup(
                WarmupReportRequest(
                    status = status,
                    videosWatched = stats.videosWatched,
                    likesGiven = stats.likesGiven,
                    durationSeconds = stats.durationSeconds,
                    vlmRecoveryCalls = stats.vlmRecoveryCalls,
                    popupsDismissed = stats.popupsDismissed,
                    errorMessage = errorMessage
                )
            )
            Timber.tag(TAG).i("Warmup reported to server: id=${response.id}")
        } catch (e: Exception) {
            // Reporting failure should NOT fail the worker
            Timber.tag(TAG).w(e, "Failed to report warmup to server (non-fatal)")
        }
    }
}
