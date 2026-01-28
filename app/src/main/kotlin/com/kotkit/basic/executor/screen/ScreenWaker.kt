package com.kotkit.basic.executor.screen

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenWaker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ScreenWaker"
        private const val WAKE_TAG = "AutoPoster:WakeLock"
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Check if screen is currently on
     */
    fun isScreenOn(): Boolean {
        return powerManager.isInteractive
    }

    /**
     * Wake the screen
     */
    @Suppress("DEPRECATION")
    fun wake() {
        Log.w(TAG, "wake() called")
        releaseWakeLock()

        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            WAKE_TAG
        ).apply {
            acquire(WAKE_TIMEOUT_MS)
        }
        Log.w(TAG, "wake() WakeLock acquired")
    }

    /**
     * Keep screen on while posting
     */
    @Suppress("DEPRECATION")
    fun keepScreenOn() {
        if (wakeLock?.isHeld == true) return

        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            WAKE_TAG
        ).apply {
            acquire(WAKE_TIMEOUT_MS)
        }
    }

    /**
     * Release wake lock
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
