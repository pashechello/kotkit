package com.kotkit.basic.scheduler

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.kotkit.basic.App
import com.kotkit.basic.R
import com.kotkit.basic.agent.PostingAgent
import com.kotkit.basic.agent.PostResult
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.repository.AnalyticsRepository
import com.kotkit.basic.data.repository.PostRepository
import com.kotkit.basic.sound.MeowSoundService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

@HiltWorker
class PostWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val postRepository: PostRepository,
    private val postingAgent: PostingAgent,
    private val meowSoundService: MeowSoundService,
    private val analyticsRepository: AnalyticsRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PostWorker"
        private const val NOTIFICATION_ID_BASE = 10000
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        val postId = inputData.getLong("post_id", -1)
        if (postId == -1L) {
            Timber.tag(TAG).e("Invalid post ID")
            return Result.failure()
        }

        val post = postRepository.getById(postId)
        if (post == null) {
            Timber.tag(TAG).e("Post not found: $postId")
            return Result.failure()
        }

        // Track timing and session for analytics
        val startTime = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()

        // Show foreground notification (may fail on Android 12+ if app is in background)
        try {
            setForeground(createForegroundInfo(post.id, post.caption))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Timber.tag(TAG).w("Cannot start foreground service from background, using backup notification", e)
            // Fallback: show regular notification with stop button
            SchedulerNotifications.showStartingNotification(context, post.id)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to set foreground info, using backup notification", e)
            // Fallback: show regular notification with stop button
            SchedulerNotifications.showStartingNotification(context, post.id)
        }

        // Update status to posting
        postRepository.updateStatus(postId, PostStatus.POSTING)

        // Track post started
        analyticsRepository.trackPostStarted(postId, sessionId)

        Timber.tag(TAG).i("Starting post execution for ID: $postId")

        // Execute posting
        return when (val result = postingAgent.executePost(post)) {
            is PostResult.Success -> {
                val durationMs = System.currentTimeMillis() - startTime
                postRepository.updateStatus(postId, PostStatus.COMPLETED)

                // Track successful completion
                analyticsRepository.trackPostCompleted(postId, sessionId, durationMs)

                // Delete video file after successful posting
                postRepository.deleteVideoFile(post.videoPath)

                // Play success sound
                meowSoundService.playSuccess()

                // Show rich notification with sound
                SchedulerNotifications.showSuccessNotification(
                    context,
                    postId,
                    result.message ?: "Видео опубликовано в TikTok"
                )
                Timber.tag(TAG).i("Post completed successfully: $postId (${durationMs}ms)")
                Result.success()
            }
            is PostResult.Failed -> {
                val durationMs = System.currentTimeMillis() - startTime

                // Check if this is a transient error that should be retried
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    // Track retry attempt
                    analyticsRepository.trackPostRetrying(postId, sessionId, runAttemptCount + 1)

                    // Retry for transient errors (network, TikTok crash, etc)
                    postRepository.updateStatus(postId, PostStatus.SCHEDULED, "Retrying (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS): ${result.reason}")
                    Timber.tag(TAG).w("Post failed (attempt $runAttemptCount), will retry: $postId - ${result.reason}")
                    Result.retry()  // Video kept for retry
                } else {
                    // Track permanent failure
                    analyticsRepository.trackPostFailed(postId, sessionId, result.reason, durationMs)

                    // Permanent failure after max retries
                    postRepository.updateStatus(postId, PostStatus.FAILED, result.reason)

                    // Delete video file after final failure
                    postRepository.deleteVideoFile(post.videoPath)

                    // Play error sound
                    meowSoundService.playError()

                    // Show failure notification
                    SchedulerNotifications.showFailedNotification(
                        context,
                        postId,
                        "${result.reason} (после $MAX_RETRY_ATTEMPTS попыток)"
                    )
                    Timber.tag(TAG).e("Post failed permanently after $MAX_RETRY_ATTEMPTS attempts: $postId - ${result.reason}")
                    Result.failure()
                }
            }
            is PostResult.NeedUserAction -> {
                val durationMs = System.currentTimeMillis() - startTime

                // Track as failure requiring user action
                analyticsRepository.trackPostFailed(
                    postId, sessionId,
                    "User action required: ${result.message}",
                    durationMs
                )

                postRepository.updateStatus(postId, PostStatus.NEEDS_ACTION, result.message)

                // Play error sound - user intervention required
                meowSoundService.playError()

                // Show failure notification with action required message
                SchedulerNotifications.showFailedNotification(
                    context,
                    postId,
                    "Требуется действие: ${result.message}"
                )
                Timber.tag(TAG).w("Post needs user action: $postId - ${result.message}")
                Result.failure()
            }
            is PostResult.Retry -> {
                // Temporary condition (e.g., phone in pocket) - retry without counting against max attempts
                Timber.tag(TAG).i("Post temporarily blocked, will retry: $postId - ${result.reason}")
                postRepository.updateStatus(postId, PostStatus.SCHEDULED, "Rescheduled: ${result.reason}")
                Result.retry()  // WorkManager will reschedule with backoff
            }
        }
    }

    private fun createForegroundInfo(postId: Long, caption: String): ForegroundInfo {
        // Create stop action PendingIntent
        val stopIntent = Intent(context, StopPostingReceiver::class.java).apply {
            action = StopPostingReceiver.ACTION_STOP_POSTING
            putExtra("post_id", postId)  // Pass postId to cancel notification
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            postId.toInt(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cat-themed notification with stop button 🐱
        val largeIcon = BitmapFactory.decodeResource(context.resources, R.drawable.logo)
        val notification = NotificationCompat.Builder(context, App.CHANNEL_POSTING)
            .setContentTitle("🚀 Поехали!")
            .setContentText("Мур! Работаю над постом... 🐱")
            .setSmallIcon(R.drawable.ic_upload)
            .setLargeIcon(largeIcon)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // Required for actions to show
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(R.drawable.ic_cancel, "Остановить 🐾", stopPendingIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                (NOTIFICATION_ID_BASE + postId).toInt(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ForegroundInfo((NOTIFICATION_ID_BASE + postId).toInt(), notification)
        }
    }

}
