package com.kotkit.basic.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * BroadcastReceiver for handling Smart Scheduler alarms.
 *
 * Handles:
 * - Warning notifications (10min, 1min before)
 * - Start posting
 * - Retry posting
 * - Cancel posting
 *
 * Uses supervised coroutine scope with timeout to avoid leaks and ensure
 * work completes within goAsync() time limit (10 seconds).
 */
@AndroidEntryPoint
class SchedulerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SchedulerReceiver"
        private const val WORK_TIMEOUT_MS = 9_000L // 9 seconds (leave 1 second buffer)
    }

    @Inject
    lateinit var smartScheduler: SmartScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val postId = intent.getLongExtra(SmartScheduler.EXTRA_POST_ID, -1)
        if (postId == -1L) {
            Timber.tag(TAG).e("Invalid post ID in intent")
            return
        }

        Timber.tag(TAG).i("Received action: ${intent.action} for post: $postId")

        val pendingResult = goAsync()

        // Use a supervised scope with timeout to prevent leaks
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                withTimeout(WORK_TIMEOUT_MS) {
                    handleAction(intent, postId)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Timber.tag(TAG).e("Operation timed out for post: $postId", e)
            } catch (e: Exception) {
                Timber.tag(TAG).e("Error handling scheduler action for post: $postId", e)
            } finally {
                // Always finish the pending result and cancel the scope
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun handleAction(intent: Intent, postId: Long) {
        when (intent.action) {
            SmartScheduler.ACTION_WARNING -> {
                val warningMinutes = intent.getIntExtra(SmartScheduler.EXTRA_WARNING_MINUTES, 10)
                smartScheduler.onWarningAlarm(postId, warningMinutes)
            }

            SmartScheduler.ACTION_FINAL_WARNING -> {
                smartScheduler.onWarningAlarm(postId, 1)
            }

            SmartScheduler.ACTION_START_POSTING -> {
                val retryCount = intent.getIntExtra(SmartScheduler.EXTRA_RETRY_COUNT, 0)
                smartScheduler.onPostingAlarm(postId, retryCount)
            }

            SmartScheduler.ACTION_RETRY_POST -> {
                val retryCount = intent.getIntExtra(SmartScheduler.EXTRA_RETRY_COUNT, 0)
                smartScheduler.onPostingAlarm(postId, retryCount)
            }

            SmartScheduler.ACTION_CANCEL_POST -> {
                Timber.tag(TAG).i("User cancelled post: $postId")
                smartScheduler.cancelPost(postId)
                SchedulerNotifications.cancelAllNotifications(
                    smartScheduler.getContext(),
                    postId
                )
            }

            SmartScheduler.ACTION_FORCE_PUBLISH -> {
                Timber.tag(TAG).i("User requested force publish: $postId")
                smartScheduler.forcePublish(postId)
            }

            else -> {
                Timber.tag(TAG).w("Unknown action: ${intent.action}")
            }
        }
    }
}
