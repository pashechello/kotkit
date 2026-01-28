package com.kotkit.basic.executor.screenshot

import android.graphics.BitmapFactory
import android.util.Base64
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    /**
     * Compare two screenshots using pixel sampling.
     * Returns true if screens are DIFFERENT (changed).
     *
     * Uses bitmap decoding and samples pixels at grid points to detect
     * meaningful changes (not just JPEG compression artifacts).
     *
     * Used for publish success detection (like autobot phash).
     */
    fun screensChanged(before: String, after: String, threshold: Double = 0.85): Boolean {
        return try {
            // Decode base64 to bytes
            val beforeBytes = Base64.decode(before, Base64.DEFAULT)
            val afterBytes = Base64.decode(after, Base64.DEFAULT)

            // Decode to Bitmap for pixel comparison
            val beforeBitmap = BitmapFactory.decodeByteArray(beforeBytes, 0, beforeBytes.size)
            val afterBitmap = BitmapFactory.decodeByteArray(afterBytes, 0, afterBytes.size)

            if (beforeBitmap == null || afterBitmap == null) {
                Timber.tag("ScreenshotManager").w("Failed to decode bitmaps")
                return true  // Assume changed on decode error
            }

            // Different dimensions = definitely changed
            if (beforeBitmap.width != afterBitmap.width || beforeBitmap.height != afterBitmap.height) {
                beforeBitmap.recycle()
                afterBitmap.recycle()
                Timber.tag("ScreenshotManager").d("Screen changed: different dimensions")
                return true
            }

            // Sample pixels at grid points (faster than full comparison)
            val gridSize = 10  // 10x10 grid = 100 sample points
            val stepX = beforeBitmap.width / gridSize
            val stepY = beforeBitmap.height / gridSize
            var matchingPixels = 0
            var totalSamples = 0

            for (gridX in 0 until gridSize) {
                for (gridY in 0 until gridSize) {
                    val x = gridX * stepX + stepX / 2
                    val y = gridY * stepY + stepY / 2

                    if (x < beforeBitmap.width && y < beforeBitmap.height) {
                        val pixelBefore = beforeBitmap.getPixel(x, y)
                        val pixelAfter = afterBitmap.getPixel(x, y)
                        if (pixelBefore == pixelAfter) {
                            matchingPixels++
                        }
                        totalSamples++
                    }
                }
            }

            beforeBitmap.recycle()
            afterBitmap.recycle()

            val similarity = if (totalSamples > 0) matchingPixels.toDouble() / totalSamples else 0.0
            val changed = similarity < threshold

            Timber.tag("ScreenshotManager").d(
                "Screen comparison: similarity=${String.format("%.2f", similarity)}, " +
                "threshold=$threshold, changed=$changed"
            )
            changed
        } catch (e: Exception) {
            Timber.tag("ScreenshotManager").w("Screenshot comparison failed: ${e.message}")
            true  // Assume changed on error (safer for publish detection)
        }
    }
}

sealed class CaptureResult {
    data class Success(val base64: String, val sizeBytes: Int) : CaptureResult()
    data class Failed(val reason: String) : CaptureResult()
}
