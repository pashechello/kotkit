package com.kotkit.basic.executor.screen

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import timber.log.Timber

/**
 * Transparent Activity that reliably wakes the screen on all OEMs.
 *
 * On OnePlus/OxygenOS, WakeLock with ACQUIRE_CAUSES_WAKEUP does NOT fully wake the screen —
 * it stays in Dozing/AOD state. The Activity-based approach with turnScreenOn/showWhenLocked
 * flags is the only reliable way to force a full wake on these devices.
 *
 * When launched with EXTRA_DISMISS_KEYGUARD, also calls requestDismissKeyguard() to
 * bring up the PIN bouncer. This is needed because dispatchGesture swipe does NOT
 * trigger the PIN bouncer on OnePlus/OxygenOS lockscreen.
 *
 * This Activity auto-finishes after a short delay once the screen is on.
 */
class WakeActivity : Activity() {

    companion object {
        private const val TAG = "WakeActivity"
        /** Delay before auto-finishing (ms). Enough for screen to fully initialize. */
        private const val AUTO_FINISH_DELAY_MS = 500L
        /** Intent extra: set to true to request bouncer (PIN entry screen) */
        const val EXTRA_DISMISS_KEYGUARD = "dismiss_keyguard"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable {
        if (!isFinishing) {
            Timber.tag(TAG).w("WakeActivity auto-finishing")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.tag(TAG).w("WakeActivity created — forcing screen on")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val dismissKeyguard = intent?.getBooleanExtra(EXTRA_DISMISS_KEYGUARD, false) == true

        @Suppress("DEPRECATION")
        var flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON

        if (dismissKeyguard) {
            // FLAG_DISMISS_KEYGUARD: tells WindowManager to dismiss the keyguard.
            // For secure lockscreens (PIN/password), this triggers the bouncer.
            // This is a direct window flag — works even when requestDismissKeyguard() fails on some OEMs.
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            Timber.tag(TAG).w("WakeActivity: adding FLAG_DISMISS_KEYGUARD to trigger bouncer")
        }

        window.addFlags(flags)

        if (dismissKeyguard) {
            // Don't auto-finish quickly — need time for bouncer to appear.
            handler.postDelayed(finishRunnable, 5000L)
            // Also try requestDismissKeyguard API as belt-and-suspenders
            requestBouncerDismiss()
        } else {
            handler.postDelayed(finishRunnable, AUTO_FINISH_DELAY_MS)
        }
    }

    private fun requestBouncerDismiss() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager == null || !keyguardManager.isDeviceLocked) {
            Timber.tag(TAG).w("requestBouncerDismiss: not locked or no KeyguardManager")
            return
        }

        Timber.tag(TAG).w("requestBouncerDismiss: calling requestDismissKeyguard")
        keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissError() {
                Timber.tag(TAG).w("requestDismissKeyguard: onDismissError")
            }
            override fun onDismissSucceeded() {
                Timber.tag(TAG).w("requestDismissKeyguard: onDismissSucceeded — keyguard dismissed")
                if (!isFinishing) finish()
            }
            override fun onDismissCancelled() {
                Timber.tag(TAG).w("requestDismissKeyguard: onDismissCancelled")
            }
        })
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }
}
