package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Network Mode API Models - Worker Management
 *
 * These models correspond to the backend API endpoints in:
 * tiktok-agent/api/routes/workers.py
 * tiktok-agent/api/schemas/worker.py
 */

// ============================================================================
// Worker Registration & Profile
// ============================================================================

data class WorkerRegisterRequest(
    @SerializedName("tiktok_username") val tiktokUsername: String?,
    @SerializedName("category_ids") val categoryIds: List<String>?,
    @SerializedName("country_code") val countryCode: String?,
    val timezone: String?
)

data class WorkerUpdateRequest(
    @SerializedName("tiktok_username") val tiktokUsername: String?,
    @SerializedName("category_ids") val categoryIds: List<String>?,
    @SerializedName("max_daily_tasks") val maxDailyTasks: Int?,
    @SerializedName("min_price_per_post") val minPricePerPost: Float?,
    @SerializedName("is_active") val isActive: Boolean?,
    @SerializedName("country_code") val countryCode: String?,
    val timezone: String?
)

data class WorkerResponse(
    val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("tiktok_username") val tiktokUsername: String?,
    @SerializedName("followers_count") val followersCount: Int,
    @SerializedName("is_verified") val isVerified: Boolean,
    @SerializedName("is_active") val isActive: Boolean,
    val rating: Float,
    @SerializedName("total_tasks") val totalTasks: Int,
    @SerializedName("completed_tasks") val completedTasks: Int,
    @SerializedName("failed_tasks") val failedTasks: Int,
    @SerializedName("success_rate") val successRate: Float,
    @SerializedName("total_earned") val totalEarned: Float,
    @SerializedName("pending_balance") val pendingBalance: Float,
    @SerializedName("available_balance") val availableBalance: Float,
    @SerializedName("max_daily_tasks") val maxDailyTasks: Int,
    @SerializedName("min_price_per_post") val minPricePerPost: Float,
    @SerializedName("country_code") val countryCode: String?,
    val timezone: String?,
    @SerializedName("category_ids") val categoryIds: List<String>,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("updated_at") val updatedAt: Long
)

data class WorkerToggleRequest(
    @SerializedName("is_active") val isActive: Boolean? = null
)

// ============================================================================
// Worker Stats
// ============================================================================

data class WorkerStatsResponse(
    @SerializedName("worker_id") val workerId: String,
    // Today
    @SerializedName("today_tasks") val todayTasks: Int,
    @SerializedName("today_completed") val todayCompleted: Int,
    @SerializedName("today_earned") val todayEarned: Float,
    // Week
    @SerializedName("week_tasks") val weekTasks: Int,
    @SerializedName("week_completed") val weekCompleted: Int,
    @SerializedName("week_earned") val weekEarned: Float,
    // Month
    @SerializedName("month_tasks") val monthTasks: Int,
    @SerializedName("month_completed") val monthCompleted: Int,
    @SerializedName("month_earned") val monthEarned: Float,
    // Other
    @SerializedName("avg_completion_time_sec") val avgCompletionTimeSec: Float?,
    @SerializedName("streak_days") val streakDays: Int
)

// ============================================================================
// Worker Device Management
// ============================================================================

data class RegisterDeviceRequest(
    @SerializedName("device_fingerprint") val deviceFingerprint: String,
    @SerializedName("device_model") val deviceModel: String?,
    @SerializedName("android_version") val androidVersion: String?,
    @SerializedName("app_version") val appVersion: String?,
    @SerializedName("fcm_token") val fcmToken: String?
)

data class WorkerDeviceResponse(
    val id: String,
    @SerializedName("device_fingerprint") val deviceFingerprint: String,
    @SerializedName("device_model") val deviceModel: String?,
    @SerializedName("android_version") val androidVersion: String?,
    @SerializedName("app_version") val appVersion: String?,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("last_seen_at") val lastSeenAt: Long?,
    @SerializedName("created_at") val createdAt: Long
)

data class WorkerDeviceListResponse(
    val devices: List<WorkerDeviceResponse>
)

// ============================================================================
// Content Category (for worker specialization)
// ============================================================================

data class ContentCategoryResponse(
    val id: String,
    val name: String,
    @SerializedName("name_ru") val nameRu: String?,
    val description: String?,
    @SerializedName("is_allowed") val isAllowed: Boolean,
    @SerializedName("requires_verification") val requiresVerification: Boolean
)

// ============================================================================
// Worker Heartbeat (Worker-level, not Task-level)
// ============================================================================

data class WorkerHeartbeatResponse(
    val ok: Boolean,
    @SerializedName("last_active_at") val lastActiveAt: Long
)

data class WorkerOfflineResponse(
    val ok: Boolean
)
