package com.kotkit.basic.executor.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Captures a single screenshot using MediaProjection + VirtualDisplay + ImageReader.
 *
 * Used as fallback on API 29 where AccessibilityService.takeScreenshot() is unavailable.
 *
 * Design:
 * - MediaProjection is created once from the consent token and cached for reuse.
 *   On API 29, getMediaProjection() consumes the token Intent — it cannot be reused.
 * - VirtualDisplay is created on demand per screenshot and destroyed immediately after.
 *   This avoids continuous GPU compositing and saves battery.
 * - A dedicated HandlerThread is used for ImageReader callbacks.
 * - capture() is guarded by a Mutex to prevent concurrent VirtualDisplay creation.
 */
object MediaProjectionScreenshot {

    private const val TAG = "MPScreenshot"
    private const val VIRTUAL_DISPLAY_NAME = "KotKitScreenCapture"
    private const val CAPTURE_TIMEOUT_MS = 5000L

    @Volatile
    private var cachedProjection: MediaProjection? = null

    private val captureMutex = Mutex()

    /**
     * Create and cache a MediaProjection from the stored token.
     * Must be called once after consent is obtained. The token is consumed.
     */
    fun initialize(context: Context): Boolean {
        val token = MediaProjectionTokenHolder.data ?: return false
        val resultCode = MediaProjectionTokenHolder.resultCode
        if (resultCode == 0) return false

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        return try {
            val projection = projectionManager.getMediaProjection(resultCode, token)
            if (projection == null) {
                Timber.tag(TAG).e("getMediaProjection returned null")
                MediaProjectionTokenHolder.clear()
                return false
            }
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.tag(TAG).w("MediaProjection stopped by system")
                    cachedProjection = null
                    MediaProjectionTokenHolder.clear()
                }
            }, null)
            cachedProjection = projection
            Timber.tag(TAG).i("MediaProjection initialized successfully")
            true
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "SecurityException: foreground service with MEDIA_PROJECTION type " +
                "required before getMediaProjection() on API ${android.os.Build.VERSION.SDK_INT}")
            MediaProjectionTokenHolder.clear()
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize MediaProjection")
            MediaProjectionTokenHolder.clear()
            false
        }
    }

    /**
     * Release the cached MediaProjection. Call when Worker Mode stops.
     * Safe to call multiple times (idempotent).
     */
    fun release() {
        val projection = cachedProjection
        cachedProjection = null
        try {
            projection?.stop()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping MediaProjection")
        }
        Timber.tag(TAG).i("MediaProjection released")
    }

    val isAvailable: Boolean
        get() = cachedProjection != null

    /**
     * Capture a single screenshot using the cached MediaProjection.
     *
     * Creates a VirtualDisplay + ImageReader, grabs one frame, and destroys them.
     * Guarded by a Mutex to prevent concurrent captures.
     * Returns Bitmap or null on failure/timeout.
     */
    suspend fun capture(context: Context): Bitmap? = captureMutex.withLock {
        val projection = cachedProjection
        if (projection == null) {
            Timber.tag(TAG).e("No active MediaProjection")
            return@withLock null
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val handlerThread = HandlerThread("MPScreenshot").apply { start() }
        val handler = Handler(handlerThread.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        try {
            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = AtomicBoolean(false)

                    imageReader.setOnImageAvailableListener({ reader ->
                        if (!resumed.compareAndSet(false, true)) return@setOnImageAvailableListener

                        val image: Image? = try {
                            reader.acquireLatestImage()
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to acquire image")
                            null
                        }

                        if (image != null) {
                            val result = imageToBitmap(image, width, height)
                            image.close()
                            continuation.resume(result)
                        } else {
                            continuation.resume(null)
                        }
                    }, handler)

                    virtualDisplay = projection.createVirtualDisplay(
                        VIRTUAL_DISPLAY_NAME,
                        width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface,
                        null, handler
                    )

                    continuation.invokeOnCancellation {
                        resumed.set(true)
                    }
                }
            }

            if (bitmap != null) {
                Timber.tag(TAG).d("Screenshot captured: ${bitmap.width}x${bitmap.height}")
            } else {
                Timber.tag(TAG).e("Screenshot capture timed out or returned null")
            }

            return@withLock bitmap

        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "MediaProjection SecurityException (token revoked?)")
            cachedProjection = null
            MediaProjectionTokenHolder.clear()
            return@withLock null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "MediaProjection screenshot failed")
            return@withLock null
        } finally {
            virtualDisplay?.release()
            imageReader.close()
            handlerThread.quitSafely()
        }
    }

    /**
     * Convert an Image from ImageReader to a Bitmap.
     *
     * ImageReader with RGBA_8888 produces Images with a row stride that may be
     * larger than width * 4 bytes (due to padding). We must account for this.
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        return if (bitmapWidth != width) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }
}
