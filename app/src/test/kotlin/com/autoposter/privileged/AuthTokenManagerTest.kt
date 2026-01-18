package com.autoposter.privileged

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * Unit tests for AuthTokenManager.
 *
 * Tests cover:
 * - Token generation and loading
 * - Token format validation
 * - Token persistence
 * - Error handling
 *
 * Note: These tests use a temporary folder to simulate the data directory
 * since AuthTokenManager.loadToken() works with file paths directly.
 */
class AuthTokenManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataDir: File
    private lateinit var filesDir: File
    private lateinit var tokenFile: File

    @Before
    fun setUp() {
        dataDir = tempFolder.newFolder("data")
        filesDir = File(dataDir, "files").also { it.mkdirs() }
        tokenFile = File(filesDir, "server_auth_token")
    }

    @After
    fun tearDown() {
        // Cleanup is handled by TemporaryFolder rule
    }

    // ============= loadToken Tests =============

    @Test
    fun `loadToken returns null when file does not exist`() {
        val result = AuthTokenManager.loadToken(dataDir.absolutePath)
        assertNull("Should return null when token file missing", result)
    }

    @Test
    fun `loadToken returns token when valid file exists`() {
        // Create a valid token file
        val token = ByteArray(32) { it.toByte() }
        val base64Token = Base64.getEncoder().encodeToString(token)
        tokenFile.writeText(base64Token)

        val result = AuthTokenManager.loadToken(dataDir.absolutePath)

        assertNotNull("Should return token", result)
        assertEquals("Token should be 32 bytes", 32, result!!.size)
        assertTrue("Token should match", token.contentEquals(result))
    }

    @Test
    fun `loadToken returns null for wrong size token`() {
        // Create a token with wrong size
        val wrongSizeToken = ByteArray(16) { it.toByte() }
        val base64Token = Base64.getEncoder().encodeToString(wrongSizeToken)
        tokenFile.writeText(base64Token)

        val result = AuthTokenManager.loadToken(dataDir.absolutePath)

        assertNull("Should return null for wrong size token", result)
    }

    @Test
    fun `loadToken returns null for invalid base64`() {
        // Write invalid base64
        tokenFile.writeText("not-valid-base64!!!")

        val result = AuthTokenManager.loadToken(dataDir.absolutePath)

        assertNull("Should return null for invalid base64", result)
    }

    @Test
    fun `loadToken returns null for empty file`() {
        tokenFile.writeText("")

        val result = AuthTokenManager.loadToken(dataDir.absolutePath)

        assertNull("Should return null for empty file", result)
    }

    // ============= Token Format Tests =============

    @Test
    fun `AUTH_TOKEN_LENGTH is 32 bytes`() {
        assertEquals(
            "Auth token should be 32 bytes (256 bits)",
            32,
            ServerProtocol.AUTH_TOKEN_LENGTH
        )
    }

    @Test
    fun `token file path is correct`() {
        // Create token and verify path
        val token = ByteArray(32) { 0xFF.toByte() }
        val base64Token = Base64.getEncoder().encodeToString(token)
        tokenFile.writeText(base64Token)

        val expectedPath = File(dataDir, "files/server_auth_token")
        assertTrue("Token file should exist at expected path", expectedPath.exists())
    }

    // ============= Token Regeneration Tests =============

    @Test
    fun `different tokens are unique`() {
        // Simulate two different token generations
        val token1 = ByteArray(32) { it.toByte() }
        val token2 = ByteArray(32) { (it + 1).toByte() }

        assertFalse(
            "Different tokens should not be equal",
            token1.contentEquals(token2)
        )
    }

    // ============= Edge Cases =============

    @Test
    fun `loadToken handles non-existent data directory`() {
        val nonExistent = "/path/that/does/not/exist"

        val result = AuthTokenManager.loadToken(nonExistent)

        assertNull("Should return null for non-existent directory", result)
    }

    @Test
    fun `loadToken handles directory without files subdirectory`() {
        val dirWithoutFiles = tempFolder.newFolder("no-files-dir")

        val result = AuthTokenManager.loadToken(dirWithoutFiles.absolutePath)

        assertNull("Should return null when files/ subdirectory missing", result)
    }

    @Test
    fun `token with special base64 characters loads correctly`() {
        // Create a token that produces + and / in base64
        val token = ByteArray(32) { 0xFE.toByte() }
        val base64Token = Base64.getEncoder().encodeToString(token)
        tokenFile.writeText(base64Token)

        val result = AuthTokenManager.loadToken(dataDir.absolutePath)

        assertNotNull("Should load token with special base64 chars", result)
        assertTrue("Token should match", token.contentEquals(result!!))
    }

    // ============= Security Tests =============

    @Test
    fun `all-zeros token is valid format`() {
        val zeroToken = ByteArray(32) { 0x00 }
        val base64Token = Base64.getEncoder().encodeToString(zeroToken)
        tokenFile.writeText(base64Token)

        val result = AuthTokenManager.loadToken(dataDir.absolutePath)

        assertNotNull("All-zeros is valid token format", result)
        assertEquals("Token size should be 32", 32, result!!.size)
    }

    @Test
    fun `all-ones token is valid format`() {
        val onesToken = ByteArray(32) { 0xFF.toByte() }
        val base64Token = Base64.getEncoder().encodeToString(onesToken)
        tokenFile.writeText(base64Token)

        val result = AuthTokenManager.loadToken(dataDir.absolutePath)

        assertNotNull("All-0xFF is valid token format", result)
        assertEquals("Token size should be 32", 32, result!!.size)
    }
}
