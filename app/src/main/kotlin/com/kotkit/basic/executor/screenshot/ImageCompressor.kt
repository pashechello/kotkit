package com.kotkit.basic.executor.screenshot

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

class ImageCompressor {

    /**
     * Compress bitmap to JPEG byte array
     */
    fun compress(bitmap: Bitmap, quality: Int = 50): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Compress and scale bitmap for optimal upload size
     */
    fun compressForUpload(
        bitmap: Bitmap,
        quality: Int = 50,
        maxWidth: Int = 1080,
        maxHeight: Int = 1920
    ): ByteArray {
        val scaledBitmap = scaleBitmap(bitmap, maxWidth, maxHeight)
        val compressed = compress(scaledBitmap, quality)

        // Recycle scaled bitmap if it's different from original
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return compressed
    }

    /**
     * Scale bitmap to fit within max dimensions while maintaining aspect ratio
     */
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
