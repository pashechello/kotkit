package com.autoposter.scheduler

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.autoposter.App
import com.autoposter.R
import com.autoposter.agent.PostingAgent
import com.autoposter.agent.PostResult
import com.autoposter.data.local.db.entities.PostStatus
import com.autoposter.data.repository.PostRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PostWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val postRepository: PostRepository,
    private val postingAgent: PostingAgent
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PostWorker"
        private const val NOTIFICATION_ID_BASE = 10000
    }

    override suspend fun doWork(): Result {
        val postId = inputData.getLong("post_id", -1)
        if (postId == -1L) {
            Log.e(TAG, "Invalid post ID")
            return Result.failure()
        }

        val post = postRepository.getById(postId)
        if (post == null) {
            Log.e(TAG, "Post not found: $postId")
            return Result.failure()
        }

        // Show foreground notification
        setForeground(createForegroundInfo(post.id, post.caption))

        // Update status to posting
        postRepository.updateStatus(postId, PostStatus.POSTING)

        Log.i(TAG, "Starting post execution for ID: $postId")

        // Execute posting
        return when (val result = postingAgent.executePost(post)) {
            is PostResult.Success -> {
                postRepository.updateStatus(postId, PostStatus.COMPLETED)
                showNotification(
                    postId,
                    "Posted Successfully",
                    result.message ?: "Your video has been posted to TikTok",
                    success = true
                )
                Log.i(TAG, "Post completed successfully: $postId")
                Result.success()
            }
            is PostResult.Failed -> {
                postRepository.updateStatus(postId, PostStatus.FAILED, result.reason)
                showNotification(
                    postId,
                    "Post Failed",
                    result.reason,
                    success = false
                )
                Log.e(TAG, "Post failed: $postId - ${result.reason}")
                Result.failure()
            }
            is PostResult.NeedUserAction -> {
                postRepository.updateStatus(postId, PostStatus.NEEDS_ACTION, result.message)
                showNotification(
                    postId,
                    "Action Required",
                    result.message,
                    success = false
                )
                Log.w(TAG, "Post needs user action: $postId - ${result.message}")
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(postId: Long, caption: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, App.CHANNEL_POSTING)
            .setContentTitle("Posting to TikTok")
            .setContentText(caption.take(50) + if (caption.length > 50) "..." else "")
            .setSmallIcon(R.drawable.ic_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return ForegroundInfo((NOTIFICATION_ID_BASE + postId).toInt(), notification)
    }

    private fun showNotification(
        postId: Long,
        title: String,
        message: String,
        success: Boolean
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(if (success) R.drawable.ic_check else R.drawable.ic_error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((NOTIFICATION_ID_BASE + postId + 1000).toInt(), notification)
    }
}
