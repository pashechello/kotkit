package com.kotkit.basic.network

import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.kotkit.basic.BuildConfig
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.CorrelationIdInterceptor
import com.kotkit.basic.data.remote.api.models.ErrorLogRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports errors to the backend for monitoring and debugging.
 *
 * Features:
 * - Automatic device info collection
 * - Optional screenshot capture
 * - Fire-and-forget async reporting
 * - Always enabled (no feature flag)
 */
@Singleton
class ErrorReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val screenshotManager: ScreenshotManager
) {
    companion object {
        private const val TAG = "ErrorReporter"
        private const val MAX_STACK_TRACE_LENGTH = 5000
        private const val MAX_MESSAGE_LENGTH = 1000
    }

    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Report an error to the backend.
     * This is fire-and-forget - failures are logged but don't propagate.
     */
    fun report(
        errorType: String,
        errorMessage: String,
        taskId: String? = null,
        throwable: Throwable? = null,
        context: Map<String, String>? = null,
        includeScreenshot: Boolean = false
    ) {
        reportScope.launch {
            try {
                reportInternal(
                    errorType = errorType,
                    errorMessage = errorMessage,
                    taskId = taskId,
                    throwable = throwable,
                    additionalContext = context,
                    includeScreenshot = includeScreenshot
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report error", e)
            }
        }
    }

    /**
     * Report an exception.
     */
    fun reportException(
        throwable: Throwable,
        taskId: String? = null,
        context: Map<String, String>? = null
    ) {
        val errorType = classifyException(throwable)
        report(
            errorType = errorType,
            errorMessage = throwable.message ?: "Unknown error",
            taskId = taskId,
            throwable = throwable,
            context = context,
            includeScreenshot = false
        )
    }

    /**
     * Report with screenshot capture.
     */
    suspend fun reportWithScreenshot(
        errorType: String,
        errorMessage: String,
        taskId: String? = null,
        throwable: Throwable? = null,
        context: Map<String, String>? = null
    ) {
        try {
            reportInternal(
                errorType = errorType,
                errorMessage = errorMessage,
                taskId = taskId,
                throwable = throwable,
                additionalContext = context,
                includeScreenshot = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report error with screenshot", e)
        }
    }

    private suspend fun reportInternal(
        errorType: String,
        errorMessage: String,
        taskId: String?,
        throwable: Throwable?,
        additionalContext: Map<String, String>?,
        includeScreenshot: Boolean
    ) {
        val deviceInfo = collectDeviceInfo()

        val screenshotB64 = if (includeScreenshot) {
            captureScreenshot()
        } else null

        val stackTrace = throwable?.stackTraceToString()?.take(MAX_STACK_TRACE_LENGTH)

        // Merge context with additional context and add correlation ID
        val fullContext = mutableMapOf<String, Any>()
        fullContext["correlation_id"] = CorrelationIdInterceptor.getSessionCorrelationId()
        additionalContext?.forEach { (k, v) -> fullContext[k] = v }

        val request = ErrorLogRequest(
            level = "error",
            message = errorMessage.take(MAX_MESSAGE_LENGTH),
            errorType = errorType,
            taskId = taskId,
            context = fullContext.ifEmpty { null },
            deviceInfo = deviceInfo,
            screenshotB64 = screenshotB64,
            stackTrace = stackTrace,
            timestamp = System.currentTimeMillis()
        )

        val response = apiService.reportError(request)
        Log.i(TAG, "Error reported: ${response.id} ($errorType)")
    }

    private fun collectDeviceInfo(): Map<String, Any> {
        val runtime = Runtime.getRuntime()
        val freeMemoryMb = (runtime.freeMemory() / (1024 * 1024)).toInt()

        val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging

        val tiktokVersion = getTikTokVersion()

        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android_version" to Build.VERSION.RELEASE,
            "app_version" to BuildConfig.VERSION_NAME,
            "tiktok_version" to (tiktokVersion ?: "unknown"),
            "free_memory_mb" to freeMemoryMb,
            "battery_percent" to batteryPercent,
            "is_charging" to isCharging
        )
    }

    private fun getTikTokVersion(): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo("com.zhiliaoapp.musically", 0)
            packageInfo.versionName
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun captureScreenshot(): String? {
        return try {
            val path = screenshotManager.captureProofScreenshot("error_report")
            if (path != null) {
                encodeScreenshotFile(path)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screenshot", e)
            null
        }
    }

    private fun encodeScreenshotFile(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists()) return null

            val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream) // Lower quality for errors
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode screenshot", e)
            null
        }
    }

    private fun classifyException(throwable: Throwable): String {
        return when (throwable) {
            is java.net.SocketTimeoutException,
            is java.net.UnknownHostException,
            is java.net.ConnectException -> "network_timeout"

            is java.io.IOException -> "io_error"

            is SecurityException -> "security_error"

            is IllegalStateException,
            is IllegalArgumentException -> "illegal_state"

            is NullPointerException -> "null_pointer"

            is OutOfMemoryError -> "out_of_memory"

            else -> "unknown_error"
        }
    }
}
