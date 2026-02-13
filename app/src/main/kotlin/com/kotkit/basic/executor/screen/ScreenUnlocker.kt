package com.kotkit.basic.executor.screen

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import timber.log.Timber
import com.kotkit.basic.data.local.keystore.KeystoreManager
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ScreenUnlocker - Handles automatic screen unlock via AccessibilityService.
 *
 * Two supported unlock modes:
 *   1. No PIN/password → Simple swipe via Accessibility
 *   2. PIN/password → Enter PIN via Accessibility (reads PIN pad from UI tree)
 *
 * This approach works on MIUI/HyperOS where shell `input` commands are blocked by SELinux.
 * Key insight: TalkBack (system Accessibility) works on lockscreen, so our service does too.
 */
@Singleton
class ScreenUnlocker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val screenWaker: ScreenWaker,
    private val lockManager: LockManager
) {
    companion object {
        private const val TAG = "ScreenUnlocker"
        private const val SWIPE_DURATION = 300L

        // Relative positions (percentage of screen)
        private const val SWIPE_START_Y_PERCENT = 0.9f
        private const val SWIPE_END_Y_PERCENT = 0.4f
        private const val SWIPE_X_PERCENT = 0.5f
    }

    private val screenWidth: Int
    private val screenHeight: Int

    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
    }

    suspend fun ensureUnlocked(): UnlockResult {
        val totalStartTime = System.currentTimeMillis()
        Timber.tag(TAG).w("⏱️ ensureUnlocked: starting")

        // 1. Wake screen and wait for it to actually be on
        val wakeStartTime = System.currentTimeMillis()
        screenWaker.wake()

        // Poll for screen to actually be interactive (MIUI can take 500-800ms)
        val maxWakeWaitMs = 2000L
        val wakeCheckInterval = 50L
        while (!screenWaker.isScreenOn() && System.currentTimeMillis() - wakeStartTime < maxWakeWaitMs) {
            delay(wakeCheckInterval)
        }
        // Extra settle time for MIUI keyguard to fully initialize after screen on
        delay(500)
        Timber.tag(TAG).w("⏱️ Wake + delay: ${System.currentTimeMillis() - wakeStartTime}ms (screenOn=${screenWaker.isScreenOn()})")

        // 2. Check if locked
        val isLocked = lockManager.isLocked()
        Timber.tag(TAG).w("isLocked: $isLocked")
        if (!isLocked) {
            Timber.tag(TAG).w("⏱️ Already unlocked, total: ${System.currentTimeMillis() - totalStartTime}ms")
            return UnlockResult.AlreadyUnlocked
        }

        // 3. Check Accessibility service
        val service = TikTokAccessibilityService.getInstance()
        if (service == null) {
            Timber.tag(TAG).w("Accessibility service not available")
            return UnlockResult.NeedUserAction("Включите Accessibility Service в настройках")
        }

        // 4. Determine lock type and unlock method
        val isSecure = lockManager.isDeviceSecure()
        Timber.tag(TAG).w("isDeviceSecure: $isSecure")

        val result = if (!isSecure) {
            // Swipe to unlock (no PIN/password)
            Timber.tag(TAG).w("PATH: Accessibility swipe (no PIN/password)")
            unlockSwipe(service)
        } else {
            // PIN unlock via Accessibility
            Timber.tag(TAG).w("PATH: Accessibility PIN entry")
            unlockWithPin(service)
        }
        Timber.tag(TAG).w("⏱️ ensureUnlocked TOTAL: ${System.currentTimeMillis() - totalStartTime}ms")
        return result
    }

    /**
     * Unlock using PIN via AccessibilityService.
     * Reads PIN pad coordinates from UI tree and taps each digit.
     */
    private suspend fun unlockWithPin(service: TikTokAccessibilityService): UnlockResult {
        val pin = keystoreManager.getStoredPin()
        if (pin == null) {
            Timber.tag(TAG).w("No PIN stored")
            return UnlockResult.NeedUserAction("Сохраните PIN в настройках приложения")
        }

        Timber.tag(TAG).w("Entering PIN via Accessibility (length=${pin.length})")
        val success = service.enterPin(pin, screenAlreadyAwake = true)

        if (!success) {
            Timber.tag(TAG).w("Failed to enter PIN via Accessibility")
            return UnlockResult.Failed("Не удалось ввести PIN. Проверьте, что экран PIN виден.")
        }

        // Poll for unlock status (MIUI/HyperOS can take up to 3s to close keyguard)
        val maxWaitMs = 3000L
        val checkIntervalMs = 100L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (!lockManager.isLocked()) {
                val elapsed = System.currentTimeMillis() - startTime
                Timber.tag(TAG).w("⏱️ PIN unlock confirmed after ${elapsed}ms")
                return UnlockResult.Success
            }
            delay(checkIntervalMs)
        }

        val stillLocked = lockManager.isLocked()
        Timber.tag(TAG).w("After PIN entry (${maxWaitMs}ms timeout), stillLocked: $stillLocked")

        return if (!stillLocked) {
            UnlockResult.Success
        } else {
            UnlockResult.Failed("PIN введён, но телефон остался заблокирован. Проверьте правильность PIN.")
        }
    }

    /**
     * Swipe to unlock (for devices without PIN/password).
     * Uses Accessibility service's dispatchGesture which works on lockscreen.
     */
    private suspend fun unlockSwipe(service: TikTokAccessibilityService): UnlockResult {
        Timber.tag(TAG).w("unlockSwipe: using Accessibility service")

        val swipeX = (screenWidth * SWIPE_X_PERCENT).toInt()
        val swipeStartY = (screenHeight * SWIPE_START_Y_PERCENT).toInt()
        val swipeEndY = (screenHeight * SWIPE_END_Y_PERCENT).toInt()

        Timber.tag(TAG).w("Accessibility swipe: ($swipeX, $swipeStartY) -> ($swipeX, $swipeEndY)")
        service.swipe(swipeX, swipeStartY, swipeX, swipeEndY, SWIPE_DURATION)

        // Poll for unlock status (faster than fixed delay)
        val maxWaitMs = 1500L
        val checkIntervalMs = 100L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (!lockManager.isLocked()) {
                val elapsed = System.currentTimeMillis() - startTime
                Timber.tag(TAG).w("⏱️ Swipe unlock confirmed after ${elapsed}ms")
                return UnlockResult.Success
            }
            delay(checkIntervalMs)
        }

        val stillLocked = lockManager.isLocked()
        Timber.tag(TAG).w("After swipe (${maxWaitMs}ms timeout), stillLocked: $stillLocked")
        return if (!stillLocked) {
            UnlockResult.Success
        } else {
            UnlockResult.Failed("Swipe unlock failed")
        }
    }
}

sealed class UnlockResult {
    object Success : UnlockResult()
    object AlreadyUnlocked : UnlockResult()
    data class Failed(val reason: String) : UnlockResult()
    data class NeedUserAction(val message: String) : UnlockResult()
    data class NotSupported(val message: String) : UnlockResult()
}
