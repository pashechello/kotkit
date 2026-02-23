package com.kotkit.basic.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import timber.log.Timber
import com.kotkit.basic.agent.PostResult
import com.kotkit.basic.agent.PostingAgent
import com.kotkit.basic.data.local.db.entities.NetworkTaskEntity
import com.kotkit.basic.data.local.db.entities.PostEntity
import com.kotkit.basic.data.local.db.entities.PostStatus
import com.kotkit.basic.data.local.db.entities.NetworkTaskStatus
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.ErrorType
import com.kotkit.basic.data.remote.api.models.ProxyConnectRequest
import com.kotkit.basic.data.remote.api.models.ProxyDisconnectRequest
import com.kotkit.basic.data.repository.NetworkTaskRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.kotkit.basic.data.repository.WorkerRepository
import com.kotkit.basic.proxy.ProxyConfig
import com.kotkit.basic.proxy.ProxyManager
import com.kotkit.basic.proxy.toDomain
import com.kotkit.basic.scheduler.DeviceStateChecker
import com.kotkit.basic.scheduler.PublishCheckResult
import com.kotkit.basic.scheduler.PublishConditions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
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
    private val screenshotManager: ScreenshotManager,
    private val deviceStateChecker: DeviceStateChecker,
    private val workerRepository: WorkerRepository,
    private val proxyManager: ProxyManager,
    private val apiService: ApiService
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
        Timber.tag(TAG).i("Starting execution of task ${task.id}")

        // Duplicate video protection is handled server-side in task_scheduler.py
        // (excludes workers who already posted a task with the same video_hash).
        // Client-side Room DB may contain stale/zombie completions, so we don't check here.

        // Track video path for cleanup on exception
        var downloadedVideoPath: String? = null
        // Proxy teardown tracking — proxyConnected guards the finally block
        var proxySessionId: String? = null
        var proxyExitIp: String? = null
        var proxyConnected = false

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

            // Check URL expiry (expiresAt is in seconds, currentTimeMillis in milliseconds)
            if (System.currentTimeMillis() / 1000 >= videoInfo.expiresAt) {
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
            downloadedVideoPath = videoPath  // Track for cleanup on exception
            networkTaskRepository.updateDownloadProgress(task.id, videoPath, 100)

            // ====================================================================
            // Stage 2.5: Wait for screen off (don't interrupt user)
            // ====================================================================
            onProgress(ExecutionStage.WAITING_SCREEN_OFF, 0)

            if (!waitForScreenOff(maxWaitMinutes = 8)) {
                Timber.tag(TAG).w("Screen stayed on too long for task ${task.id}, will retry later")
                return ExecutionResult.Retry(reason = "Screen stayed on for too long, posting deferred")
            }

            // ====================================================================
            // Stage 2.7: Connect Proxy
            // ====================================================================
            onProgress(ExecutionStage.CONNECTING_PROXY, 0)

            val proxyConfigResult = runCatching { apiService.getProxyConfig(task.id) }
            if (proxyConfigResult.isSuccess) {
                val proxyResponse = proxyConfigResult.getOrThrow()
                if (proxyResponse.required && proxyResponse.proxy != null) {
                    val config: ProxyConfig = proxyResponse.proxy.toDomain(task.id)
                    proxySessionId = config.sessionId
                    try {
                        proxyExitIp = proxyManager.connect(config)
                        proxyConnected = true  // Mark connected ONLY after success
                        // Report connect to server (best-effort)
                        runCatching {
                            apiService.reportProxyConnect(
                                config.sessionId,
                                ProxyConnectRequest(task.id, config.sessionId, proxyExitIp)
                            )
                        }
                        onProgress(ExecutionStage.CONNECTING_PROXY, 100)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Proxy connection failed for task ${task.id}")
                        return handleError(
                            task = task,
                            errorType = ErrorType.PROXY_CONNECT_FAILED,
                            message = "Proxy connection failed: ${e.message}"
                        )
                    }
                } else {
                    Timber.tag(TAG).i("Proxy not required for task ${task.id}")
                    onProgress(ExecutionStage.CONNECTING_PROXY, 100)
                }
            } else {
                Timber.tag(TAG).e("Failed to get proxy config for task ${task.id}: ${proxyConfigResult.exceptionOrNull()?.message}")
                return handleError(
                    task = task,
                    errorType = ErrorType.PROXY_CONFIG_FAILED,
                    message = "Could not get proxy config: ${proxyConfigResult.exceptionOrNull()?.message}"
                )
            }

            // ====================================================================
            // Stage 3: Post to TikTok
            // ====================================================================
            onProgress(ExecutionStage.POSTING, 0)
            networkTaskRepository.updateProgress(task.id, NetworkTaskStatus.POSTING, 0)

            // Create a mock PostEntity for the PostingAgent
            val mockPost = PostEntity(
                id = task.id.hashCode().toLong(),
                videoPath = videoPath,
                caption = task.caption ?: "",
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
            // NOTE: proxy disconnect happens in the finally block below,
            //       covering all exit paths (success, error, cancellation)
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

            // Use video ID/URL from PostResult if available (clipboard extraction)
            // Otherwise null - server will discover via yt-dlp scraping
            val tiktokVideoId = postSuccess.tiktokVideoId
            val tiktokPostUrl = postSuccess.tiktokPostUrl

            val completeResult = networkTaskRepository.completeTask(
                taskId = task.id,
                tiktokVideoId = tiktokVideoId,
                tiktokPostUrl = tiktokPostUrl,
                proofScreenshotPath = screenshotPath,
                proofScreenshotB64 = screenshotB64
            )

            if (completeResult.isFailure) {
                Timber.tag(TAG).w("Failed to sync completion (will retry later): ${completeResult.exceptionOrNull()?.message}")
                // Task is marked locally as completed with pending sync
            } else {
                // Sync earnings from server so foreground notification shows correct amount
                try {
                    workerRepository.fetchEarnings()
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Failed to sync earnings after completion: ${e.message}")
                }
            }

            // ====================================================================
            // Stage 6: Cleanup
            // ====================================================================
            videoDownloader.deleteVideo(task.id)

            onProgress(ExecutionStage.COMPLETED, 100)
            Timber.tag(TAG).i("Task ${task.id} completed successfully")

            return ExecutionResult.Success(
                tiktokVideoId = tiktokVideoId,
                tiktokPostUrl = tiktokPostUrl,
                screenshotPath = screenshotPath
            )

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error executing task ${task.id}")
            // Proxy teardown handled in finally below
            return handleError(
                task = task,
                errorType = ErrorType.UNKNOWN_ERROR,
                message = "Unexpected error: ${e.message}",
                captureScreenshot = true,
                cleanup = downloadedVideoPath != null,
                videoPath = downloadedVideoPath
            )
        } finally {
            // Guaranteed proxy teardown — runs on ALL exit paths:
            // success return, early return (PostResult.Failed/Retry), exception, cancellation.
            // NonCancellable ensures this completes even if the coroutine is being cancelled.
            if (proxyConnected) {
                withContext(NonCancellable) {
                    onProgress(ExecutionStage.DISCONNECTING_PROXY, 0)
                    try { proxyManager.disconnect() } catch (_: Exception) {}
                    runCatching {
                        apiService.reportProxyDisconnect(
                            proxySessionId!!,
                            ProxyDisconnectRequest(task.id, proxySessionId!!, proxyExitIp)
                        )
                    }
                    onProgress(ExecutionStage.DISCONNECTING_PROXY, 100)
                }
            }
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
        Timber.tag(TAG).e("Task ${task.id} failed: [$errorType] $message")

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

    /**
     * Wait for the screen to turn off before posting.
     * Polls every 5 seconds, gives up after maxWaitMinutes.
     */
    private suspend fun waitForScreenOff(maxWaitMinutes: Int = 30): Boolean {
        val conditions = PublishConditions(requireScreenOff = true)
        if (deviceStateChecker.canPublish(conditions) is PublishCheckResult.Ready) {
            Timber.tag(TAG).i("Screen already off, proceeding to post")
            return true
        }

        Timber.tag(TAG).i("Screen is on, waiting up to ${maxWaitMinutes}min for screen off...")

        val maxWaitMs = maxWaitMinutes * 60_000L
        val pollIntervalMs = 5_000L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            delay(pollIntervalMs)
            if (deviceStateChecker.canPublish(conditions) is PublishCheckResult.Ready) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                Timber.tag(TAG).i("Screen turned off after ${elapsed}s, proceeding to post")
                return true
            }
        }

        Timber.tag(TAG).w("Screen stayed on for ${maxWaitMinutes}min, giving up")
        return false
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
            Timber.tag(TAG).e(e, "Failed to encode screenshot")
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
    WAITING_SCREEN_OFF,
    CONNECTING_PROXY,
    POSTING,
    DISCONNECTING_PROXY,
    VERIFYING,
    COMPLETING,
    COMPLETED
}

/**
 * Task execution result.
 */
sealed class ExecutionResult {
    data class Success(
        val tiktokVideoId: String?,
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
