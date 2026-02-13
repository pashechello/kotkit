package com.kotkit.basic.network

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates task checking from multiple sources:
 * - FCM push notifications (primary)
 * - Periodic WorkManager (fallback)
 * - Manual triggers
 */
@Singleton
class NetworkTaskCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager
) {
    /**
     * Trigger task check.
     *
     * @param immediate If true, runs immediately. If false, schedules with delay.
     * @param reason Source of trigger (for analytics)
     */
    fun triggerTaskCheck(immediate: Boolean = true, reason: String = "unknown") {
        Log.i(TAG, "Triggering task check: immediate=$immediate, reason=$reason")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<TaskFetchWorker>()
            .setConstraints(constraints)
            .addTag(reason)
            .build()

        // Use unique work to prevent duplicate executions
        workManager.enqueueUniqueWork(
            "task_fetch_$reason",
            ExistingWorkPolicy.REPLACE,
            request
        )

        Log.d(TAG, "Task check scheduled (reason: $reason)")
    }

    companion object {
        private const val TAG = "TaskCoordinator"
    }
}
