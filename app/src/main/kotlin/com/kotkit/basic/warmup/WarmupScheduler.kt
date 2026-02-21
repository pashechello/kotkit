package com.kotkit.basic.warmup

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a warmup session should start based on device state and frequency limits.
 *
 * Conditions:
 * - Worker mode active
 * - Phone charging + battery >= 20%
 * - Screen off (user not using phone)
 * - No active posting task
 * - Warmup not already running
 * - Cooldown elapsed (48h between sessions)
 * - Sessions this week < 3
 * - Consecutive failures < 3
 */
@Singleton
class WarmupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStateChecker: com.kotkit.basic.scheduler.DeviceStateChecker
) {
    companion object {
        private const val TAG = "WarmupScheduler"
    }

    private val prefs by lazy {
        context.getSharedPreferences(WarmupConfig.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    @Volatile
    var isWarmupRunning = false

    fun shouldStartWarmup(): Boolean {
        // Screen must be off (user not interacting)
        val screenOn = powerManager?.isInteractive ?: true
        if (screenOn) {
            Timber.tag(TAG).d("Skip warmup: screen is on")
            return false
        }

        // Must be charging with sufficient battery
        val state = deviceStateChecker.getCurrentStateSnapshot()
        if (!state.isCharging) {
            Timber.tag(TAG).d("Skip warmup: not charging")
            return false
        }
        if (state.batteryLevel < WarmupConfig.MIN_BATTERY_LEVEL) {
            Timber.tag(TAG).d("Skip warmup: battery ${state.batteryLevel}% < ${WarmupConfig.MIN_BATTERY_LEVEL}%")
            return false
        }

        // Not already running
        if (isWarmupRunning) {
            Timber.tag(TAG).d("Skip warmup: already running")
            return false
        }

        // Consecutive failures check
        val failures = prefs.getInt(WarmupConfig.KEY_CONSECUTIVE_FAILURES, 0)
        if (failures >= WarmupConfig.MAX_CONSECUTIVE_FAILURES) {
            Timber.tag(TAG).d("Skip warmup: $failures consecutive failures")
            return false
        }

        // Cooldown check (48h between sessions)
        val lastSession = prefs.getLong(WarmupConfig.KEY_LAST_SESSION_TIMESTAMP, 0L)
        val elapsed = System.currentTimeMillis() - lastSession
        if (elapsed < WarmupConfig.MIN_COOLDOWN_BETWEEN_SESSIONS_MS) {
            val hoursLeft = (WarmupConfig.MIN_COOLDOWN_BETWEEN_SESSIONS_MS - elapsed) / (60 * 60 * 1000)
            Timber.tag(TAG).d("Skip warmup: cooldown, ${hoursLeft}h remaining")
            return false
        }

        // Weekly limit check
        resetWeeklyCounterIfNeeded()
        val sessionsThisWeek = prefs.getInt(WarmupConfig.KEY_SESSIONS_THIS_WEEK, 0)
        if (sessionsThisWeek >= WarmupConfig.MAX_SESSIONS_PER_WEEK) {
            Timber.tag(TAG).d("Skip warmup: weekly limit $sessionsThisWeek/${WarmupConfig.MAX_SESSIONS_PER_WEEK}")
            return false
        }

        Timber.tag(TAG).i("Warmup conditions met: charging=${state.isCharging}, battery=${state.batteryLevel}%, " +
            "sessionsThisWeek=$sessionsThisWeek, failures=$failures")
        return true
    }

    fun recordSessionComplete() {
        prefs.edit()
            .putLong(WarmupConfig.KEY_LAST_SESSION_TIMESTAMP, System.currentTimeMillis())
            .putInt(WarmupConfig.KEY_SESSIONS_THIS_WEEK, prefs.getInt(WarmupConfig.KEY_SESSIONS_THIS_WEEK, 0) + 1)
            .putInt(WarmupConfig.KEY_CONSECUTIVE_FAILURES, 0)
            .apply()
        Timber.tag(TAG).i("Session recorded. Total this week: ${prefs.getInt(WarmupConfig.KEY_SESSIONS_THIS_WEEK, 0)}")
    }

    fun recordSessionFailed(reason: String) {
        val failures = prefs.getInt(WarmupConfig.KEY_CONSECUTIVE_FAILURES, 0) + 1
        prefs.edit()
            .putInt(WarmupConfig.KEY_CONSECUTIVE_FAILURES, failures)
            .apply()
        Timber.tag(TAG).w("Session failed ($reason). Consecutive failures: $failures")
    }

    private fun resetWeeklyCounterIfNeeded() {
        val weekStart = prefs.getLong(WarmupConfig.KEY_WEEK_START_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()

        // Reset if we're in a new week (7 days since last reset)
        if (now - weekStart > 7L * 24 * 60 * 60 * 1000) {
            prefs.edit()
                .putInt(WarmupConfig.KEY_SESSIONS_THIS_WEEK, 0)
                .putLong(WarmupConfig.KEY_WEEK_START_TIMESTAMP, now)
                .apply()
            Timber.tag(TAG).d("Weekly counter reset")
        }
    }
}
