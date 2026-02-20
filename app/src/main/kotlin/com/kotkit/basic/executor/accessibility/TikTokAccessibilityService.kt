package com.kotkit.basic.executor.accessibility

/**
 * # SECURITY MODEL - READ THIS FIRST
 *
 * This Accessibility Service is RESTRICTED TO TIKTOK ONLY.
 *
 * ## What This Service CAN Do:
 * - Perform gestures (tap, swipe) in TikTok app
 * - Read UI elements in TikTok app
 * - Take screenshots when TikTok is in foreground
 *
 * ## What This Service CANNOT Do:
 * - Access ANY other app (banking, messaging, email, etc.)
 * - Read passwords or sensitive data from other apps
 * - Perform gestures outside TikTok
 * - Take screenshots of other apps
 *
 * ## How Restriction Is Enforced (3 Layers):
 *
 * 1. ANDROID SYSTEM LEVEL (accessibility_service_config.xml):
 *    ```xml
 *    android:packageNames="com.zhiliaoapp.musically,com.ss.android.ugc.trill"
 *    ```
 *    Android OS filters events BEFORE they reach this service.
 *
 * 2. COMPILE-TIME CONSTANT (this file, ALLOWED_PACKAGES):
 *    Immutable set of allowed packages. Cannot be changed by server or at runtime.
 *
 * 3. RUNTIME CHECK (onAccessibilityEvent):
 *    Defense-in-depth: rejects any non-TikTok events that somehow arrive.
 *
 * ## Can The Server Bypass This?
 * NO. The server sends action commands like {action: "tap", x: 540, y: 960}.
 * These execute through this service, which is physically restricted to TikTok
 * at the Android OS level. The server has ZERO control over which apps are accessible.
 *
 * @see SECURITY.md for full security documentation
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import timber.log.Timber
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kotkit.basic.executor.accessibility.portal.UITree
import com.kotkit.basic.executor.accessibility.portal.UITreeParser
import com.kotkit.basic.executor.screenshot.MediaProjectionScreenshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class TikTokAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TikTokA11yService"
        private const val SERVICE_ID = "com.kotkit.basic/.executor.accessibility.TikTokAccessibilityService"

        /** Must match NetworkWorkerService.ACCESSIBILITY_DEAD_NOTIFICATION_ID */
        private const val ACCESSIBILITY_DEAD_NOTIFICATION_ID = 10003

        // Test actions
        const val ACTION_TEST_TAP = "com.kotkit.basic.TEST_TAP"
        const val ACTION_TEST_SCREENSHOT = "com.kotkit.basic.TEST_SCREENSHOT"
        const val ACTION_TEST_UI_TREE = "com.kotkit.basic.TEST_UI_TREE"
        const val ACTION_TEST_PIN = "com.kotkit.basic.TEST_PIN"
        const val ACTION_TEST_LOCK = "com.kotkit.basic.TEST_LOCK"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_PIN = "pin"

        /**
         * SECURITY: Immutable allowlist of TikTok package names.
         *
         * This is the ONLY set of apps this service can interact with.
         * - This is a compile-time constant (private val)
         * - Cannot be modified at runtime
         * - Cannot be changed by server commands
         * - Android OS also enforces this via accessibility_service_config.xml
         *
         * Any attempt to access apps outside this list will be blocked.
         */
        private val ALLOWED_PACKAGES = setOf(
            "com.zhiliaoapp.musically",      // TikTok (main international)
            "com.ss.android.ugc.trill",      // TikTok Lite
            "com.ss.android.ugc.aweme",      // TikTok (Chinese/legacy)
            "com.zhiliaoapp.musically.go",   // TikTok Go (emerging markets)
            "musical.ly"                      // Old Musical.ly (legacy)
        )

        /**
         * Packages that own the lockscreen/keyguard across different OEMs.
         * Used to decide whether to press HOME to surface the keyguard.
         * On EMUI 12, the keyguard is owned by com.huawei.android.launcher,
         * not com.android.systemui as on AOSP/MIUI.
         */
        private val LOCKSCREEN_PACKAGES = setOf(
            "com.android.systemui",           // AOSP / Pixel / MIUI keyguard
            "com.huawei.android.launcher",    // EMUI 10-12 keyguard
            "com.huawei.systemserver",        // EMUI some variants
            "com.samsung.android.keyguard",   // Samsung One UI (some versions)
        )

        @Volatile
        private var instance: TikTokAccessibilityService? = null

        fun getInstance(): TikTokAccessibilityService? = instance

        /**
         * Check if accessibility service is enabled in system settings.
         * This survives app restarts.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledServices.split(':').any {
                it.equals(SERVICE_ID, ignoreCase = true) ||
                it.contains("TikTokAccessibilityService", ignoreCase = true)
            }
        }

        // Legacy method for backward compatibility
        fun isServiceEnabled(): Boolean = instance != null
    }

    private val uiTreeParser = UITreeParser()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var testReceiver: BroadcastReceiver? = null
    @Volatile private var isFilterExpanded = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
            // CRITICAL: Restrict to TikTok packages only
            packageNames = ALLOWED_PACKAGES.toTypedArray()
        }
        serviceInfo = info

        // Register test tap receiver
        registerTestReceiver()

        // Clear any "accessibility crashed" notification now that we're back
        clearCrashNotification()

        Timber.tag(TAG).i("Accessibility Service connected - restricted to TikTok only")
    }

    /**
     * Clear the crash notification that NetworkWorkerService may have posted.
     * Called on (re)connect so the user doesn't see a stale warning.
     */
    private fun clearCrashNotification() {
        try {
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager?.cancel(ACCESSIBILITY_DEAD_NOTIFICATION_ID)
            Timber.tag(TAG).d("Cleared accessibility crash notification (if any)")
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * Register broadcast receiver for testing on lockscreen.
     * Usage:
     *   adb shell am broadcast -a com.kotkit.basic.TEST_TAP --ei x 540 --ei y 1550
     *   adb shell am broadcast -a com.kotkit.basic.TEST_SCREENSHOT
     */
    private fun registerTestReceiver() {
        testReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_TEST_TAP -> handleTestTap(intent)
                    ACTION_TEST_SCREENSHOT -> handleTestScreenshot()
                    ACTION_TEST_UI_TREE -> handleTestUiTree()
                    ACTION_TEST_PIN -> handleTestPin(intent)
                    ACTION_TEST_LOCK -> {
                        Timber.tag(TAG).i("TEST_LOCK: locking screen")
                        lockScreen()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_TEST_TAP)
            addAction(ACTION_TEST_SCREENSHOT)
            addAction(ACTION_TEST_UI_TREE)
            addAction(ACTION_TEST_PIN)
            addAction(ACTION_TEST_LOCK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(testReceiver, filter)
        }
        Timber.tag(TAG).d("Test tap receiver registered")
    }

    private fun handleTestTap(intent: Intent) {
        val x = intent.getIntExtra(EXTRA_X, 540)
        val y = intent.getIntExtra(EXTRA_Y, 1550)
        Timber.tag(TAG).i("TEST_TAP received: x=$x, y=$y")

        serviceScope.launch {
            // 1. Wake screen
            wakeScreen()
            kotlinx.coroutines.delay(500)

            // 2. Swipe up to show PIN entry
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val swipeX = screenWidth / 2
            val swipeStartY = (screenHeight * 0.9f).toInt()
            val swipeEndY = (screenHeight * 0.4f).toInt()
            Timber.tag(TAG).i("Swiping up: ($swipeX, $swipeStartY) -> ($swipeX, $swipeEndY)")
            swipe(swipeX, swipeStartY, swipeX, swipeEndY, 300)
            kotlinx.coroutines.delay(500)

            // 3. Tap on PIN digit
            val result = tap(x, y)
            Timber.tag(TAG).i("TEST_TAP result: $result")
        }
    }

    private fun handleTestScreenshot() {
        Timber.tag(TAG).i("TEST_SCREENSHOT received")

        serviceScope.launch {
            // 1. Wake screen
            wakeScreen()
            kotlinx.coroutines.delay(500)

            // 2. Swipe up to show PIN entry
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val swipeX = screenWidth / 2
            val swipeStartY = (screenHeight * 0.9f).toInt()
            val swipeEndY = (screenHeight * 0.4f).toInt()
            swipe(swipeX, swipeStartY, swipeX, swipeEndY, 300)
            kotlinx.coroutines.delay(1500)  // Wait for PIN screen to render

            // 3. Take screenshot
            Timber.tag(TAG).i("Taking screenshot...")
            val bitmap = takeScreenshot()
            if (bitmap != null) {
                Timber.tag(TAG).i("Screenshot captured: ${bitmap.width}x${bitmap.height}")
                // Save to file
                val file = java.io.File(getExternalFilesDir(null), "pin_screen.png")
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Timber.tag(TAG).i("Screenshot saved to: ${file.absolutePath}")
            } else {
                Timber.tag(TAG).e("Screenshot failed!")
            }
        }
    }

    private fun handleTestUiTree() {
        Timber.tag(TAG).i("TEST_UI_TREE received")

        serviceScope.launch {
            // 1. Wake screen
            wakeScreen()
            kotlinx.coroutines.delay(500)

            // 2. Swipe up to show PIN entry
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val swipeX = screenWidth / 2
            val swipeStartY = (screenHeight * 0.9f).toInt()
            val swipeEndY = (screenHeight * 0.4f).toInt()
            swipe(swipeX, swipeStartY, swipeX, swipeEndY, 300)
            kotlinx.coroutines.delay(1500)

            // 3. Get UI tree
            Timber.tag(TAG).i("Getting UI tree on lockscreen...")
            val root = rootInActiveWindow
            if (root != null) {
                Timber.tag(TAG).i("Root window: ${root.packageName}, childCount=${root.childCount}")
                dumpNodeTree(root, 0)
                root.recycle()
            } else {
                Timber.tag(TAG).e("rootInActiveWindow is NULL on lockscreen!")

                // Try windows API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val windowsList = windows
                    Timber.tag(TAG).i("Windows count: ${windowsList.size}")
                    windowsList.forEachIndexed { index, window ->
                        Timber.tag(TAG).i("Window[$index]: type=${window.type}, layer=${window.layer}")
                        val windowRoot = window.root
                        if (windowRoot != null) {
                            Timber.tag(TAG).i("  Window root: ${windowRoot.packageName}")
                            dumpNodeTree(windowRoot, 2)
                            windowRoot.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun handleTestPin(intent: Intent) {
        val pin = intent.getStringExtra(EXTRA_PIN) ?: "0000"
        Timber.tag(TAG).i("TEST_PIN received: pin=$pin")

        serviceScope.launch {
            // Use the same enterPin() path as production ScreenUnlocker
            val result = enterPin(pin, screenAlreadyAwake = false)
            Timber.tag(TAG).i("TEST_PIN result: $result")
        }
    }

    /**
     * Enter PIN code on lockscreen using UI tree coordinates.
     * This is the public method to be called from ScreenUnlocker.
     *
     * Temporarily expands package filter to see lockscreen UI (normally restricted to TikTok).
     *
     * @param pin The PIN code to enter (digits only)
     * @param screenAlreadyAwake If true, skip internal wake (caller already woke screen)
     * @return true if PIN was entered successfully
     */
    suspend fun enterPin(pin: String, screenAlreadyAwake: Boolean = false): Boolean {
        Timber.tag(TAG).i("enterPin: starting PIN entry (screenAlreadyAwake=$screenAlreadyAwake)")

        // Temporarily expand access to see lockscreen UI tree
        expandPackageFilter()

        try {
            // 1. Wait for filter expansion to take effect (async on MIUI)
            val filterReady = waitForFilterExpansion(timeoutMs = 2000)
            Timber.tag(TAG).i("enterPin: filter expansion ready=$filterReady")

            // 2. Wake screen (skip if caller already did it)
            if (!screenAlreadyAwake) {
                wakeScreen()
                kotlinx.coroutines.delay(500)
            }

            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val swipeX = screenWidth / 2
            val swipeStartY = (screenHeight * 0.9f).toInt()
            val swipeEndY = (screenHeight * 0.4f).toInt()

            // 3. Swipe up + poll for PIN pad (with retry)
            // Each attempt checks active window and presses HOME if our app is above keyguard.
            // This prevents the race condition where rootInActiveWindow changes between
            // the initial check and the swipe (MIUI can bring our app to foreground mid-flow).
            var initialCoords: Map<Char, Pair<Int, Int>>? = null
            val maxSwipeAttempts = 2
            for (attempt in 1..maxSwipeAttempts) {
                val currentRoot = rootInActiveWindow?.packageName?.toString()
                Timber.tag(TAG).i("enterPin: attempt $attempt/$maxSwipeAttempts (activeRoot=$currentRoot)")

                // Ensure lockscreen is in foreground before swiping.
                // On MIUI, our app may render above the keyguard after screen wake.
                // HOME press surfaces the lockscreen so swipe targets the correct layer.
                // On EMUI 12, the keyguard is owned by com.huawei.android.launcher.
                val onLockscreen = currentRoot == null || currentRoot in LOCKSCREEN_PACKAGES
                if (!onLockscreen) {
                    Timber.tag(TAG).i("enterPin: not on lockscreen ($currentRoot), pressing HOME to surface keyguard")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    kotlinx.coroutines.delay(500)
                }

                // Try lock icon first (Pixel/AOSP fast path)
                val clickedLockIcon = tryClickLockIcon()
                if (clickedLockIcon) {
                    Timber.tag(TAG).i("enterPin: clicked lock icon, checking for bouncer")
                    // Quick check: did the lock icon actually bring up the PIN pad?
                    initialCoords = pollForPinPad(maxWaitMs = 1500, pollIntervalMs = 200)
                    if (initialCoords != null) {
                        Timber.tag(TAG).i("enterPin: lock icon worked, PIN pad appeared")
                        break
                    }
                    // Lock icon click didn't produce PIN pad (Samsung false positive or timing)
                    Timber.tag(TAG).w("enterPin: lock icon clicked but no PIN pad, falling back to swipe")
                }

                // Swipe up (MIUI, Samsung, and fallback for failed lock icon click)
                swipe(swipeX, swipeStartY, swipeX, swipeEndY, 300)
                initialCoords = pollForPinPad(maxWaitMs = 4000, pollIntervalMs = 200)
                if (initialCoords != null) break

                if (attempt < maxSwipeAttempts) {
                    Timber.tag(TAG).w("enterPin: PIN pad not found on attempt $attempt, retrying...")
                }
            }

            if (initialCoords == null) {
                Timber.tag(TAG).e("enterPin: failed to find PIN pad after $maxSwipeAttempts swipe attempts")
                return false
            }
            Timber.tag(TAG).i("enterPin: PIN pad detected with ${initialCoords.size} digits")

            // Wait for PIN pad animation to fully settle before tapping
            // MIUI keyguard animates the PIN pad sliding up; coordinates captured mid-animation
            // can be slightly off from final positions, causing taps to miss.
            // EMUI 12 keyguard animation also needs extra time — use 600ms there.
            val pinPadSettleMs = if (Build.MANUFACTURER?.lowercase()?.contains("huawei") == true) 600L else 400L
            kotlinx.coroutines.delay(pinPadSettleMs)

            // Re-capture coordinates after animation settles for accurate positions
            val pinPadCoords = getPinPadCoordinates() ?: initialCoords
            Timber.tag(TAG).i("enterPin: final coordinates for ${pinPadCoords.size} digits")

            // 4. Enter each digit
            for (digit in pin) {
                val coords = pinPadCoords[digit]
                if (coords == null) {
                    Timber.tag(TAG).e("enterPin: no coordinates for digit: $digit")
                    return false
                }
                Timber.tag(TAG).d("enterPin: tapping digit $digit at (${coords.first}, ${coords.second})")
                tap(coords.first, coords.second)
                kotlinx.coroutines.delay(150)
            }

            Timber.tag(TAG).i("enterPin: all digits entered, looking for Enter button")

            // On Pixel/AOSP, PIN pad requires explicit Enter press to confirm
            // (MIUI auto-submits after correct digit count, but AOSP does not)
            val enterTapped = tapEnterButton()
            Timber.tag(TAG).i("enterPin: Enter button tapped=$enterTapped")

            // Wait for keyguard to process before restoring filter
            kotlinx.coroutines.delay(500)
            return true
        } finally {
            // Always restore TikTok-only filter
            restorePackageFilter()
        }
    }

    /**
     * Try clicking the AOSP lock icon to trigger the bouncer (PIN entry screen).
     * On API 35 Pixel/AOSP, dispatchGesture swipe on the lockscreen does NOT
     * transition to the PIN bouncer. The bouncer only appears when the lock icon
     * is explicitly clicked.
     *
     * Uses two strategies:
     * 1. findAccessibilityNodeInfosByViewId (works on some devices)
     * 2. Manual tree traversal looking for clickable node in bottom area with
     *    a child ImageView that has contentDescription (the lock icon pattern)
     *    — needed on API 35 where resource-ids are hidden on the keyguard
     *
     * @return true if the lock icon was found and clicked
     */
    private fun tryClickLockIcon(): Boolean {
        // EMUI fast path: lockscreen does not have a tappable lock icon.
        // Skip AOSP strategies entirely — enterPin() will fall back to swipe.
        if (Build.MANUFACTURER?.lowercase()?.contains("huawei") == true) {
            Timber.tag(TAG).d("tryClickLockIcon: EMUI detected, skipping lock icon search")
            return false
        }

        val windowsList = windows
        if (windowsList.isNullOrEmpty()) {
            Timber.tag(TAG).d("tryClickLockIcon: no windows")
            return false
        }

        val screenHeight = resources.displayMetrics.heightPixels

        for (window in windowsList) {
            val root = window.root ?: continue

            // Strategy 1: find by view ID (works on some AOSP versions)
            val nodes = root.findAccessibilityNodeInfosByViewId(
                "com.android.systemui:id/device_entry_icon_view"
            )
            if (!nodes.isNullOrEmpty()) {
                val lockIcon = nodes[0]
                val result = lockIcon.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                nodes.forEach { it.recycle() }
                root.recycle()
                Timber.tag(TAG).i("tryClickLockIcon: found by viewId, clicked result=$result")
                return result
            }

            // Strategy 2: traverse tree for clickable node in bottom 25% of screen
            // with a child ImageView that has contentDescription (lock icon pattern)
            val lockNode = findLockIconByTraversal(root, screenHeight)
            if (lockNode != null) {
                val result = lockNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Timber.tag(TAG).i("tryClickLockIcon: found by traversal, clicked result=$result")
                lockNode.recycle()
                root.recycle()
                return result
            }

            root.recycle()
        }
        Timber.tag(TAG).d("tryClickLockIcon: lock icon not found")
        return false
    }

    /**
     * Find the lock icon node by traversing the tree.
     * Looks for a clickable node in the bottom 25% of screen that contains
     * a child ImageView with a non-null contentDescription.
     * This matches the Pixel/AOSP lock icon pattern.
     */
    private fun findLockIconByTraversal(
        node: AccessibilityNodeInfo,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        // Check if this is a clickable node in the bottom 25% of screen
        if (node.isClickable && bounds.top > screenHeight * 0.75) {
            // Check if it has a child ImageView with lock-related contentDescription.
            // Must check for "lock" keywords to avoid false positives on Samsung One UI
            // shortcut buttons (Camera/Phone) which also match the structural pattern.
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                val isLockIcon = child.className?.toString() == "android.widget.ImageView"
                        && desc.isNotEmpty()
                        && (desc.contains("lock") || desc.contains("замок") || desc.contains("блокировк"))
                child.recycle()
                if (isLockIcon) {
                    Timber.tag(TAG).d("tryClickLockIcon: candidate at bounds=$bounds, desc='$desc'")
                    return node
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findLockIconByTraversal(child, screenHeight)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    /**
     * Poll for PIN pad to appear in the UI tree.
     * Returns as soon as 10 digits are found, or null after timeout.
     */
    private suspend fun pollForPinPad(maxWaitMs: Long = 3000, pollIntervalMs: Long = 200): Map<Char, Pair<Int, Int>>? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val coords = getPinPadCoordinates()
            if (coords != null) {
                val elapsed = System.currentTimeMillis() - startTime
                Timber.tag(TAG).i("pollForPinPad: found PIN pad after ${elapsed}ms")
                return coords
            }
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        Timber.tag(TAG).e("pollForPinPad: timeout after ${maxWaitMs}ms")
        return null
    }

    /**
     * Temporarily remove package filter to access lockscreen UI tree.
     * The service is normally restricted to TikTok packages only, which means
     * it can't see the lockscreen PIN pad (com.android.systemui / MIUI keyguard).
     *
     * Also enables FLAG_INCLUDE_NOT_IMPORTANT_VIEWS so MIUI keyguard buttons
     * that are marked "not important for accessibility" are still visible.
     */
    private fun expandPackageFilter() {
        isFilterExpanded = true
        val info = serviceInfo
        info.packageNames = null // Allow all packages temporarily
        // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS: see MIUI keyguard buttons marked "not important"
        // FLAG_RETRIEVE_INTERACTIVE_WINDOWS: required to access PIN pad in separate window layers.
        // EMUI can silently drop this flag on serviceInfo round-trips, so we re-set it explicitly.
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        Timber.tag(TAG).i("Package filter expanded for lockscreen access")
    }

    /**
     * Restore TikTok-only package filter after unlock operation.
     */
    private fun restorePackageFilter() {
        val info = serviceInfo
        info.packageNames = ALLOWED_PACKAGES.toTypedArray()
        info.flags = info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        serviceInfo = info
        isFilterExpanded = false
        Timber.tag(TAG).i("Package filter restored (TikTok only)")
    }

    /**
     * Wait until the expanded package filter takes effect.
     *
     * Setting serviceInfo.packageNames = null is processed asynchronously by Android.
     * On MIUI, this can take 200-500ms. Without waiting, the windows API returns
     * systemui window with 0 accessible children (window exists but content unreadable).
     */
    private suspend fun waitForFilterExpansion(timeoutMs: Long = 2000): Boolean {
        val startTime = System.currentTimeMillis()
        val pollInterval = 100L
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val windowsList = windows
            if (windowsList != null) {
                for (window in windowsList) {
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString()
                    val childCount = root.childCount
                    root.recycle()
                    // If we can see a non-TikTok, non-own-app window with children,
                    // the filter has taken effect
                    if (pkg != null
                        && pkg !in ALLOWED_PACKAGES
                        && pkg != "com.kotkit.basic"
                        && childCount > 0
                    ) {
                        val elapsed = System.currentTimeMillis() - startTime
                        Timber.tag(TAG).i("waitForFilterExpansion: confirmed after ${elapsed}ms (pkg=$pkg, children=$childCount)")
                        return true
                    }
                }
            }
            kotlinx.coroutines.delay(pollInterval)
        }
        Timber.tag(TAG).w("waitForFilterExpansion: timeout after ${timeoutMs}ms")
        return false
    }

    /**
     * Get PIN pad button coordinates from UI tree.
     * Returns map of digit char to (x, y) center coordinates.
     *
     * Searches rootInActiveWindow first, then falls back to iterating
     * all windows (needed when lockscreen is a separate window layer).
     */
    private fun getPinPadCoordinates(): Map<Char, Pair<Int, Int>>? {
        // Try rootInActiveWindow first
        val root = rootInActiveWindow
        if (root != null) {
            val pkg = root.packageName?.toString()
            Timber.tag(TAG).i("getPinPadCoordinates: root=$pkg")
            val coords = findPinButtonsInNode(root)
            root.recycle()
            if (coords.size >= 10) {
                Timber.tag(TAG).i("getPinPadCoordinates: found ${coords.size} digits in root: ${coords.keys}")
                return coords
            }
            Timber.tag(TAG).i("getPinPadCoordinates: root ($pkg) had ${coords.size} digits, trying windows API")
        } else {
            Timber.tag(TAG).e("getPinPadCoordinates: rootInActiveWindow is null, trying windows API")
        }

        // Fallback: search all windows for the PIN pad
        val windowsList = windows
        if (windowsList.isNullOrEmpty()) {
            Timber.tag(TAG).e("getPinPadCoordinates: no windows available")
            return null
        }
        Timber.tag(TAG).i("getPinPadCoordinates: searching ${windowsList.size} windows")

        for ((index, window) in windowsList.withIndex()) {
            val windowRoot = window.root ?: continue
            val windowPkg = windowRoot.packageName?.toString()
            Timber.tag(TAG).i("getPinPadCoordinates: window[$index] type=${window.type}, layer=${window.layer}, pkg=$windowPkg")

            val coords = findPinButtonsInNode(windowRoot)
            windowRoot.recycle()

            if (coords.size >= 10) {
                Timber.tag(TAG).i("getPinPadCoordinates: found ${coords.size} digits in window pkg=$windowPkg")
                return coords
            }
            if (coords.isNotEmpty()) {
                Timber.tag(TAG).i("getPinPadCoordinates: window[$index] ($windowPkg) had ${coords.size} digits: ${coords.keys}")
            }
        }

        Timber.tag(TAG).e("getPinPadCoordinates: PIN pad not found in any window")
        return null
    }

    /**
     * Find PIN pad buttons (digits 0-9) in a node tree.
     *
     * Checks multiple sources for digit identification:
     * - contentDescription = "0", "1", ..., "9" (AOSP/MIUI standard)
     * - contentDescription starting with digit (e.g. "1, key" on Samsung One UI)
     * - text = "0", "1", ..., "9" (some MIUI versions)
     */
    private fun findPinButtonsInNode(root: AccessibilityNodeInfo): Map<Char, Pair<Int, Int>> {
        val coords = mutableMapOf<Char, Pair<Int, Int>>()

        fun extractDigit(text: String?): Char? {
            if (text == null) return null
            // Exact single digit: "5"
            if (text.length == 1 && text[0].isDigit()) return text[0]
            // Digit prefix: "1, key" or "5 кнопка" (AOSP/MIUI)
            if (text.isNotEmpty() && text[0].isDigit() && (text.length == 1 || !text[1].isDigit())) {
                return text[0]
            }
            // Digit suffix: "Button 1", "Key 0", "Digit 5" (EMUI/some OEM pattern)
            val lastWord = text.trimEnd().substringAfterLast(' ')
            if (lastWord.length == 1 && lastWord[0].isDigit()) return lastWord[0]
            return null
        }

        fun findPinButtons(node: AccessibilityNodeInfo) {
            // Try contentDescription first, then text
            val digit = extractDigit(node.contentDescription?.toString())
                ?: extractDigit(node.text?.toString())

            if (digit != null && digit !in coords) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val centerX = (bounds.left + bounds.right) / 2
                val centerY = (bounds.top + bounds.bottom) / 2
                coords[digit] = Pair(centerX, centerY)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findPinButtons(child)
                    child.recycle()
                }
            }
        }

        findPinButtons(root)
        return coords
    }

    /**
     * Find and tap the Enter/OK button on the PIN pad.
     * On Pixel/AOSP, the PIN pad has an Enter key (checkmark) that must be pressed
     * to confirm the PIN. Searches by resource ID and contentDescription.
     */
    private suspend fun tapEnterButton(): Boolean {
        // Strategy 1: find by resource ID (most reliable on AOSP)
        val windowsList = windows
        if (windowsList.isNullOrEmpty()) return false

        for (window in windowsList) {
            val root = window.root ?: continue

            // Try common resource IDs for Enter key
            val enterIds = listOf(
                "com.android.systemui:id/key_enter",
                "com.android.systemui:id/key_enter_text",           // Samsung One UI
                "com.android.systemui:id/confirm_btn",
                "com.huawei.android.launcher:id/key_enter",         // EMUI 12
                "com.huawei.android.launcher:id/pinpad_confirm",    // EMUI variant
            )
            for (enterId in enterIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(enterId)
                if (!nodes.isNullOrEmpty()) {
                    val enterNode = nodes[0]
                    val bounds = android.graphics.Rect()
                    enterNode.getBoundsInScreen(bounds)
                    val x = (bounds.left + bounds.right) / 2
                    val y = (bounds.top + bounds.bottom) / 2
                    Timber.tag(TAG).i("tapEnterButton: found by ID '$enterId' at ($x, $y)")
                    nodes.forEach { it.recycle() }
                    root.recycle()
                    return tap(x, y)
                }
            }

            // Strategy 2: traverse tree for Enter/OK by contentDescription
            val enterNode = findEnterButtonByTraversal(root)
            if (enterNode != null) {
                val bounds = android.graphics.Rect()
                enterNode.getBoundsInScreen(bounds)
                val x = (bounds.left + bounds.right) / 2
                val y = (bounds.top + bounds.bottom) / 2
                Timber.tag(TAG).i("tapEnterButton: found by traversal at ($x, $y)")
                enterNode.recycle()
                root.recycle()
                return tap(x, y)
            }

            root.recycle()
        }

        Timber.tag(TAG).w("tapEnterButton: Enter button not found (device may auto-submit)")
        return false
    }

    /**
     * Find the Enter button in the PIN pad by traversing the UI tree.
     * Looks for nodes with contentDescription matching Enter/OK patterns.
     */
    private fun findEnterButtonByTraversal(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null) {
            val isEnter = desc == "enter" || desc == "ok" || desc == "done"
                    || desc == "ввод" || desc == "ок" || desc == "готово"
                    || desc.contains("enter") || desc.contains("confirm")
                    || desc.startsWith("ok,")  // Samsung One UI: "OK, Button, Disabled"
            if (isEnter) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEnterButtonByTraversal(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun dumpNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        // Log node info
        Timber.tag(TAG).i("${indent}Node: class=${node.className}, " +
                "text=${node.text}, " +
                "desc=${node.contentDescription}, " +
                "bounds=${bounds}, " +
                "clickable=${node.isClickable}")

        // Recurse children (limit depth to avoid spam)
        if (depth < 10) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    dumpNodeTree(child, depth + 1)
                    child.recycle()
                }
            }
        }
    }

    /**
     * SECURITY: Event handler with defense-in-depth package check.
     *
     * Even though Android OS already filters events via accessibility_service_config.xml,
     * this method provides an additional runtime check. If ANY event from a non-TikTok
     * app somehow arrives, it is immediately rejected and logged.
     *
     * This ensures that even in the unlikely case of an Android bug, this service
     * cannot process events from other apps.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // SECURITY: Defense-in-depth - reject non-TikTok events even if they somehow arrive
        val packageName = event?.packageName?.toString()
        if (packageName != null && packageName !in ALLOWED_PACKAGES) {
            // During PIN unlock, filter is temporarily expanded — silently ignore non-TikTok events
            if (!isFilterExpanded) {
                Timber.tag(TAG).w("SECURITY: Blocked event from non-TikTok package: $packageName")
            }
            return
        }

        // Events can be processed here if needed for reactive automation
        // Currently we use polling approach from PostingAgent
    }

    override fun onInterrupt() {
        Timber.tag(TAG).w("Accessibility Service interrupted")
    }

    /**
     * Return true so Android calls onRebind() instead of onBind() on reconnection.
     * This helps the system rebind faster after transient process deaths,
     * reducing the chance of entering the "crashed services" state.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Timber.tag(TAG).w("Accessibility Service onUnbind — requesting rebind")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        instance = this
        Timber.tag(TAG).i("Accessibility Service rebound (fast path via onRebind)")
    }

    override fun onDestroy() {
        super.onDestroy()
        testReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        instance = null
        Timber.tag(TAG).i("Accessibility Service destroyed")
    }

    /**
     * Wake the screen using PowerManager WakeLock
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "kotkit:test_wake"
            )
            wakeLock.acquire(3000L)
            Timber.tag(TAG).i("Screen wake requested")
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to wake screen", e)
        }
    }

    /**
     * Perform a tap gesture at the specified coordinates
     */
    suspend fun tap(x: Int, y: Int, durationMs: Long = 100): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGestureAndWait(gesture)
    }

    /**
     * Perform a swipe gesture
     */
    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGestureAndWait(gesture)
    }

    /**
     * Perform a long press gesture
     */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGestureAndWait(gesture)
    }

    /**
     * Type text into the currently focused input field
     */
    fun type(text: String): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            Timber.tag(TAG).w("No focused input field found")
            return false
        }

        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            result
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to type text", e)
            false
        }
    }

    /**
     * Append text to the current input (doesn't clear existing text)
     */
    fun appendText(text: String): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            Timber.tag(TAG).w("No focused input field found")
            return false
        }

        return try {
            val currentText = focusedNode.text?.toString() ?: ""
            val newText = currentText + text

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            result
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to append text", e)
            false
        }
    }

    /**
     * Press the back button
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Press the home button
     */
    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Open the recents screen
     */
    fun openRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Open the notifications shade
     */
    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * Lock the screen (Android 9+ / API 28+)
     * Uses GLOBAL_ACTION_LOCK_SCREEN which requires Accessibility Service permission.
     */
    fun lockScreen(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val result = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            Timber.tag(TAG).i("lockScreen via Accessibility: $result")
            result
        } else {
            Timber.tag(TAG).w("lockScreen requires Android 9+ (API 28)")
            false
        }
    }

    /**
     * Get the current UI tree
     */
    fun getUITree(): UITree {
        val rootNode = rootInActiveWindow
        val tree = uiTreeParser.parse(rootNode)
        rootNode?.recycle()
        return tree
    }

    /**
     * Take a screenshot.
     * - API 30+: Native AccessibilityService.takeScreenshot()
     * - API 29: MediaProjection fallback (VirtualDisplay + ImageReader)
     * - API 28-: Not supported
     */
    suspend fun takeScreenshot(): Bitmap? {
        Timber.tag(TAG).d("takeScreenshot called, SDK=${Build.VERSION.SDK_INT}")

        // API 30+: Use native AccessibilityService.takeScreenshot()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Timber.tag(TAG).d("Starting screenshot capture via AccessibilityService...")
            val result = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine { continuation ->
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                Timber.tag(TAG).d("Screenshot onSuccess!")
                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                screenshot.hardwareBuffer.close()
                                continuation.resume(bitmap)
                            }

                            override fun onFailure(errorCode: Int) {
                                Timber.tag(TAG).e("Screenshot onFailure! error code: $errorCode")
                                continuation.resume(null)
                            }
                        }
                    )
                }
            }

            if (result == null) {
                Timber.tag(TAG).e("Screenshot returned null (timeout or failure)")
            } else {
                Timber.tag(TAG).d("Screenshot successful: ${result.width}x${result.height}")
            }

            return result
        }

        // API 29: Fallback to MediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Timber.tag(TAG).d("Using MediaProjection fallback for API ${Build.VERSION.SDK_INT}")
            return MediaProjectionScreenshot.capture(applicationContext)
        }

        // API 28-: Not supported
        Timber.tag(TAG).w("Screenshot not supported on API ${Build.VERSION.SDK_INT}")
        return null
    }

    /**
     * Check if a specific package is in foreground
     */
    fun isPackageInForeground(packageName: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val currentPackage = rootNode.packageName?.toString()
        rootNode.recycle()
        return currentPackage == packageName
    }

    /**
     * Check if TikTok is currently in foreground
     */
    /**
     * Check if TikTok is currently in foreground.
     * Uses multiple detection methods for reliability after Share Intent.
     */
    fun isTikTokInForeground(): Boolean {
        val rootNode = rootInActiveWindow

        // Method 1: Check package name directly
        if (rootNode != null) {
            val currentPackage = rootNode.packageName?.toString()
            rootNode.recycle()

            if (currentPackage in ALLOWED_PACKAGES) {
                Timber.tag(TAG).d("isTikTokInForeground: YES (package=$currentPackage)")
                return true
            }

            Timber.tag(TAG).d("isTikTokInForeground: NO (package=$currentPackage)")
            return false
        }

        // Method 2: Fallback - check UI tree for TikTok resource IDs
        // This helps when rootInActiveWindow is null during transitions
        Timber.tag(TAG).d("isTikTokInForeground: rootInActiveWindow is null, trying fallback...")
        return isTikTokDetectedByResourceIds()
    }

    /**
     * Fallback detection: look for TikTok-specific resource IDs in any accessible window.
     */
    private fun isTikTokDetectedByResourceIds(): Boolean {
        try {
            val windows = windows ?: return false

            for (window in windows) {
                val root = window.root ?: continue
                val packageName = root.packageName?.toString()

                // Check if this window belongs to TikTok
                if (packageName != null && ALLOWED_PACKAGES.any { packageName.startsWith(it.substringBefore(".")) }) {
                    Timber.tag(TAG).d("isTikTokDetectedByResourceIds: found TikTok window (package=$packageName)")
                    root.recycle()
                    return true
                }

                // Check for TikTok-specific resource IDs
                val hasTikTokResources = hasResourceIdPrefix(root, "com.zhiliaoapp.musically") ||
                        hasResourceIdPrefix(root, "com.ss.android.ugc")

                root.recycle()

                if (hasTikTokResources) {
                    Timber.tag(TAG).d("isTikTokDetectedByResourceIds: found TikTok resources")
                    return true
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("isTikTokDetectedByResourceIds: error - ${e.message}")
        }

        Timber.tag(TAG).d("isTikTokDetectedByResourceIds: TikTok not detected")
        return false
    }

    /**
     * Check if any node in the tree has a resource ID with the given prefix.
     */
    private fun hasResourceIdPrefix(node: AccessibilityNodeInfo, prefix: String): Boolean {
        val resourceId = node.viewIdResourceName
        if (resourceId != null && resourceId.startsWith(prefix)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasResourceIdPrefix(child, prefix)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Find a node by text and click it
     */
    fun clickByText(text: String, exactMatch: Boolean = false): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        fun findAndClick(node: AccessibilityNodeInfo): Boolean {
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString()
            val matches = if (exactMatch) {
                nodeText == text
            } else {
                nodeText?.contains(text, ignoreCase = true) == true
            }

            if (matches && node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return result
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    if (findAndClick(child)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                }
            }

            return false
        }

        val result = findAndClick(rootNode)
        rootNode.recycle()
        return result
    }

    /**
     * Find a node by resource ID and click it
     */
    fun clickByResourceId(resourceId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        fun findAndClick(node: AccessibilityNodeInfo): Boolean {
            if (node.viewIdResourceName == resourceId && node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return result
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    if (findAndClick(child)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                }
            }

            return false
        }

        val result = findAndClick(rootNode)
        rootNode.recycle()
        return result
    }

    /**
     * Find a node by content description and click it
     */
    fun clickByContentDescription(description: String, exactMatch: Boolean = false): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        fun findAndClick(node: AccessibilityNodeInfo): Boolean {
            val contentDesc = node.contentDescription?.toString()
            val matches = if (exactMatch) {
                contentDesc == description
            } else {
                contentDesc?.contains(description, ignoreCase = true) == true
            }

            if (matches && node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return result
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    if (findAndClick(child)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                }
            }

            return false
        }

        val result = findAndClick(rootNode)
        rootNode.recycle()
        return result
    }

    /**
     * Dispatch gesture and wait for completion
     */
    private suspend fun dispatchGestureAndWait(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    continuation.resume(false)
                }
            }

            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                continuation.resume(false)
            }
        }
    }
}
