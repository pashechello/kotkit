package com.autoposter.scheduler

/**
 * Configuration for Smart Scheduler.
 *
 * The scheduler works on a SCHEDULED basis with ADVANCE NOTIFICATIONS:
 * 1. User sets posting times (e.g., 14:00, 18:00, 21:00)
 * 2. Scheduler sends warning notifications before each posting
 * 3. At scheduled time, checks if phone is available
 * 4. If available - publishes, if not - waits or reschedules
 */
data class ScheduleConfig(
    /**
     * Number of videos to post per day
     */
    val videosPerDay: Int = 3,

    /**
     * Preferred hours for posting (24h format)
     * e.g., [9, 14, 19] = 9:00, 14:00, 19:00
     */
    val preferredHours: List<Int> = listOf(9, 14, 19),

    /**
     * Minimum interval between posts in minutes
     */
    val minIntervalMinutes: Int = 120,

    /**
     * Warning notification times before scheduled posting (in minutes)
     * e.g., [10, 1] = warn at 10 minutes and 1 minute before
     */
    val warningMinutesBefore: List<Int> = listOf(10, 1),

    /**
     * How long to wait (seconds) after final warning before starting
     * This gives user a chance to cancel
     */
    val finalCountdownSeconds: Int = 30,

    /**
     * If phone is busy at scheduled time, how long to wait (minutes)
     */
    val busyWaitMinutes: Int = 5,

    /**
     * Maximum retries if phone keeps being busy
     */
    val maxBusyRetries: Int = 3,

    /**
     * Conditions that must be met before publishing
     */
    val publishConditions: PublishConditions = PublishConditions()
)

/**
 * Conditions to check before starting the publish process
 */
data class PublishConditions(
    /**
     * Screen must be off to start publishing
     */
    val requireScreenOff: Boolean = true,

    /**
     * Phone must be charging (optional, false by default)
     */
    val requireCharging: Boolean = false,

    /**
     * Phone must be in pocket (proximity sensor near)
     */
    val requireInPocket: Boolean = false,

    /**
     * No user activity for N minutes (0 = disabled)
     */
    val idleTimeoutMinutes: Int = 0
)

/**
 * Current state of the device
 */
data class DeviceState(
    val isScreenOff: Boolean,
    val isCharging: Boolean,
    val isInPocket: Boolean,
    val idleMinutes: Int,
    val batteryLevel: Int,
    val isWifiConnected: Boolean
)

/**
 * Status of a scheduled posting
 */
enum class ScheduledPostStatus {
    /**
     * Post is waiting for its scheduled time
     */
    SCHEDULED,

    /**
     * Warning notification was sent (10 min before)
     */
    WARNING_SENT,

    /**
     * Final warning sent (1 min before)
     */
    FINAL_WARNING_SENT,

    /**
     * Countdown started (30 sec before)
     */
    COUNTDOWN_STARTED,

    /**
     * Checking if phone is available
     */
    CHECKING_AVAILABILITY,

    /**
     * Phone was busy, waiting to retry
     */
    WAITING_RETRY,

    /**
     * Publishing in progress
     */
    PUBLISHING,

    /**
     * Publishing completed
     */
    COMPLETED,

    /**
     * Failed after all retries
     */
    FAILED,

    /**
     * Cancelled by user
     */
    CANCELLED
}
