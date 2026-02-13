package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Network Mode API Models - Error Logging
 *
 * These models correspond to the backend API endpoints in:
 * tiktok-agent/api/routes/logs.py
 * tiktok-agent/api/schemas/logs.py
 */

data class ErrorLogRequest(
    val level: String, // error, warning, info
    val message: String,
    @SerializedName("error_type") val errorType: String?,
    @SerializedName("task_id") val taskId: String?,
    val context: Map<String, Any>?,
    @SerializedName("device_info") val deviceInfo: Map<String, Any>?,
    @SerializedName("screenshot_b64") val screenshotB64: String?,
    @SerializedName("stack_trace") val stackTrace: String?,
    val timestamp: Long
)

data class ErrorLogResponse(
    val id: String,
    val status: String // "logged"
)

// Batch logging
data class BatchLogRequest(
    val logs: List<ErrorLogRequest>
)

data class BatchLogResponse(
    @SerializedName("logged_count") val loggedCount: Int,
    @SerializedName("failed_count") val failedCount: Int
)

// Helper for DeviceInfo
object DeviceInfoBuilder {
    fun build(
        model: String,
        manufacturer: String,
        androidVersion: String,
        appVersion: String,
        tiktokVersion: String? = null,
        freeMemoryMb: Int? = null,
        batteryPercent: Int? = null,
        isCharging: Boolean? = null
    ): Map<String, Any> {
        return buildMap {
            put("model", model)
            put("manufacturer", manufacturer)
            put("android_version", androidVersion)
            put("app_version", appVersion)
            tiktokVersion?.let { put("tiktok_version", it) }
            freeMemoryMb?.let { put("free_memory_mb", it) }
            batteryPercent?.let { put("battery_percent", it) }
            isCharging?.let { put("is_charging", it) }
        }
    }
}

object LogLevel {
    const val ERROR = "error"
    const val WARNING = "warning"
    const val INFO = "info"
}

// Device log upload
data class LogUploadResponse(
    val status: String,
    @SerializedName("s3_key") val s3Key: String,
    @SerializedName("size_bytes") val sizeBytes: Long
)
