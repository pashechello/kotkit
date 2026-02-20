package com.kotkit.basic.executor.screenshot

import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MediaProjectionTokenHolderTest {

    @After
    fun tearDown() {
        MediaProjectionTokenHolder.clear()
    }

    @Test
    fun `initially has no token`() {
        assertFalse(MediaProjectionTokenHolder.hasToken)
        assertEquals(0, MediaProjectionTokenHolder.resultCode)
        assertNull(MediaProjectionTokenHolder.data)
    }

    @Test
    fun `store saves resultCode and data`() {
        val intent = Intent("test.action")
        MediaProjectionTokenHolder.store(-1, intent)

        assertTrue(MediaProjectionTokenHolder.hasToken)
        assertEquals(-1, MediaProjectionTokenHolder.resultCode)
        assertNotNull(MediaProjectionTokenHolder.data)
    }

    @Test
    fun `clear resets all fields`() {
        val intent = Intent("test.action")
        MediaProjectionTokenHolder.store(-1, intent)
        assertTrue(MediaProjectionTokenHolder.hasToken)

        MediaProjectionTokenHolder.clear()

        assertFalse(MediaProjectionTokenHolder.hasToken)
        assertEquals(0, MediaProjectionTokenHolder.resultCode)
        assertNull(MediaProjectionTokenHolder.data)
    }

    @Test
    fun `store makes defensive copy of intent`() {
        val intent = Intent("test.action")
        intent.putExtra("key", "value1")
        MediaProjectionTokenHolder.store(-1, intent)

        // Mutate original — stored copy should be unaffected
        intent.putExtra("key", "value2")

        val stored = MediaProjectionTokenHolder.data!!
        assertEquals("value1", stored.getStringExtra("key"))
    }

    @Test
    fun `hasToken is false with resultCode 0`() {
        // resultCode 0 = Activity.RESULT_CANCELED on some paths
        val intent = Intent("test.action")
        MediaProjectionTokenHolder.store(0, intent)

        // resultCode 0 means invalid/cancelled
        assertFalse(MediaProjectionTokenHolder.hasToken)
    }

    @Test
    fun `double clear does not crash`() {
        MediaProjectionTokenHolder.clear()
        MediaProjectionTokenHolder.clear()
        assertFalse(MediaProjectionTokenHolder.hasToken)
    }
}
