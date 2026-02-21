package com.kotkit.basic.warmup

/**
 * Configuration constants for account warmup sessions.
 *
 * Warmup simulates natural TikTok browsing (scroll feed, watch videos, like)
 * to prevent accounts from being flagged as automated.
 *
 * Trigger: worker mode ON + charging + screen off + frequency limits met.
 */
object WarmupConfig {

    // Session duration (randomized within range)
    const val SESSION_MIN_MINUTES = 10
    const val SESSION_MAX_MINUTES = 20

    // Frequency limits
    const val MAX_SESSIONS_PER_WEEK = 3
    const val MIN_COOLDOWN_BETWEEN_SESSIONS_MS = 48L * 60 * 60 * 1000  // 48 hours

    // Battery
    const val MIN_BATTERY_LEVEL = 20

    // Failure tracking
    const val MAX_CONSECUTIVE_FAILURES = 3

    // Behavioral parameters
    const val LIKE_PROBABILITY = 0.15f          // 15% chance to like a video
    const val WATCH_TIME_MIN_SEC = 3L
    const val WATCH_TIME_MAX_SEC = 30L
    const val PAUSE_PROBABILITY = 0.10f         // 10% chance of idle pause
    const val PAUSE_MIN_SEC = 5L
    const val PAUSE_MAX_SEC = 20L

    // Swipe (scroll to next video)
    const val SWIPE_DURATION_MIN_MS = 200L
    const val SWIPE_DURATION_MAX_MS = 450L
    const val POST_SWIPE_SETTLE_MS = 600L       // Wait for video transition

    // VLM monitoring (verify_feed + analyze recovery)
    const val VLM_CHECK_INTERVAL_VIDEOS = 3     // Check feed state via Qwen every N videos
    const val MAX_VLM_RECOVERY_ATTEMPTS = 3     // Max full VLM recovery attempts per session
    const val MAX_POPUP_DISMISSALS = 5          // Max popup dismissals before aborting
    const val FEED_RECOVERY_SETTLE_MS = 1500L   // Wait after VLM recovery action

    // Periodic device checks
    const val FEED_CHECK_INTERVAL = 5           // Check TikTok foreground + charging every N videos

    // TikTok launch
    const val TIKTOK_LAUNCH_WAIT_MS = 5000L

    // Double-tap like zone (percentage of screen, avoids right-side buttons & bottom nav)
    const val LIKE_ZONE_LEFT_PCT = 0.15f
    const val LIKE_ZONE_RIGHT_PCT = 0.65f
    const val LIKE_ZONE_TOP_PCT = 0.25f
    const val LIKE_ZONE_BOTTOM_PCT = 0.75f

    // Cancellation polling interval
    const val CANCEL_CHECK_INTERVAL_MS = 500L

    // SharedPreferences
    const val PREFS_NAME = "warmup_prefs"
    const val KEY_LAST_SESSION_TIMESTAMP = "last_session_timestamp"
    const val KEY_SESSIONS_THIS_WEEK = "sessions_this_week"
    const val KEY_WEEK_START_TIMESTAMP = "week_start_timestamp"
    const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
}
