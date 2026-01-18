package com.autoposter.executor.screenshot

import android.util.Base64
import com.autoposter.executor.accessibility.TikTokAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotManager @Inject constructor(
    private val imageCompressor: ImageCompressor
) {
    companion object {
        private const val DEFAULT_QUALITY = 50
        private const val MAX_WIDTH = 1080
        private const val MAX_HEIGHT = 1920
    }

    suspend fun capture(quality: Int = DEFAULT_QUALITY): CaptureResult = withContext(Dispatchers.IO) {
        try {
            val service = TikTokAccessibilityService.getInstance()
            if (service == null) {
                return@withContext CaptureResult.Failed("Accessibility service not available")
            }

            val bitmap = service.takeScreenshot()
            if (bitmap == null) {
                return@withContext CaptureResult.Failed("Screenshot capture failed")
            }

            // Compress for upload
            val compressed = imageCompressor.compressForUpload(
                bitmap,
                quality,
                MAX_WIDTH,
                MAX_HEIGHT
            )

            // Convert to Base64
            val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)

            bitmap.recycle()

            CaptureResult.Success(base64, compressed.size)
        } catch (e: Exception) {
            CaptureResult.Failed(e.message ?: "Unknown error during screenshot")
        }
    }

    /**
     * Capture screenshot and return raw bytes (not Base64)
     */
    suspend fun captureRaw(quality: Int = DEFAULT_QUALITY): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val service = TikTokAccessibilityService.getInstance() ?: return@withContext null

            val bitmap = service.takeScreenshot() ?: return@withContext null

            val compressed = imageCompressor.compressForUpload(
                bitmap,
                quality,
                MAX_WIDTH,
                MAX_HEIGHT
            )

            bitmap.recycle()

            compressed
        } catch (e: Exception) {
            null
        }
    }
}

sealed class CaptureResult {
    data class Success(val base64: String, val sizeBytes: Int) : CaptureResult()
    data class Failed(val reason: String) : CaptureResult()
}
