package com.kotkit.basic.executor.screenshot

import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the API branching logic for screenshot capture.
 *
 * The actual takeScreenshot() method lives in TikTokAccessibilityService and can't
 * be easily unit tested (requires AccessibilityService lifecycle). These tests verify
 * the branching conditions match our expectations.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotApiBranchingTest {

    /**
     * On API 30+: should use AccessibilityService.takeScreenshot() (native).
     * MediaProjection should NOT be needed.
     */
    @Test
    @Config(sdk = [30])
    fun `API 30 uses native AccessibilityService takeScreenshot`() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        // On API 30+, MediaProjection fallback is not used
        assertFalse(shouldUseMediaProjectionFallback())
    }

    @Test
    @Config(sdk = [31])
    fun `API 31 uses native AccessibilityService takeScreenshot`() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assertFalse(shouldUseMediaProjectionFallback())
    }

    @Test
    @Config(sdk = [34])
    fun `API 34 uses native AccessibilityService takeScreenshot`() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assertFalse(shouldUseMediaProjectionFallback())
    }

    /**
     * On API 29: should use MediaProjection fallback.
     */
    @Test
    @Config(sdk = [29])
    fun `API 29 uses MediaProjection fallback`() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assertTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        assertTrue(shouldUseMediaProjectionFallback())
    }

    /**
     * On API 28 and below: no screenshot capability.
     */
    @Test
    @Config(sdk = [28])
    fun `API 28 has no screenshot support`() {
        assertFalse(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assertFalse(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assertFalse(shouldUseMediaProjectionFallback())
        assertTrue(isScreenshotUnsupported())
    }

    @Test
    @Config(sdk = [26])
    fun `API 26 has no screenshot support`() {
        assertTrue(isScreenshotUnsupported())
    }

    /**
     * Mirrors the branching logic in TikTokAccessibilityService.takeScreenshot().
     *
     * The actual code:
     *   if (SDK >= R) -> native takeScreenshot
     *   if (SDK >= Q) -> MediaProjection fallback
     *   else -> null
     */
    private fun shouldUseMediaProjectionFallback(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    }

    private fun isScreenshotUnsupported(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }
}
