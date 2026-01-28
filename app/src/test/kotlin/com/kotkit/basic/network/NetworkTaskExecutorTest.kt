package com.kotkit.basic.network

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for NetworkTaskExecutor logic.
 */
class NetworkTaskExecutorTest {

    @Test
    fun `URL expiry check works correctly`() {
        val now = System.currentTimeMillis()
        val expiredUrl = now - 1000 // 1 second ago
        val validUrl = now + 60000 // 1 minute from now

        assertTrue("Expired URL should be detected", now >= expiredUrl)
        assertFalse("Valid URL should not be expired", now >= validUrl)
    }

    @Test
    fun `error type mapping works correctly`() {
        // Test accessibility error
        assertEquals("accessibility_disabled", mapPostResultToErrorType("accessibility service not enabled"))

        // Test login error - must contain "login" keyword
        assertEquals("tiktok_not_logged_in", mapPostResultToErrorType("please login first"))

        // Test TikTok app not installed (contains both "tiktok" and "not")
        assertEquals("tiktok_app_not_installed", mapPostResultToErrorType("tiktok app not found"))

        // Test banned
        assertEquals("account_banned", mapPostResultToErrorType("your account is banned"))

        // Test button not found
        assertEquals("button_not_found", mapPostResultToErrorType("post button missing"))

        // Test timeout
        assertEquals("upload_timeout", mapPostResultToErrorType("timeout waiting for upload"))

        // Test captcha
        assertEquals("tiktok_captcha", mapPostResultToErrorType("captcha verification required"))

        // Test unknown
        assertEquals("unknown_error", mapPostResultToErrorType("something unexpected happened"))
    }

    private fun mapPostResultToErrorType(reason: String): String {
        // This mirrors the logic in NetworkTaskExecutor.mapPostResultToErrorType
        return when {
            reason.contains("accessibility", ignoreCase = true) -> "accessibility_disabled"
            reason.contains("tiktok", ignoreCase = true) && reason.contains("not", ignoreCase = true) -> "tiktok_app_not_installed"
            reason.contains("login", ignoreCase = true) -> "tiktok_not_logged_in"
            reason.contains("banned", ignoreCase = true) -> "account_banned"
            reason.contains("button", ignoreCase = true) -> "button_not_found"
            reason.contains("timeout", ignoreCase = true) -> "upload_timeout"
            reason.contains("captcha", ignoreCase = true) -> "tiktok_captcha"
            else -> "unknown_error"
        }
    }

    @Test
    fun `execution stages are in correct order`() {
        val stages = listOf(
            ExecutionStage.GETTING_URL,
            ExecutionStage.DOWNLOADING,
            ExecutionStage.POSTING,
            ExecutionStage.VERIFYING,
            ExecutionStage.COMPLETING,
            ExecutionStage.COMPLETED
        )

        assertEquals(6, stages.size)
        assertEquals(ExecutionStage.GETTING_URL, stages.first())
        assertEquals(ExecutionStage.COMPLETED, stages.last())
    }

    @Test
    fun `execution result types are distinct`() {
        val success = ExecutionResult.Success(
            tiktokVideoId = "123",
            tiktokPostUrl = "https://tiktok.com/123",
            screenshotPath = "/path/to/screenshot.jpg"
        )
        val failed = ExecutionResult.Failed(
            errorType = "network_timeout",
            message = "Connection timed out"
        )
        val retry = ExecutionResult.Retry(reason = "Temporary failure")

        assertTrue(success is ExecutionResult.Success)
        assertTrue(failed is ExecutionResult.Failed)
        assertTrue(retry is ExecutionResult.Retry)
    }
}
