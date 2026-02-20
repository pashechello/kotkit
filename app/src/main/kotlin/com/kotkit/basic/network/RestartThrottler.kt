package com.kotkit.basic.network

import android.content.Context
import timber.log.Timber

/**
 * Prevents rapid restart cycles that cause Android to mark
 * the accessibility service as "crashed".
 *
 * Android tracks rapid process deaths internally. After ~3-5 deaths within
 * a short window, it adds the accessibility service to "Crashed services"
 * and stops rebinding it. The only fix is a manual toggle in Settings.
 *
 * This throttler tracks service start times and enforces exponential backoff
 * when restarts happen too frequently, breaking the crash-restart loop.
 */
object RestartThrottler {
    private const val TAG = "RestartThrottler"
    private const val PREFS_NAME = "restart_throttler"
    private const val KEY_RESTART_TIMES = "restart_times"
    private const val KEY_BACKOFF_LEVEL = "backoff_level"

    /** Max number of recent restarts to track */
    private const val WINDOW_SIZE = 5

    /** If WINDOW_SIZE restarts happen within this window, throttle */
    private const val RAPID_RESTART_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

    /** Base backoff delay */
    private const val BASE_BACKOFF_MS = 30_000L // 30 seconds

    /** Max backoff delay */
    private const val MAX_BACKOFF_MS = 5 * 60 * 1000L // 5 minutes

    /** Max backoff level (2^5 * 30s = 960s, capped to 300s) */
    private const val MAX_BACKOFF_LEVEL = 5

    /**
     * Record a service start event and check if we should proceed.
     *
     * @return delay in ms to wait before actually starting (0 = proceed immediately)
     */
    fun recordStartAndGetDelay(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Load recent restart times
        val timesStr = prefs.getString(KEY_RESTART_TIMES, "") ?: ""
        val times = if (timesStr.isBlank()) mutableListOf()
        else timesStr.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()

        // Add current time
        times.add(now)

        // Keep only recent entries
        val cutoff = now - RAPID_RESTART_WINDOW_MS
        val recentTimes = times.filter { it > cutoff }.toMutableList()

        // Persist
        prefs.edit()
            .putString(KEY_RESTART_TIMES, recentTimes.joinToString(","))
            .apply()

        if (recentTimes.size >= WINDOW_SIZE) {
            // Too many restarts in the window — apply backoff
            val backoffLevel = prefs.getInt(KEY_BACKOFF_LEVEL, 0)
            val newLevel = (backoffLevel + 1).coerceAtMost(MAX_BACKOFF_LEVEL)
            val delay = (BASE_BACKOFF_MS * (1L shl backoffLevel)).coerceAtMost(MAX_BACKOFF_MS)

            prefs.edit().putInt(KEY_BACKOFF_LEVEL, newLevel).apply()

            Timber.tag(TAG).w(
                "THROTTLED: ${recentTimes.size} restarts in ${RAPID_RESTART_WINDOW_MS / 1000}s window. " +
                    "Backoff level=$newLevel, delay=${delay / 1000}s"
            )
            return delay
        }

        // Not throttled — reset backoff if we're below threshold
        if (prefs.getInt(KEY_BACKOFF_LEVEL, 0) > 0) {
            prefs.edit().putInt(KEY_BACKOFF_LEVEL, 0).apply()
            Timber.tag(TAG).d("Backoff reset: only ${recentTimes.size} restarts in window")
        }

        Timber.tag(TAG).d("Restart recorded: ${recentTimes.size} in window, proceeding immediately")
        return 0
    }

    /**
     * Reset all throttling state (call when user explicitly starts worker mode).
     */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        Timber.tag(TAG).d("Throttler state reset")
    }
}
