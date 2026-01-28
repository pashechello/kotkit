package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

/**
 * Network Mode API Models - Remote Configuration
 *
 * These models correspond to the backend API endpoints in:
 * tiktok-agent/api/routes/config.py
 */

// ============================================================================
// Selectors Config (TikTok UI automation)
// ============================================================================

data class SelectorsConfigResponse(
    val version: String,
    @SerializedName("updated_at") val updatedAt: Long,
    val selectors: Map<String, String>, // Selector ID -> Resource ID
    @SerializedName("content_descriptions") val contentDescriptions: Map<String, String>,
    val timeouts: TimeoutsConfig
)

data class TimeoutsConfig(
    @SerializedName("page_load_ms") val pageLoadMs: Int,
    @SerializedName("element_wait_ms") val elementWaitMs: Int,
    @SerializedName("upload_timeout_ms") val uploadTimeoutMs: Int,
    @SerializedName("post_confirm_ms") val postConfirmMs: Int
)

// ============================================================================
// App Config (general settings)
// ============================================================================

data class AppConfigResponse(
    val version: String,
    @SerializedName("min_supported_version") val minSupportedVersion: String,
    @SerializedName("force_update") val forceUpdate: Boolean,
    @SerializedName("update_url") val updateUrl: String,
    @SerializedName("api_base_url") val apiBaseUrl: String,
    @SerializedName("support_email") val supportEmail: String,
    @SerializedName("support_telegram") val supportTelegram: String,
    @SerializedName("terms_url") val termsUrl: String,
    @SerializedName("privacy_url") val privacyUrl: String,
    @SerializedName("max_video_size_mb") val maxVideoSizeMb: Int,
    @SerializedName("max_caption_length") val maxCaptionLength: Int,
    @SerializedName("task_timeout_minutes") val taskTimeoutMinutes: Int,
    @SerializedName("cooldown_minutes_min") val cooldownMinutesMin: Int,
    @SerializedName("cooldown_minutes_max") val cooldownMinutesMax: Int,
    @SerializedName("max_daily_posts") val maxDailyPosts: Int
)

// ============================================================================
// Feature Flags
// ============================================================================

data class FeatureFlagsResponse(
    val features: Map<String, Boolean>,
    val experiments: Map<String, Boolean>
)

// Feature flag keys (for type-safe access)
object FeatureFlag {
    const val NETWORK_MODE_ENABLED = "network_mode_enabled"
    const val CRYPTO_PAYOUTS_ENABLED = "crypto_payouts_enabled"
    const val CARD_PAYOUTS_ENABLED = "card_payouts_enabled"
    const val SBP_PAYOUTS_ENABLED = "sbp_payouts_enabled"
    const val PUSH_NOTIFICATIONS_ENABLED = "push_notifications_enabled"
    const val ERROR_REPORTING_ENABLED = "error_reporting_enabled"
    const val ANALYTICS_ENABLED = "analytics_enabled"
    const val DARK_MODE_ENABLED = "dark_mode_enabled"
    const val BETA_FEATURES_ENABLED = "beta_features_enabled"
}

object Experiment {
    const val NEW_TASK_UI = "new_task_ui"
    const val ANIMATED_EARNINGS = "animated_earnings"
    const val QUICK_CLAIM = "quick_claim"
}
