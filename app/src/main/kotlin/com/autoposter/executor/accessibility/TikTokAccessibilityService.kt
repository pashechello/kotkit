package com.autoposter.executor.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoposter.executor.accessibility.portal.UITree
import com.autoposter.executor.accessibility.portal.UITreeParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class TikTokAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TikTokA11yService"

        // CRITICAL: Only these packages are allowed!
        private val ALLOWED_PACKAGES = setOf(
            "com.zhiliaoapp.musically",    // TikTok
            "com.ss.android.ugc.trill"     // TikTok Lite
        )

        @Volatile
        private var instance: TikTokAccessibilityService? = null

        fun getInstance(): TikTokAccessibilityService? = instance

        fun isServiceEnabled(): Boolean = instance != null
    }

    private val uiTreeParser = UITreeParser()

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

        Log.i(TAG, "Accessibility Service connected - restricted to TikTok only")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Additional runtime check for security
        val packageName = event?.packageName?.toString()
        if (packageName != null && packageName !in ALLOWED_PACKAGES) {
            Log.w(TAG, "Blocked event from non-TikTok package: $packageName")
            return
        }

        // Events can be processed here if needed for reactive automation
        // Currently we use polling approach from PostingAgent
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service destroyed")
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
            Log.w(TAG, "No focused input field found")
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
            Log.e(TAG, "Failed to type text", e)
            false
        }
    }

    /**
     * Append text to the current input (doesn't clear existing text)
     */
    fun appendText(text: String): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            Log.w(TAG, "No focused input field found")
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
            Log.e(TAG, "Failed to append text", e)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot requires API 30+")
            return null
        }

        return withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { continuation ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            screenshot.hardwareBuffer.close()
                            continuation.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with error code: $errorCode")
                            continuation.resume(null)
                        }
                    }
                )
            }
        }
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
    fun isTikTokInForeground(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val currentPackage = rootNode.packageName?.toString()
        rootNode.recycle()
        return currentPackage in ALLOWED_PACKAGES
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
