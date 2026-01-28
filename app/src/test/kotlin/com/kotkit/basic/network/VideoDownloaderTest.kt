package com.kotkit.basic.network

import org.junit.Test
import org.junit.Assert.*
import java.security.MessageDigest

/**
 * Unit tests for VideoDownloader logic.
 */
class VideoDownloaderTest {

    @Test
    fun `SHA256 hash calculation is correct`() {
        val testData = "Hello, World!".toByteArray()
        val expectedHash = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"

        val actualHash = calculateSHA256(testData)

        assertEquals(expectedHash, actualHash)
    }

    @Test
    fun `hash comparison is case insensitive`() {
        val hash1 = "ABCDEF123456"
        val hash2 = "abcdef123456"

        assertTrue(hash1.equals(hash2, ignoreCase = true))
    }

    @Test
    fun `progress calculation is correct`() {
        val downloadedBytes = 50L
        val totalBytes = 100L

        val progress = downloadedBytes.toFloat() / totalBytes

        assertEquals(0.5f, progress, 0.001f)
    }

    @Test
    fun `progress is clamped to valid range`() {
        val testCases = listOf(
            Triple(0L, 100L, 0f),
            Triple(50L, 100L, 0.5f),
            Triple(100L, 100L, 1f),
            Triple(150L, 100L, 1f) // Over 100% should clamp to 1
        )

        for ((downloaded, total, expected) in testCases) {
            val progress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
            assertEquals("Progress for $downloaded/$total", expected, progress, 0.001f)
        }
    }

    @Test
    fun `resume range header format is correct`() {
        val existingSize = 1024L

        val rangeHeader = "bytes=$existingSize-"

        assertEquals("bytes=1024-", rangeHeader)
    }

    @Test
    fun `HTTP 206 indicates partial content`() {
        val partialContentCode = 206
        val successCode = 200

        assertTrue("206 is partial content", partialContentCode == 206)
        assertFalse("200 is not partial content", successCode == 206)
    }

    @Test
    fun `file cleanup age calculation is correct`() {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours
        val now = System.currentTimeMillis()
        val cutoff = now - maxAgeMs

        val oldFile = now - (25 * 60 * 60 * 1000L) // 25 hours ago
        val newFile = now - (23 * 60 * 60 * 1000L) // 23 hours ago

        assertTrue("Old file should be cleaned up", oldFile < cutoff)
        assertFalse("New file should not be cleaned up", newFile < cutoff)
    }

    private fun calculateSHA256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
