package com.kotkit.basic.data.local.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache for network tasks.
 *
 * Stores tasks assigned from backend for offline support and sync tracking.
 */
@Entity(
    tableName = "network_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["syncStatus"]),
        Index(value = ["scheduledFor"])
    ]
)
data class NetworkTaskEntity(
    @PrimaryKey
    val id: String, // UUID from backend

    // Task details
    val campaignId: String,
    val videoS3Key: String,
    val videoHash: String?,
    val videoSizeBytes: Long?,
    val caption: String?,

    // Status
    val status: String, // pending, assigned, downloading, posting, verifying, completed, failed, expired
    val priceRub: Float,

    // Timing
    val assignedAt: Long?,
    val scheduledFor: Long?, // When to post (includes cooldown)
    val lastHeartbeat: Long?,
    val startedAt: Long?,
    val completedAt: Long?,

    // Result
    val tiktokVideoId: String? = null,
    val tiktokPostUrl: String? = null,
    val proofScreenshotPath: String? = null,

    // Error info
    val errorMessage: String? = null,
    val errorType: String? = null,
    val retryCount: Int = 0,

    // Thumbnail
    val videoThumbnailUrl: String? = null, // Presigned URL from backend

    // Local state
    val videoLocalPath: String? = null, // Downloaded video path
    val downloadProgress: Int = 0, // 0-100%

    // Sync tracking
    val syncStatus: String = SyncStatus.SYNCED, // synced, pending_completion, pending_failure
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Sync status for offline support.
 */
object SyncStatus {
    const val SYNCED = "synced"
    const val PENDING_COMPLETION = "pending_completion"
    const val PENDING_FAILURE = "pending_failure"
    const val PENDING_HEARTBEAT = "pending_heartbeat"
}

/**
 * Task status (mirrors backend).
 */
object NetworkTaskStatus {
    const val PENDING = "pending"
    const val ASSIGNED = "assigned"
    const val DOWNLOADING = "downloading"
    const val POSTING = "posting"
    const val VERIFYING = "verifying"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val EXPIRED = "expired"

    fun isActive(status: String): Boolean {
        return status in listOf(ASSIGNED, DOWNLOADING, POSTING, VERIFYING)
    }

    fun isFinal(status: String): Boolean {
        return status in listOf(COMPLETED, FAILED, EXPIRED)
    }
}
