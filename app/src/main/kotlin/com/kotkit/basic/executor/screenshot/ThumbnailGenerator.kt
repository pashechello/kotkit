package com.kotkit.basic.executor.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailGenerator @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ThumbnailGenerator"
        private const val THUMBNAIL_WIDTH = 270
        private const val THUMBNAIL_HEIGHT = 480
        private const val THUMBNAIL_QUALITY = 80
    }

    /**
     * Generate thumbnail from video file and save to internal storage.
     * Extracts first frame of the video.
     *
     * @param videoPath Path to the video file
     * @return Path to saved thumbnail, or null if generation failed
     */
    fun generateThumbnail(videoPath: String): String? {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(videoPath)

            // Get frame at 0 microseconds (first frame)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            if (bitmap == null) {
                Timber.tag(TAG).w("Failed to extract frame from video: $videoPath")
                return null
            }

            // Scale bitmap to thumbnail size
            val scaledBitmap = scaleBitmap(bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)

            // Save to thumbnails directory
            val thumbnailPath = saveThumbnail(scaledBitmap)

            // Recycle bitmaps
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            Timber.tag(TAG).i("Generated thumbnail: $thumbnailPath")
            thumbnailPath

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to generate thumbnail for: $videoPath")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error releasing MediaMetadataRetriever: ${e.message}")
            }
        }
    }

    /**
     * Delete thumbnail file.
     */
    fun deleteThumbnail(thumbnailPath: String?) {
        if (thumbnailPath.isNullOrBlank()) return

        try {
            val file = File(thumbnailPath)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Timber.tag(TAG).d("Deleted thumbnail: $thumbnailPath")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to delete thumbnail: ${e.message}")
        }
    }

    private fun saveThumbnail(bitmap: Bitmap): String {
        val thumbnailsDir = File(context.filesDir, "thumbnails").apply {
            if (!exists()) mkdirs()
        }

        val fileName = "thumb_${UUID.randomUUID()}.jpg"
        val file = File(thumbnailsDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
        }

        return file.absolutePath
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratioWidth = maxWidth.toFloat() / width
        val ratioHeight = maxHeight.toFloat() / height
        val ratio = minOf(ratioWidth, ratioHeight)

        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
