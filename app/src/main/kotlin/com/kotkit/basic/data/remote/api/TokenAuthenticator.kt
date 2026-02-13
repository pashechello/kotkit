package com.kotkit.basic.data.remote.api

import com.kotkit.basic.auth.AuthStateManager
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.data.remote.api.models.RefreshTokenRequest
import com.google.gson.Gson
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Authenticator that handles 401 responses by refreshing the token.
 * This is called automatically by OkHttp when a 401 response is received.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val encryptedPreferences: EncryptedPreferences,
    private val gson: Gson,
    // Use Provider to avoid circular dependency during DI setup
    private val authStateManagerProvider: Provider<AuthStateManager>
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRIES = 2
    }

    @Volatile
    private var isRefreshing = false

    // Lock object for waiting on refresh completion
    private val refreshLock = Object()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Don't retry if we've already tried multiple times
        val retryCount = response.request.header("Retry-Count")?.toIntOrNull() ?: 0
        if (retryCount >= MAX_RETRIES) {
            Timber.tag(TAG).w( "Max retries reached, giving up")
            return null
        }

        // Don't try to refresh if there's no refresh token
        val refreshToken = encryptedPreferences.refreshToken
        if (refreshToken.isNullOrBlank()) {
            Timber.tag(TAG).w( "No refresh token available")
            clearAuthAndReturn()
            return null
        }

        // Synchronize to prevent multiple simultaneous refresh attempts
        synchronized(refreshLock) {
            // Check if token was already refreshed by another request
            val currentToken = encryptedPreferences.authToken
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != requestToken) {
                // Token was refreshed by another request, retry with new token
                Timber.tag(TAG).i( "Token was refreshed by another request, retrying")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .header("Retry-Count", (retryCount + 1).toString())
                    .build()
            }

            if (isRefreshing) {
                // Another thread is refreshing, wait for it to complete
                Timber.tag(TAG).d("Waiting for token refresh by another thread")
                try {
                    refreshLock.wait(30_000) // Wait up to 30 seconds
                } catch (e: InterruptedException) {
                    Timber.tag(TAG).w("Interrupted while waiting for token refresh")
                    return null
                }

                // After waiting, check if we now have a new token
                val newToken = encryptedPreferences.authToken
                if (newToken != null && newToken != requestToken) {
                    Timber.tag(TAG).i("Got new token after waiting, retrying")
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .header("Retry-Count", (retryCount + 1).toString())
                        .build()
                }
                // If still no new token, give up
                Timber.tag(TAG).w("No new token after waiting")
                return null
            }

            isRefreshing = true
        }

        try {
            val newToken = refreshTokenSync(refreshToken)

            return if (newToken != null) {
                Timber.tag(TAG).i( "Token refreshed successfully")
                encryptedPreferences.authToken = newToken

                response.request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .header("Retry-Count", (retryCount + 1).toString())
                    .build()
            } else {
                Timber.tag(TAG).w( "Token refresh failed")
                clearAuthAndReturn()
                null
            }
        } finally {
            synchronized(refreshLock) {
                isRefreshing = false
                refreshLock.notifyAll() // Wake up all waiting threads
            }
        }
    }

    /**
     * Synchronously refresh the token.
     * We use a separate OkHttpClient to avoid circular dependency.
     */
    private fun refreshTokenSync(refreshToken: String): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val requestBody = gson.toJson(RefreshTokenRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())

        val baseUrl = encryptedPreferences.serverUrl ?: "https://kotkit-app.fly.dev/"
        val request = Request.Builder()
            .url("${baseUrl}api/v1/auth/refresh")
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val authResponse = gson.fromJson(responseBody, com.kotkit.basic.data.remote.api.models.AuthResponse::class.java)

                if (authResponse.success && authResponse.accessToken != null) {
                    // Also update refresh token if provided
                    authResponse.refreshToken?.let {
                        encryptedPreferences.refreshToken = it
                    }
                    authResponse.accessToken
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error refreshing token")
            null
        }
    }

    private fun clearAuthAndReturn() {
        Timber.tag(TAG).w("Clearing auth and notifying AuthStateManager")
        encryptedPreferences.clearAuth()
        // Notify AuthStateManager about token expiration
        try {
            authStateManagerProvider.get().onTokenExpired()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to notify AuthStateManager")
        }
    }
}
