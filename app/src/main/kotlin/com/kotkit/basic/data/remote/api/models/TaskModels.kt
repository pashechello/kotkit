package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Network Mode API Models - Task Management
 *
 * These models correspond to the backend API endpoints in:
 * tiktok-agent/api/routes/tasks.py
 */

// ============================================================================
// Task Response Models
// ============================================================================

data class TaskResponse(
    val id: String,
    @SerializedName("campaign_id") val campaignId: String,
    @SerializedName("video_s3_key") val videoS3Key: String,
    @SerializedName("video_hash") val videoHash: String?,
    @SerializedName("video_size_bytes") val videoSizeBytes: Long?,
    val caption: String?,
    val status: String, // pending, assigned, downloading, posting, verifying, completed, failed, expired
    @SerializedName("price_rub") val priceRub: Float,
    @SerializedName("assigned_worker_id") val assignedWorkerId: String?,
    @SerializedName("assigned_at") val assignedAt: Long?,
    @SerializedName("scheduled_for") val scheduledFor: Long?,
    @SerializedName("last_heartbeat") val lastHeartbeat: Long?,
    @SerializedName("started_at") val startedAt: Long?,
    @SerializedName("completed_at") val completedAt: Long?,
    @SerializedName("tiktok_video_id") val tiktokVideoId: String?,
    @SerializedName("tiktok_post_url") val tiktokPostUrl: String?,
    @SerializedName("proof_screenshot_s3_key") val proofScreenshotS3Key: String?,
    @SerializedName("error_message") val errorMessage: String?,
    @SerializedName("error_type") val errorType: String?,
    @SerializedName("retry_count") val retryCount: Int,
    @SerializedName("video_thumbnail_url") val videoThumbnailUrl: String? = null,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("updated_at") val updatedAt: Long?
)

data class TaskListResponse(
    val tasks: List<TaskResponse>,
    val total: Int
)

// ============================================================================
// Task Video URL Response (P1 Fix: with resume support)
// ============================================================================

data class TaskVideoUrlResponse(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("video_url") val videoUrl: String,
    @SerializedName("expires_at") val expiresAt: Long,
    @SerializedName("video_hash") val videoHash: String,
    @SerializedName("video_size_bytes") val videoSizeBytes: Long,
    @SerializedName("support_resume") val supportResume: Boolean = true // S3 supports Range headers
)

// ============================================================================
// Task Request Models
// ============================================================================

data class ClaimTaskRequest(
    @SerializedName("task_id") val taskId: String
)

data class HeartbeatRequest(
    @SerializedName("task_id") val taskId: String
)

data class HeartbeatResponse(
    val status: String,
    @SerializedName("last_heartbeat") val lastHeartbeat: Long
)

data class TaskProgressRequest(
    val status: String, // downloading, posting, verifying
    @SerializedName("progress_percent") val progressPercent: Int?,
    val message: String?
)

data class CompleteTaskRequest(
    @SerializedName("tiktok_video_id") val tiktokVideoId: String,
    @SerializedName("tiktok_post_url") val tiktokPostUrl: String?,
    @SerializedName("proof_screenshot_b64") val proofScreenshotB64: String? // Base64 encoded
)

data class FailTaskRequest(
    @SerializedName("error_message") val errorMessage: String,
    @SerializedName("error_type") val errorType: String?,
    @SerializedName("screenshot_b64") val screenshotB64: String? // Base64 encoded
)

// ============================================================================
// Task Status Enum (matches backend)
// ============================================================================

object TaskStatus {
    const val PENDING = "pending"
    const val ASSIGNED = "assigned"
    const val DOWNLOADING = "downloading"
    const val POSTING = "posting"
    const val VERIFYING = "verifying"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val EXPIRED = "expired"
}

// ============================================================================
// Error Types (matches backend ErrorType enum)
// ============================================================================

object ErrorType {
    // Transient errors (retry immediately)
    const val NETWORK_TIMEOUT = "network_timeout"
    const val APP_CRASH = "app_crash"
    const val DEVICE_REBOOT = "device_reboot"

    // Recoverable errors (retry with delay)
    const val BUTTON_NOT_FOUND = "button_not_found"
    const val VIDEO_DOWNLOAD_FAILED = "video_download_failed"
    const val TIKTOK_APP_NOT_INSTALLED = "tiktok_app_not_installed"
    const val TIKTOK_NOT_LOGGED_IN = "tiktok_not_logged_in"
    const val UPLOAD_TIMEOUT = "upload_timeout"

    // Permanent errors (don't retry)
    const val ACCOUNT_BANNED = "account_banned"
    const val ACCESSIBILITY_DISABLED = "accessibility_disabled"
    const val VIDEO_TOO_LARGE = "video_too_large"

    // Manual intervention required
    const val TIKTOK_CAPTCHA = "captcha"
    const val UNKNOWN_ERROR = "unknown_error"

    // Duplicate protection
    const val DUPLICATE_VIDEO = "duplicate_video"
}
