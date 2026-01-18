package com.autoposter.data.remote.api.models

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val email: String,
    val password: String,
    @SerializedName("device_id") val deviceId: String
)

data class RegisterRequest(
    val email: String,
    val password: String,
    val username: String,
    @SerializedName("device_id") val deviceId: String
)

data class AuthResponse(
    val success: Boolean,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("user_id") val userId: String?,
    val message: String?,
    val error: String?
)

data class RefreshTokenRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

data class LicenseStatus(
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("plan_type") val planType: String?,
    @SerializedName("expires_at") val expiresAt: Long?,
    @SerializedName("posts_remaining") val postsRemaining: Int?,
    @SerializedName("posts_total") val postsTotal: Int?
)

data class AnalyticsEvent(
    @SerializedName("event_type") val eventType: String,
    val data: Map<String, Any>? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// Event types for analytics
object EventType {
    const val POST_STARTED = "post_started"
    const val POST_COMPLETED = "post_completed"
    const val POST_FAILED = "post_failed"
    const val APP_OPENED = "app_opened"
    const val SCREEN_UNLOCKED = "screen_unlocked"
    const val TIKTOK_OPENED = "tiktok_opened"
}
