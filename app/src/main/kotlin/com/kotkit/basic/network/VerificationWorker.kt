package com.kotkit.basic.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.VerificationCompleteRequest
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker for Post & Check video verification.
 *
 * Runs every hour to check for pending video verifications.
 * Opens TikTok video URLs, takes screenshots, and reports results to backend.
 */
@HiltWorker
class VerificationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val apiService: ApiService
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "VerificationWorker"
        const val WORK_NAME = "video_verification_check"

        // Delays and timeouts
        private const val VIDEO_LOAD_DELAY_MS = 5000L
        private const val BETWEEN_VERIFICATIONS_DELAY_MS = 10000L
        private const val VERIFICATION_TIMEOUT_MS = 60000L

        /**
         * Schedule periodic verification checks.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<VerificationWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 15,
                flexTimeIntervalUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.i(TAG, "Verification worker scheduled (hourly)")
        }

        /**
         * Cancel scheduled verification checks.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Verification worker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting verification check...")

        return try {
            // 1. Fetch pending verifications from backend
            val pendingResponse = apiService.getPendingVerifications(limit = 5)
            val verifications = pendingResponse.verifications

            if (verifications.isEmpty()) {
                Log.d(TAG, "No pending verifications")
                return Result.success()
            }

            Log.i(TAG, "Found ${verifications.size} pending verifications")

            // 2. Process each verification
            var successCount = 0
            var failCount = 0

            for (verification in verifications) {
                try {
                    // Claim the verification
                    val claimResponse = apiService.claimVerification(verification.id)
                    Log.d(TAG, "Claimed verification ${verification.id}")

                    // Execute verification
                    val result = verifyVideo(verification.tiktokVideoUrl)

                    // Submit result
                    val request = VerificationCompleteRequest(
                        result = result.resultType,
                        screenshotB64 = result.screenshotB64,
                        viewCount = result.viewCount,
                        likeCount = result.likeCount,
                        errorMessage = result.errorMessage
                    )
                    apiService.completeVerification(verification.id, request)

                    Log.i(TAG, "Verification ${verification.id} completed: ${result.resultType}")
                    successCount++

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process verification ${verification.id}", e)
                    failCount++
                }

                // Delay between verifications
                delay(BETWEEN_VERIFICATIONS_DELAY_MS)
            }

            Log.i(TAG, "Verification check complete: success=$successCount, failed=$failCount")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Verification worker failed", e)
            Result.retry()
        }
    }

    /**
     * Verify a TikTok video exists and has engagement.
     */
    private suspend fun verifyVideo(videoUrl: String): VerificationCheckResult {
        Log.d(TAG, "Verifying video: $videoUrl")

        try {
            // Step 1: Open URL via Intent (will open in TikTok app)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                return VerificationCheckResult(
                    resultType = "error",
                    errorMessage = "Cannot open TikTok URL: ${e.message}"
                )
            }

            // Step 2: Wait for TikTok to open and load content
            delay(VIDEO_LOAD_DELAY_MS)

            // Step 3: Get AccessibilityService and check state
            val service = TikTokAccessibilityService.getInstance()
            if (service == null) {
                return VerificationCheckResult(
                    resultType = "error",
                    errorMessage = "AccessibilityService not available"
                )
            }

            if (!service.isTikTokInForeground()) {
                return VerificationCheckResult(
                    resultType = "error",
                    errorMessage = "TikTok not in foreground"
                )
            }

            // Step 4: Take screenshot
            val screenshot = service.takeScreenshot()
            val screenshotB64 = if (screenshot != null) {
                val stream = ByteArrayOutputStream()
                screenshot.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } else {
                null
            }

            // Step 5: Analyze screen state
            val result = analyzeScreen(service, screenshotB64)

            // Step 6: Press back to return
            service.pressBack()
            delay(500)

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Video verification failed", e)
            return VerificationCheckResult(
                resultType = "error",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Analyze current screen to determine video state.
     */
    private fun analyzeScreen(
        service: TikTokAccessibilityService,
        screenshotB64: String?
    ): VerificationCheckResult {
        // Get UI tree to analyze
        val uiTree = service.getUITree()

        // Check for error indicators
        val errorTexts = listOf(
            "video unavailable",
            "video not found",
            "this video has been removed",
            "video was removed",
            "content is not available",
            "couldn't load",
            "видео недоступно",
            "видео удалено"
        )

        // Search for error text in UI tree
        for (element in uiTree.elements) {
            val text = element.text?.lowercase() ?: ""
            val contentDesc = element.contentDescription?.lowercase() ?: ""

            for (errorText in errorTexts) {
                if (text.contains(errorText) || contentDesc.contains(errorText)) {
                    Log.i(TAG, "Found error text: $errorText")
                    return VerificationCheckResult(
                        resultType = "video_deleted",
                        screenshotB64 = screenshotB64,
                        errorMessage = "Video unavailable: found '$errorText'"
                    )
                }
            }
        }

        // Try to find view count or like count
        var viewCount: Int? = null
        var likeCount: Int? = null

        for (element in uiTree.elements) {
            val text = element.text ?: continue

            // Look for patterns like "123K views", "1.5M", etc.
            if (element.resourceId?.contains("view") == true ||
                element.resourceId?.contains("play") == true) {
                viewCount = parseCount(text)
            }

            if (element.resourceId?.contains("like") == true ||
                element.resourceId?.contains("digg") == true) {
                likeCount = parseCount(text)
            }
        }

        // If we found engagement, video exists
        if (likeCount != null && likeCount > 0) {
            return VerificationCheckResult(
                resultType = "video_exists",
                screenshotB64 = screenshotB64,
                viewCount = viewCount,
                likeCount = likeCount
            )
        }

        // If view count is 0, might be shadowban
        if (viewCount == 0) {
            return VerificationCheckResult(
                resultType = "shadowban",
                screenshotB64 = screenshotB64,
                viewCount = 0,
                likeCount = 0
            )
        }

        // Assume video exists if no error found (conservative)
        return VerificationCheckResult(
            resultType = "video_exists",
            screenshotB64 = screenshotB64,
            viewCount = viewCount,
            likeCount = likeCount
        )
    }

    /**
     * Parse count from text like "123K", "1.5M", etc.
     */
    private fun parseCount(text: String): Int? {
        val cleanText = text.trim().lowercase()

        return try {
            when {
                cleanText.endsWith("k") -> {
                    val num = cleanText.dropLast(1).toDouble()
                    (num * 1000).toInt()
                }
                cleanText.endsWith("m") -> {
                    val num = cleanText.dropLast(1).toDouble()
                    (num * 1000000).toInt()
                }
                cleanText.endsWith("b") -> {
                    val num = cleanText.dropLast(1).toDouble()
                    (num * 1000000000).toInt()
                }
                else -> {
                    cleanText.replace(",", "").replace(" ", "").toIntOrNull()
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Result of a video verification check.
 */
data class VerificationCheckResult(
    val resultType: String,  // video_exists, video_deleted, shadowban, error
    val screenshotB64: String? = null,
    val viewCount: Int? = null,
    val likeCount: Int? = null,
    val errorMessage: String? = null
)
