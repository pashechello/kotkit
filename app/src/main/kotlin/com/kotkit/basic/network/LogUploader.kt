package com.kotkit.basic.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kotkit.basic.data.remote.api.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads device log files to the backend for remote debugging.
 *
 * - Uploads today's log after every task complete/fail
 * - Uploads yesterday's log on app start (if not uploaded)
 * - Tracks uploaded dates in SharedPreferences
 * - Max file size: 5MB
 */
@Singleton
class LogUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "LogUploader"
        private const val PREFS_NAME = "log_uploader"
        private const val KEY_UPLOADED_DATES = "uploaded_dates"
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5MB
        private const val MAX_TRACKED_DATES = 14
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val logDir: File
        get() = File(context.getExternalFilesDir(null), "logs")

    private val uploadMutex = Mutex()

    /**
     * Upload log for a specific date.
     * Today's log is always re-uploaded (it grows during the day).
     * Past dates are skipped if already uploaded.
     */
    suspend fun uploadLog(date: String): Boolean = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            try {
                val isToday = date == fileDateFormat.format(Date())

                // Skip past dates that were already uploaded
                if (!isToday && isDateUploaded(date)) {
                    Log.d(TAG, "Log for $date already uploaded, skipping")
                    return@withContext true
                }

                val logFile = File(logDir, "kotkit_$date.log")
                if (!logFile.exists()) {
                    Log.d(TAG, "No log file for $date")
                    return@withContext false
                }

                if (logFile.length() > MAX_FILE_SIZE) {
                    Log.w(TAG, "Log file too large: ${logFile.length()} bytes, skipping")
                    return@withContext false
                }

                if (logFile.length() == 0L) {
                    Log.d(TAG, "Log file empty for $date, skipping")
                    return@withContext false
                }

                // Build multipart request
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    logFile.name,
                    logFile.asRequestBody("text/plain".toMediaType())
                )
                val datePart = date.toRequestBody("text/plain".toMediaType())

                val response = apiService.uploadLogFile(filePart, datePart)
                Log.i(TAG, "Uploaded log for $date: ${response.sizeBytes} bytes -> ${response.s3Key}")

                // Mark past dates as uploaded (today always re-uploads)
                if (!isToday) {
                    markDateUploaded(date)
                }

                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload log for $date", e)
                return@withContext false
            }
        }
    }

    /**
     * Upload all pending logs: yesterday (if not uploaded) + today.
     * Called on app start and periodically.
     */
    suspend fun uploadPendingLogs() {
        try {
            val today = fileDateFormat.format(Date())
            val yesterday = fileDateFormat.format(
                Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
            )

            uploadLog(yesterday)
            uploadLog(today)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload pending logs", e)
        }
    }

    private fun isDateUploaded(date: String): Boolean {
        return date in getUploadedDates()
    }

    private fun markDateUploaded(date: String) {
        val dates = getUploadedDates().toMutableSet()
        dates.add(date)

        // Prune old dates to prevent unbounded growth
        val prunedDates = if (dates.size > MAX_TRACKED_DATES) {
            dates.sorted().takeLast(MAX_TRACKED_DATES).toSet()
        } else {
            dates
        }

        prefs.edit()
            .putStringSet(KEY_UPLOADED_DATES, prunedDates)
            .apply()
    }

    private fun getUploadedDates(): Set<String> {
        return prefs.getStringSet(KEY_UPLOADED_DATES, emptySet()) ?: emptySet()
    }
}
