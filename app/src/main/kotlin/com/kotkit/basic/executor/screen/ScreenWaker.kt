package com.kotkit.basic.executor.screen

import android.content.Context
import android.content.Intent
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
     * Wake the screen using WakeLock (standard approach).
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK deprecated at API 30 but still functional
    fun wake() {
        Timber.tag(TAG).w("wake() called")
        releaseWakeLock()

        try {
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
     * Fallback wake via transparent WakeActivity.
     *
     * On OnePlus/OxygenOS, WakeLock with ACQUIRE_CAUSES_WAKEUP does NOT fully wake the screen —
     * it stays in Dozing/AOD state. Launching WakeActivity with turnScreenOn/showWhenLocked
     * flags forces a full wake.
     */
    fun wakeViaActivity() {
        Timber.tag(TAG).w("wakeViaActivity() — launching WakeActivity as fallback")
        try {
            val intent = Intent(context, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to launch WakeActivity")
        }
    }

    /**
     * Launch WakeActivity with requestDismissKeyguard to show the PIN bouncer.
     *
     * On OnePlus/OxygenOS, dispatchGesture swipe does NOT trigger the PIN bouncer.
     * requestDismissKeyguard is the proper Android API to bring up the bouncer on all OEMs.
     */
    fun requestBouncer() {
        Timber.tag(TAG).w("requestBouncer() — launching WakeActivity with dismiss_keyguard")
        try {
            val intent = Intent(context, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(WakeActivity.EXTRA_DISMISS_KEYGUARD, true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to launch WakeActivity for bouncer")
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
