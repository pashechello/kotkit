package com.autoposter.privileged

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * AuthTokenManager generates and manages the authentication token for LocalSocket IPC.
 *
 * ## Security Model
 *
 * The token protects the Privileged Server from unauthorized access by other apps.
 * This prevents malicious apps from hijacking the input injection capability.
 *
 * ### Token Storage
 * - Token is a cryptographically random 256-bit value (SecureRandom)
 * - Stored in app's private files directory (`context.filesDir`)
 * - File permissions are mode 600 (owner read/write only)
 * - On Android, `filesDir` is protected by SELinux and Unix permissions
 *
 * ### Design Decisions
 *
 * **Why not Android Keystore encryption?**
 * The Privileged Server runs as shell user (UID 2000) via `app_process`, not as
 * the app's UID. Android Keystore is per-app and cannot be accessed by other UIDs.
 * If we encrypted the token with Keystore, the server couldn't decrypt it.
 *
 * **Why is this acceptable?**
 * 1. The threat model is protecting against other apps, not root/shell access
 * 2. An attacker with shell access can already inject input directly
 * 3. The token file is in the app's private directory, protected by SELinux
 * 4. The server requires `--data-dir` argument, failing closed without it
 *
 * ### Threat Analysis
 * - **Other apps**: Cannot read the token (app private directory)
 * - **Root access**: Can read anything; token is not the attack surface
 * - **Physical access**: Device encryption protects at-rest data
 * - **ADB access**: If attacker has ADB, they already have shell privileges
 *
 * ### Recommendations
 * - Regenerate token periodically or when pairing is re-done
 * - The app should stop the server when not in use
 * - Consider implementing token rotation in future versions
 */
object AuthTokenManager {

    private const val TAG = "AuthTokenManager"
    private const val TOKEN_FILE = "server_auth_token"

    private val secureRandom = SecureRandom()

    /**
     * Get or create authentication token.
     * Called by the app before connecting to the server.
     */
    fun getOrCreateToken(context: Context): ByteArray {
        val tokenFile = getTokenFile(context)

        // Try to load existing token
        if (tokenFile.exists()) {
            try {
                val tokenBase64 = tokenFile.readText()
                val token = Base64.decode(tokenBase64, Base64.DEFAULT)
                if (token.size == ServerProtocol.AUTH_TOKEN_LENGTH) {
                    return token
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load token, generating new one", e)
            }
        }

        // Generate new token
        val token = ByteArray(ServerProtocol.AUTH_TOKEN_LENGTH)
        secureRandom.nextBytes(token)

        // Save token
        try {
            tokenFile.writeText(Base64.encodeToString(token, Base64.NO_WRAP))
            Log.i(TAG, "Generated new auth token")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save token", e)
        }

        return token
    }

    /**
     * Load token from file.
     * Called by the server to verify client tokens.
     *
     * @param dataDir The app's data directory path (passed as argument to server)
     */
    fun loadToken(dataDir: String): ByteArray? {
        val tokenFile = File(dataDir, "files/$TOKEN_FILE")

        if (!tokenFile.exists()) {
            println("[$TAG] Token file not found: ${tokenFile.absolutePath}")
            return null
        }

        return try {
            val tokenBase64 = tokenFile.readText()
            val token = Base64.decode(tokenBase64, Base64.DEFAULT)
            if (token.size == ServerProtocol.AUTH_TOKEN_LENGTH) {
                println("[$TAG] Loaded auth token from ${tokenFile.absolutePath}")
                token
            } else {
                println("[$TAG] Invalid token size: ${token.size}")
                null
            }
        } catch (e: Exception) {
            println("[$TAG] Failed to load token: ${e.message}")
            null
        }
    }

    /**
     * Delete stored token.
     */
    fun deleteToken(context: Context) {
        val tokenFile = getTokenFile(context)
        if (tokenFile.exists()) {
            tokenFile.delete()
            Log.i(TAG, "Deleted auth token")
        }
    }

    /**
     * Regenerate token (invalidates existing connections).
     */
    fun regenerateToken(context: Context): ByteArray {
        deleteToken(context)
        return getOrCreateToken(context)
    }

    private fun getTokenFile(context: Context): File {
        return File(context.filesDir, TOKEN_FILE)
    }
}
