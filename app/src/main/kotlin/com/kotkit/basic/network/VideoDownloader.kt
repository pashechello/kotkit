package com.kotkit.basic.network

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Video downloader with resume support for network tasks.
 *
 * Features:
 * - Resume interrupted downloads using HTTP Range headers
 * - SHA256 checksum verification
 * - Progress callback
 * - Automatic cleanup on failure
 */
@Singleton
class VideoDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "VideoDownloader"
        private const val BUFFER_SIZE = 8192
        private const val VIDEOS_DIR = "network_videos"
    }

    /**
     * Download video with resume support.
     *
     * @param url Presigned S3 URL
     * @param taskId Task ID for filename
     * @param expectedHash SHA256 hash for verification
     * @param expectedSize Expected file size in bytes
     * @param supportsResume Whether server supports Range headers
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Result with downloaded file path
     */
    suspend fun downloadVideo(
        url: String,
        taskId: String,
        expectedHash: String,
        expectedSize: Long,
        supportsResume: Boolean = true,
        onProgress: (Float) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val destFile = getVideoFile(taskId)

        try {
            // Check existing file for resume
            val existingSize = if (destFile.exists()) destFile.length() else 0L
            Log.i(TAG, "Downloading video for task $taskId: existingSize=$existingSize, expectedSize=$expectedSize")

            // Skip download if already complete
            if (existingSize == expectedSize && verifyChecksum(destFile, expectedHash)) {
                Log.i(TAG, "Video already downloaded and verified: $taskId")
                onProgress(1f)
                return@withContext Result.success(destFile.absolutePath)
            }

            // Build request with Range header for resume
            val requestBuilder = Request.Builder().url(url)
            if (supportsResume && existingSize > 0 && existingSize < expectedSize) {
                requestBuilder.header("Range", "bytes=$existingSize-")
                Log.i(TAG, "Resuming download from byte $existingSize")
            }

            val request = requestBuilder.build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Download failed: HTTP ${response.code}")
            }

            val isPartial = response.code == 206
            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()

            // Calculate total size
            val totalSize = if (isPartial) {
                existingSize + contentLength
            } else {
                contentLength
            }

            // Open file for append (resume) or write (fresh start)
            val fos = if (isPartial && existingSize > 0) {
                FileOutputStream(destFile, true) // Append
            } else {
                FileOutputStream(destFile) // Overwrite
            }

            fos.use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var downloadedBytes = if (isPartial) existingSize else 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // Report progress
                        if (totalSize > 0) {
                            onProgress(downloadedBytes.toFloat() / totalSize)
                        }
                    }
                }
            }

            // Verify checksum
            if (!verifyChecksum(destFile, expectedHash)) {
                destFile.delete()
                throw IOException("Checksum verification failed")
            }

            Log.i(TAG, "Video downloaded and verified: $taskId (${destFile.length()} bytes)")
            onProgress(1f)
            Result.success(destFile.absolutePath)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for task $taskId", e)
            // Don't delete partial file - can resume later
            Result.failure(e)
        }
    }

    /**
     * Get video file path for a task.
     */
    fun getVideoFile(taskId: String): File {
        val videosDir = File(context.filesDir, VIDEOS_DIR)
        if (!videosDir.exists()) {
            videosDir.mkdirs()
        }
        return File(videosDir, "$taskId.mp4")
    }

    /**
     * Delete downloaded video for a task.
     */
    fun deleteVideo(taskId: String): Boolean {
        val file = getVideoFile(taskId)
        return if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                Log.i(TAG, "Deleted video for task $taskId")
            }
            deleted
        } else {
            true
        }
    }

    /**
     * Get download progress for a task (0.0 to 1.0).
     */
    fun getDownloadProgress(taskId: String, expectedSize: Long): Float {
        val file = getVideoFile(taskId)
        return if (file.exists() && expectedSize > 0) {
            (file.length().toFloat() / expectedSize).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    /**
     * Verify file checksum.
     */
    private fun verifyChecksum(file: File, expectedHash: String): Boolean {
        if (expectedHash.isEmpty()) {
            Log.w(TAG, "No hash provided, skipping verification")
            return true
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = actualHash.equals(expectedHash, ignoreCase = true)
            if (!matches) {
                Log.w(TAG, "Checksum mismatch: expected=$expectedHash, actual=$actualHash")
            }
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Checksum verification error", e)
            false
        }
    }

    /**
     * Cleanup old video files.
     */
    suspend fun cleanupOldVideos(maxAgeMs: Long = 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        val videosDir = File(context.filesDir, VIDEOS_DIR)
        if (!videosDir.exists()) return@withContext

        val cutoff = System.currentTimeMillis() - maxAgeMs
        videosDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                if (file.delete()) {
                    Log.i(TAG, "Cleaned up old video: ${file.name}")
                }
            }
        }
    }
}
