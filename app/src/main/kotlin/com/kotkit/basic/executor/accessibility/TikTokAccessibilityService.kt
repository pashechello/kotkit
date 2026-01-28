package com.kotkit.basic.executor.accessibility

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
import com.kotkit.basic.status.FloatingLogoService
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

        // Test actions
        const val ACTION_TEST_TAP = "com.kotkit.basic.TEST_TAP"
        const val ACTION_TEST_SCREENSHOT = "com.kotkit.basic.TEST_SCREENSHOT"
        const val ACTION_TEST_UI_TREE = "com.kotkit.basic.TEST_UI_TREE"
        const val ACTION_TEST_PIN = "com.kotkit.basic.TEST_PIN"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_PIN = "pin"

        // CRITICAL: Only these packages are allowed!
        private val ALLOWED_PACKAGES = setOf(
            "com.zhiliaoapp.musically",      // TikTok (основной)
            "com.ss.android.ugc.trill",      // TikTok Lite
            "com.ss.android.ugc.aweme",      // TikTok (китайский/старый)
            "com.zhiliaoapp.musically.go",   // TikTok Go (лайт для развивающихся рынков)
            "musical.ly"                      // Старый Musical.ly (legacy)
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 100
            // CRITICAL: Restrict to TikTok packages only
            packageNames = ALLOWED_PACKAGES.toTypedArray()
        }
        serviceInfo = info

        // Register test tap receiver
        registerTestReceiver()

        // Start floating logo indicator
        FloatingLogoService.start(this)

        Timber.tag(TAG).i("Accessibility Service connected - restricted to TikTok only")
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
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_TEST_TAP)
            addAction(ACTION_TEST_SCREENSHOT)
            addAction(ACTION_TEST_UI_TREE)
            addAction(ACTION_TEST_PIN)
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
        val pin = intent.getStringExtra(EXTRA_PIN) ?: "5452"
        Timber.tag(TAG).i("TEST_PIN received: pin=$pin")

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

            // 3. Get PIN pad coordinates from UI tree
            val pinPadCoords = getPinPadCoordinates()
            if (pinPadCoords == null) {
                Timber.tag(TAG).e("Failed to get PIN pad coordinates!")
                return@launch
            }
            Timber.tag(TAG).i("PIN pad coordinates: $pinPadCoords")

            // 4. Enter PIN
            for (digit in pin) {
                val coords = pinPadCoords[digit]
                if (coords == null) {
                    Timber.tag(TAG).e("No coordinates for digit: $digit")
                    continue
                }
                Timber.tag(TAG).i("Tapping digit $digit at (${coords.first}, ${coords.second})")
                tap(coords.first, coords.second)
                kotlinx.coroutines.delay(150)
            }

            Timber.tag(TAG).i("PIN entry complete!")
        }
    }

    /**
     * Enter PIN code on lockscreen using UI tree coordinates.
     * This is the public method to be called from ScreenUnlocker.
     *
     * @param pin The PIN code to enter (digits only)
     * @return true if PIN was entered successfully
     */
    suspend fun enterPin(pin: String): Boolean {
        Timber.tag(TAG).i("enterPin: starting PIN entry")

        // 1. Wake screen
        wakeScreen()
        kotlinx.coroutines.delay(500)

        // 2. Swipe up to show PIN entry
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val swipeX = screenWidth / 2
        val swipeStartY = (screenHeight * 0.9f).toInt()
        val swipeEndY = (screenHeight * 0.4f).toInt()
        Timber.tag(TAG).i("enterPin: swiping up to show PIN pad")
        swipe(swipeX, swipeStartY, swipeX, swipeEndY, 300)
        kotlinx.coroutines.delay(1500)

        // 3. Get PIN pad coordinates from UI tree
        val pinPadCoords = getPinPadCoordinates()
        if (pinPadCoords == null) {
            Timber.tag(TAG).e("enterPin: failed to get PIN pad coordinates")
            return false
        }
        Timber.tag(TAG).i("enterPin: got coordinates for ${pinPadCoords.size} digits")

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

        Timber.tag(TAG).i("enterPin: PIN entry complete")
        return true
    }

    /**
     * Get PIN pad button coordinates from UI tree.
     * Returns map of digit char to (x, y) center coordinates.
     */
    private fun getPinPadCoordinates(): Map<Char, Pair<Int, Int>>? {
        val root = rootInActiveWindow
        if (root == null) {
            Timber.tag(TAG).e("getPinPadCoordinates: rootInActiveWindow is null")
            return null
        }
        Timber.tag(TAG).i("getPinPadCoordinates: root=${root.packageName}")
        val coords = mutableMapOf<Char, Pair<Int, Int>>()

        fun findPinButtons(node: AccessibilityNodeInfo) {
            val desc = node.contentDescription?.toString()
            // PIN buttons have contentDescription = "0", "1", ..., "9"
            if (desc != null && desc.length == 1 && desc[0].isDigit()) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val centerX = (bounds.left + bounds.right) / 2
                val centerY = (bounds.top + bounds.bottom) / 2
                coords[desc[0]] = Pair(centerX, centerY)
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
        root.recycle()

        Timber.tag(TAG).i("getPinPadCoordinates: found ${coords.size} digits: ${coords.keys}")
        return if (coords.size >= 10) coords else null
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Additional runtime check for security
        val packageName = event?.packageName?.toString()
        if (packageName != null && packageName !in ALLOWED_PACKAGES) {
            Timber.tag(TAG).w("Blocked event from non-TikTok package: $packageName")
            return
        }

        // Events can be processed here if needed for reactive automation
        // Currently we use polling approach from PostingAgent
    }

    override fun onInterrupt() {
        Timber.tag(TAG).w("Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        testReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        // Stop floating logo indicator
        FloatingLogoService.stop(this)
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
     * Take a screenshot (requires API 30+)
     */
    suspend fun takeScreenshot(): Bitmap? {
        Timber.tag(TAG).d("takeScreenshot called, SDK=${Build.VERSION.SDK_INT}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.tag(TAG).w("Screenshot requires API 30+")
            return null
        }

        Timber.tag(TAG).d("Starting screenshot capture...")
        val result = withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { continuation ->
                Timber.tag(TAG).d("Calling takeScreenshot API...")
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
