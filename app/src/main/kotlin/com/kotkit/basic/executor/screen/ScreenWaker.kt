package com.kotkit.basic.executor.screen

import android.content.Context
import android.os.PowerManager
import timber.log.Timber
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
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK deprecated at API 30 but still functional
    fun wake() {
        Timber.tag(TAG).w("wake() called")
        releaseWakeLock()

        try {
            // FULL_WAKE_LOCK is silently ignored on API 26+ — use SCREEN_BRIGHT_WAKE_LOCK instead.
            // SCREEN_BRIGHT_WAKE_LOCK keeps the screen on at full brightness, which is the correct
            // behaviour for automation. Both FULL and SCREEN_BRIGHT are deprecated at API 30 but
            // still functional; the proper long-term replacement is WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            // which requires an Activity — not applicable here.
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                WAKE_TAG
            ).apply {
                acquire(WAKE_TIMEOUT_MS)
            }
            Timber.tag(TAG).w("wake() WakeLock acquired")
        } catch (e: SecurityException) {
            // EMUI Power Genie may block ACQUIRE_CAUSES_WAKEUP if battery restrictions are active
            Timber.tag(TAG).e(e, "WakeLock acquisition blocked by system — screen may not wake")
        }
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
