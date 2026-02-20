package com.kotkit.basic.network

import android.content.Context
import android.graphics.Bitmap
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages proof screenshots for network tasks.
 *
 * Screenshots are captured after successful posting as proof of completion.
 */
@Singleton
class ScreenshotManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ScreenshotManager"
        private const val SCREENSHOTS_DIR = "proof_screenshots"
        private const val QUALITY = 85
    }

    /**
     * Capture and save a proof screenshot.
     *
     * Note: This requires accessibility service or MediaProjection.
     * Currently saves a placeholder - actual screenshot capture needs
     * to be integrated with the accessibility service.
     *
     * @param taskId Task ID for filename
     * @return Screenshot file path or null if capture failed
     */
    suspend fun captureProofScreenshot(taskId: String): String? = withContext(Dispatchers.IO) {
        try {
            val screenshotFile = getScreenshotFile(taskId)

            // TODO: Integrate with accessibility service for actual screenshot capture
            // For now, this is a placeholder that should be called from PostingAgent
            // after successful posting

            if (screenshotFile.exists()) {
                Timber.tag(TAG).i("Screenshot already exists for task $taskId")
                return@withContext screenshotFile.absolutePath
            }

            // Return null - screenshot should be saved by PostingAgent
            Timber.tag(TAG).d("No screenshot available for task $taskId")
            null

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to capture screenshot for task $taskId")
            null
        }
    }

    /**
     * Save a screenshot bitmap.
     *
     * Called from PostingAgent after capturing the screen.
     */
    suspend fun saveScreenshot(taskId: String, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val file = getScreenshotFile(taskId)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }

            Timber.tag(TAG).i("Screenshot saved for task $taskId: ${file.absolutePath}")
            file.absolutePath

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save screenshot for task $taskId")
            null
        }
    }

    /**
     * Get screenshot file for a task.
     */
    fun getScreenshotFile(taskId: String): File {
        val dir = File(context.filesDir, SCREENSHOTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$taskId.jpg")
    }

    /**
     * Delete screenshot for a task.
     */
    fun deleteScreenshot(taskId: String): Boolean {
        val file = getScreenshotFile(taskId)
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    /**
     * Check if screenshot exists for a task.
     */
    fun hasScreenshot(taskId: String): Boolean {
        return getScreenshotFile(taskId).exists()
    }

    /**
     * Cleanup old screenshots.
     */
    suspend fun cleanupOldScreenshots(maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, SCREENSHOTS_DIR)
        if (!dir.exists()) return@withContext

        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                if (file.delete()) {
                    Timber.tag(TAG).i("Cleaned up old screenshot: ${file.name}")
                }
            }
        }
    }
}
