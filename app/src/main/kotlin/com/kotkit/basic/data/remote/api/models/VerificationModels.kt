package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Models for Post & Check video verification system.
 */

// ========================================================================
// Requests
// ========================================================================

data class VerificationCompleteRequest(
    @SerializedName("result") val result: String,  // video_exists, video_deleted, shadowban, error
    @SerializedName("screenshot_b64") val screenshotB64: String? = null,
    @SerializedName("view_count") val viewCount: Int? = null,
    @SerializedName("like_count") val likeCount: Int? = null,
    @SerializedName("error_message") val errorMessage: String? = null
)

// ========================================================================
// Responses
// ========================================================================

data class VerificationResponse(
    @SerializedName("id") val id: String,
    @SerializedName("task_id") val taskId: String,
    @SerializedName("tiktok_video_url") val tiktokVideoUrl: String,
    @SerializedName("tiktok_video_id") val tiktokVideoId: String? = null,
    @SerializedName("status") val status: String,
    @SerializedName("scheduled_for") val scheduledFor: Long,
    @SerializedName("attempts") val attempts: Int,
    @SerializedName("max_attempts") val maxAttempts: Int,
    @SerializedName("created_at") val createdAt: Long
)

data class VerificationListResponse(
    @SerializedName("verifications") val verifications: List<VerificationResponse>,
    @SerializedName("total") val total: Int
)

data class VerificationClaimResponse(
    @SerializedName("id") val id: String,
    @SerializedName("tiktok_video_url") val tiktokVideoUrl: String,
    @SerializedName("status") val status: String,
    @SerializedName("assigned_at") val assignedAt: Long
)

data class VerificationCompleteResponse(
    @SerializedName("id") val id: String,
    @SerializedName("result") val result: String,
    @SerializedName("status") val status: String
)

data class VerificationStatsResponse(
    @SerializedName("total_verifications") val totalVerifications: Int,
    @SerializedName("passed_verifications") val passedVerifications: Int,
    @SerializedName("failed_verifications") val failedVerifications: Int,
    @SerializedName("verification_success_rate") val verificationSuccessRate: Float,
    @SerializedName("active_strikes") val activeStrikes: Int,
    @SerializedName("total_strikes") val totalStrikes: Int,
    @SerializedName("is_banned") val isBanned: Boolean
)
