package com.kotkit.basic.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.kotkit.basic.agent.PostResult
import com.kotkit.basic.agent.PostingAgent
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.local.db.entities.NetworkTaskStatus
import com.kotkit.basic.data.remote.api.models.ErrorType
import com.kotkit.basic.data.repository.NetworkTaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes network tasks: Download video → Post to TikTok → Verify → Report.
 *
 * This is the core execution engine for Network Mode.
 */
@Singleton
class NetworkTaskExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkTaskRepository: NetworkTaskRepository,
    private val videoDownloader: VideoDownloader,
    private val postingAgent: PostingAgent,
    private val screenshotManager: ScreenshotManager
) {
    companion object {
        private const val TAG = "NetworkTaskExecutor"
    }

    /**
     * Execute a network task end-to-end.
     *
     * Flow:
     * 1. Get video URL from backend
     * 2. Download video with resume support
     * 3. Post to TikTok via PostingAgent
     * 4. Capture proof screenshot
     * 5. Report completion/failure to backend
     * 6. Cleanup video file
     *
     * @param task Task to execute
     * @param onProgress Progress callback (stage, percent)
     * @return Execution result
     */
    suspend fun executeTask(
        task: NetworkTaskEntity,
        onProgress: (ExecutionStage, Int) -> Unit = { _, _ -> }
    ): ExecutionResult {
        Log.i(TAG, "Starting execution of task ${task.id}")

        try {
            // ====================================================================
            // Stage 1: Get Video URL
            // ====================================================================
            onProgress(ExecutionStage.GETTING_URL, 0)

            val videoInfoResult = networkTaskRepository.getVideoUrl(task.id)
            if (videoInfoResult.isFailure) {
                return handleError(
                    task = task,
                    errorType = ErrorType.NETWORK_TIMEOUT,
                    message = "Failed to get video URL: ${videoInfoResult.exceptionOrNull()?.message}"
                )
            }

            val videoInfo = videoInfoResult.getOrThrow()

            // Check URL expiry
            if (System.currentTimeMillis() >= videoInfo.expiresAt) {
                return handleError(
                    task = task,
                    errorType = ErrorType.NETWORK_TIMEOUT,
                    message = "Video URL expired, need to retry"
                )
            }

            // ====================================================================
            // Stage 2: Download Video
            // ====================================================================
            onProgress(ExecutionStage.DOWNLOADING, 0)
            networkTaskRepository.updateProgress(task.id, NetworkTaskStatus.DOWNLOADING, 0)

            val downloadResult = videoDownloader.downloadVideo(
                url = videoInfo.url,
                taskId = task.id,
                expectedHash = videoInfo.hash,
                expectedSize = videoInfo.sizeBytes,
                supportsResume = videoInfo.supportsResume,
                onProgress = { progress ->
                    onProgress(ExecutionStage.DOWNLOADING, (progress * 100).toInt())
                }
            )

            if (downloadResult.isFailure) {
                return handleError(
                    task = task,
                    errorType = ErrorType.VIDEO_DOWNLOAD_FAILED,
                    message = "Download failed: ${downloadResult.exceptionOrNull()?.message}"
                )
            }

            val videoPath = downloadResult.getOrThrow()
            networkTaskRepository.updateDownloadProgress(task.id, videoPath, 100)

            // ====================================================================
            // Stage 3: Post to TikTok
            // ====================================================================
            onProgress(ExecutionStage.POSTING, 0)
            networkTaskRepository.updateProgress(task.id, NetworkTaskStatus.POSTING, 0)

            // Create a mock PostEntity for the PostingAgent
            val mockPost = PostEntity(
                id = task.id.hashCode().toLong(),
                videoPath = videoPath,
                caption = task.caption,
                scheduledTime = System.currentTimeMillis(),
                status = PostStatus.POSTING
            )
            val postResult = postingAgent.executePost(mockPost)

            // Handle posting result and extract video info
            val postSuccess: PostResult.Success = when (postResult) {
                is PostResult.Success -> {
                    onProgress(ExecutionStage.POSTING, 100)
                    postResult
                }
                is PostResult.Failed -> {
                    return handleError(
                        task = task,
                        errorType = mapPostResultToErrorType(postResult.reason),
                        message = postResult.reason,
                        cleanup = true,
                        videoPath = videoPath
                    )
                }
                is PostResult.NeedUserAction -> {
                    return handleError(
                        task = task,
                        errorType = ErrorType.TIKTOK_CAPTCHA,
                        message = postResult.message,
                        cleanup = false, // Keep video for retry
                        videoPath = videoPath
                    )
                }
                is PostResult.Retry -> {
                    return ExecutionResult.Retry(postResult.reason)
                }
            }

            // ====================================================================
            // Stage 4: Capture Proof Screenshot
            // ====================================================================
            onProgress(ExecutionStage.VERIFYING, 0)
            networkTaskRepository.updateProgress(task.id, NetworkTaskStatus.VERIFYING, 0)

            val screenshotPath = screenshotManager.captureProofScreenshot(task.id)
            val screenshotB64 = screenshotPath?.let { encodeScreenshot(it) }

            onProgress(ExecutionStage.VERIFYING, 50)

            // ====================================================================
            // Stage 5: Report Completion
            // ====================================================================
            onProgress(ExecutionStage.COMPLETING, 0)

            // Use video ID from PostResult if available, otherwise generate from timestamp
            val tiktokVideoId = postSuccess.tiktokVideoId ?: "post_${System.currentTimeMillis()}"
            val tiktokPostUrl = postSuccess.tiktokPostUrl

            val completeResult = networkTaskRepository.completeTask(
                taskId = task.id,
                tiktokVideoId = tiktokVideoId,
                tiktokPostUrl = tiktokPostUrl,
                proofScreenshotPath = screenshotPath,
                proofScreenshotB64 = screenshotB64
            )

            if (completeResult.isFailure) {
                Log.w(TAG, "Failed to sync completion (will retry later): ${completeResult.exceptionOrNull()?.message}")
                // Task is marked locally as completed with pending sync
            }

            // ====================================================================
            // Stage 6: Cleanup
            // ====================================================================
            videoDownloader.deleteVideo(task.id)

            onProgress(ExecutionStage.COMPLETED, 100)
            Log.i(TAG, "Task ${task.id} completed successfully")

            return ExecutionResult.Success(
                tiktokVideoId = tiktokVideoId,
                tiktokPostUrl = tiktokPostUrl,
                screenshotPath = screenshotPath
            )

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error executing task ${task.id}", e)
            return handleError(
                task = task,
                errorType = ErrorType.UNKNOWN_ERROR,
                message = "Unexpected error: ${e.message}",
                captureScreenshot = true
            )
        }
    }

    private suspend fun handleError(
        task: NetworkTaskEntity,
        errorType: String,
        message: String,
        cleanup: Boolean = false,
        videoPath: String? = null,
        captureScreenshot: Boolean = false
    ): ExecutionResult.Failed {
        Log.e(TAG, "Task ${task.id} failed: [$errorType] $message")

        // Capture error screenshot if needed
        val screenshotB64 = if (captureScreenshot) {
            screenshotManager.captureProofScreenshot(task.id)?.let { encodeScreenshot(it) }
        } else null

        // Report failure to backend
        networkTaskRepository.failTask(
            taskId = task.id,
            errorMessage = message,
            errorType = errorType,
            screenshotB64 = screenshotB64
        )

        // Cleanup if requested
        if (cleanup && videoPath != null) {
            videoDownloader.deleteVideo(task.id)
        }

        return ExecutionResult.Failed(
            errorType = errorType,
            message = message
        )
    }

    private fun mapPostResultToErrorType(reason: String): String {
        return when {
            reason.contains("accessibility", ignoreCase = true) -> ErrorType.ACCESSIBILITY_DISABLED
            reason.contains("tiktok", ignoreCase = true) && reason.contains("not", ignoreCase = true) -> ErrorType.TIKTOK_APP_NOT_INSTALLED
            reason.contains("login", ignoreCase = true) -> ErrorType.TIKTOK_NOT_LOGGED_IN
            reason.contains("banned", ignoreCase = true) -> ErrorType.ACCOUNT_BANNED
            reason.contains("button", ignoreCase = true) -> ErrorType.BUTTON_NOT_FOUND
            reason.contains("timeout", ignoreCase = true) -> ErrorType.UPLOAD_TIMEOUT
            reason.contains("captcha", ignoreCase = true) -> ErrorType.TIKTOK_CAPTCHA
            else -> ErrorType.UNKNOWN_ERROR
        }
    }

    private fun encodeScreenshot(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists()) return null

            val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return null
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode screenshot", e)
            null
        }
    }
}

/**
 * Execution stages for progress tracking.
 */
enum class ExecutionStage {
    GETTING_URL,
    DOWNLOADING,
    POSTING,
    VERIFYING,
    COMPLETING,
    COMPLETED
}

/**
 * Task execution result.
 */
sealed class ExecutionResult {
    data class Success(
        val tiktokVideoId: String,
        val tiktokPostUrl: String?,
        val screenshotPath: String?
    ) : ExecutionResult()

    data class Failed(
        val errorType: String,
        val message: String
    ) : ExecutionResult()

    data class Retry(
        val reason: String
    ) : ExecutionResult()
}
