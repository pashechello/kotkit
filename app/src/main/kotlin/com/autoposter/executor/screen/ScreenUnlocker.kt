package com.autoposter.executor.screen

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.autoposter.data.local.keystore.KeystoreManager
import com.autoposter.executor.accessibility.TikTokAccessibilityService
import com.autoposter.executor.accessibility.portal.UITreeParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class ScreenUnlocker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val screenWaker: ScreenWaker,
    private val lockManager: LockManager,
    private val uiTreeParser: UITreeParser
) {
    companion object {
        private const val SWIPE_DURATION = 300L

        // Relative positions (percentage of screen)
        private const val SWIPE_START_Y_PERCENT = 0.9f  // 90% from top
        private const val SWIPE_END_Y_PERCENT = 0.4f    // 40% from top
        private const val SWIPE_X_PERCENT = 0.5f        // Center

        // PIN pad relative positions
        private const val PIN_PAD_TOP_PERCENT = 0.55f   // PIN pad starts at 55% from top
        private const val PIN_PAD_ROW_HEIGHT_PERCENT = 0.09f
        private const val PIN_PAD_COLUMN_WIDTH_PERCENT = 0.33f
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
        val service = TikTokAccessibilityService.getInstance()
            ?: return UnlockResult.Failed("Accessibility service not available")

        // 1. Wake screen
        screenWaker.wake()
        delay(500)

        // 2. Check if locked
        if (!lockManager.isLocked()) {
            return UnlockResult.AlreadyUnlocked
        }

        // 3. Determine lock type and unlock
        return if (!lockManager.isDeviceSecure()) {
            // Swipe to unlock
            unlockSwipe(service)
        } else if (keystoreManager.hasStoredPin()) {
            unlockWithPin(service)
        } else if (keystoreManager.hasStoredPassword()) {
            unlockWithPassword(service)
        } else {
            UnlockResult.NeedUserAction("Please unlock your device or save PIN/password in settings")
        }
    }

    private suspend fun unlockSwipe(service: TikTokAccessibilityService): UnlockResult {
        val swipeX = (screenWidth * SWIPE_X_PERCENT).toInt()
        val swipeStartY = (screenHeight * SWIPE_START_Y_PERCENT).toInt()
        val swipeEndY = (screenHeight * SWIPE_END_Y_PERCENT).toInt()

        // Swipe up to dismiss lock screen
        service.swipe(swipeX, swipeStartY, swipeX, swipeEndY, SWIPE_DURATION)
        delay(500)

        return if (!lockManager.isLocked()) {
            UnlockResult.Success
        } else {
            UnlockResult.Failed("Swipe unlock failed")
        }
    }

    private suspend fun unlockWithPin(service: TikTokAccessibilityService): UnlockResult {
        val pin = keystoreManager.getStoredPin()
            ?: return UnlockResult.NeedUserAction("PIN not saved")

        val swipeX = (screenWidth * SWIPE_X_PERCENT).toInt()
        val swipeStartY = (screenHeight * SWIPE_START_Y_PERCENT).toInt()
        val swipeEndY = (screenHeight * SWIPE_END_Y_PERCENT).toInt()

        // Swipe to show PIN pad
        service.swipe(swipeX, swipeStartY, swipeX, swipeEndY, SWIPE_DURATION)
        delay(500)

        // Wait for PIN pad to appear
        delay(300)

        // Enter PIN digits
        for (digit in pin) {
            val buttonLocation = findPinButton(service, digit)
            if (buttonLocation != null) {
                service.tap(buttonLocation.first, buttonLocation.second)
                // Human-like random delay between digits
                delay(Random.nextLong(80, 150))
            } else {
                return UnlockResult.Failed("PIN button not found: $digit")
            }
        }

        delay(500)

        return if (!lockManager.isLocked()) {
            UnlockResult.Success
        } else {
            UnlockResult.Failed("Wrong PIN or unlock failed")
        }
    }

    private suspend fun unlockWithPassword(service: TikTokAccessibilityService): UnlockResult {
        val password = keystoreManager.getStoredPassword()
            ?: return UnlockResult.NeedUserAction("Password not saved")

        val swipeX = (screenWidth * SWIPE_X_PERCENT).toInt()
        val swipeStartY = (screenHeight * SWIPE_START_Y_PERCENT).toInt()
        val swipeEndY = (screenHeight * SWIPE_END_Y_PERCENT).toInt()

        // Swipe to show password field
        service.swipe(swipeX, swipeStartY, swipeX, swipeEndY, SWIPE_DURATION)
        delay(500)

        // Find password field
        val uiTree = service.getUITree()
        val passwordField = uiTreeParser.findInputFields(uiTree).firstOrNull()
            ?: return UnlockResult.Failed("Password field not found")

        // Tap on password field
        service.tap(passwordField.centerX, passwordField.centerY)
        delay(300)

        // Type password
        if (!service.type(password)) {
            return UnlockResult.Failed("Failed to type password")
        }
        delay(200)

        // Find and tap Enter/OK button
        val enterButton = uiTreeParser.findElementByText(uiTree, "OK")
            ?: uiTreeParser.findElementByText(uiTree, "Enter")
            ?: uiTreeParser.findElementByText(uiTree, "Done")
            ?: uiTreeParser.findElementByText(uiTree, "Готово")

        if (enterButton != null) {
            service.tap(enterButton.centerX, enterButton.centerY)
        } else {
            service.pressBack() // Sometimes Enter key works
        }

        delay(500)

        return if (!lockManager.isLocked()) {
            UnlockResult.Success
        } else {
            UnlockResult.Failed("Wrong password or unlock failed")
        }
    }

    /**
     * Find PIN button location by digit.
     * First tries to find the button in the UI tree (most reliable).
     * Falls back to calculated positions based on screen size.
     */
    private fun findPinButton(service: TikTokAccessibilityService, digit: Char): Pair<Int, Int>? {
        // Try to find button by text in UI tree (preferred method)
        val uiTree = service.getUITree()
        val button = uiTreeParser.findElementByText(uiTree, digit.toString())
        if (button != null && button.isClickable) {
            return Pair(button.centerX, button.centerY)
        }

        // Fallback to calculated positions based on screen size
        // Standard Android PIN pad layout: 3 columns, 4 rows
        val columnWidth = (screenWidth * PIN_PAD_COLUMN_WIDTH_PERCENT).toInt()
        val rowHeight = (screenHeight * PIN_PAD_ROW_HEIGHT_PERCENT).toInt()
        val padTop = (screenHeight * PIN_PAD_TOP_PERCENT).toInt()

        // Column centers (left, center, right)
        val col1 = columnWidth / 2
        val col2 = screenWidth / 2
        val col3 = screenWidth - columnWidth / 2

        return when (digit) {
            '1' -> Pair(col1, padTop)
            '2' -> Pair(col2, padTop)
            '3' -> Pair(col3, padTop)
            '4' -> Pair(col1, padTop + rowHeight)
            '5' -> Pair(col2, padTop + rowHeight)
            '6' -> Pair(col3, padTop + rowHeight)
            '7' -> Pair(col1, padTop + 2 * rowHeight)
            '8' -> Pair(col2, padTop + 2 * rowHeight)
            '9' -> Pair(col3, padTop + 2 * rowHeight)
            '0' -> Pair(col2, padTop + 3 * rowHeight)
            else -> null
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
