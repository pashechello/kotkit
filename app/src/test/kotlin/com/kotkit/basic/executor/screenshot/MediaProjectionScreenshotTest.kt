package com.kotkit.basic.executor.screenshot

import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MediaProjectionScreenshotTest {

    @Before
    fun setUp() {
        MediaProjectionTokenHolder.clear()
        MediaProjectionScreenshot.release()
    }

    @After
    fun tearDown() {
        MediaProjectionScreenshot.release()
        MediaProjectionTokenHolder.clear()
    }

    @Test
    fun `isAvailable is false initially`() {
        assertFalse(MediaProjectionScreenshot.isAvailable)
    }

    @Test
    fun `initialize returns false without token`() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(MediaProjectionScreenshot.initialize(context))
        assertFalse(MediaProjectionScreenshot.isAvailable)
    }

    @Test
    fun `initialize returns false with resultCode 0`() {
        val context = RuntimeEnvironment.getApplication()
        MediaProjectionTokenHolder.store(0, Intent("test"))
        // hasToken is false when resultCode is 0
        assertFalse(MediaProjectionScreenshot.initialize(context))
    }

    @Test
    fun `release is idempotent`() {
        // Should not crash when called multiple times
        MediaProjectionScreenshot.release()
        MediaProjectionScreenshot.release()
        MediaProjectionScreenshot.release()
        assertFalse(MediaProjectionScreenshot.isAvailable)
    }

    @Test
    fun `release clears isAvailable`() {
        // Even if somehow set, release should clear it
        MediaProjectionScreenshot.release()
        assertFalse(MediaProjectionScreenshot.isAvailable)
    }
}
