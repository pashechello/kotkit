package com.kotkit.basic.data.remote.api

import android.util.Log
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authenticator that handles 401 responses by refreshing the token.
 * This is called automatically by OkHttp when a 401 response is received.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val encryptedPreferences: EncryptedPreferences,
    private val gson: Gson
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRIES = 2
    }

    @Volatile
    private var isRefreshing = false

    override fun authenticate(route: Route?, response: Response): Request? {
        // Don't retry if we've already tried multiple times
        val retryCount = response.request.header("Retry-Count")?.toIntOrNull() ?: 0
        if (retryCount >= MAX_RETRIES) {
            Log.w(TAG, "Max retries reached, giving up")
            return null
        }

        // Don't try to refresh if there's no refresh token
        val refreshToken = encryptedPreferences.refreshToken
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No refresh token available")
            clearAuthAndReturn()
            return null
        }

        // Synchronize to prevent multiple simultaneous refresh attempts
        synchronized(this) {
            // Check if token was already refreshed by another request
            val currentToken = encryptedPreferences.authToken
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != requestToken) {
                // Token was refreshed by another request, retry with new token
                Log.i(TAG, "Token was refreshed by another request, retrying")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .header("Retry-Count", (retryCount + 1).toString())
                    .build()
            }

            if (isRefreshing) {
                // Another thread is refreshing, wait and retry
                return null
            }

            isRefreshing = true
        }

        try {
            val newToken = refreshTokenSync(refreshToken)

            return if (newToken != null) {
                Log.i(TAG, "Token refreshed successfully")
                encryptedPreferences.authToken = newToken

                response.request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .header("Retry-Count", (retryCount + 1).toString())
                    .build()
            } else {
                Log.w(TAG, "Token refresh failed")
                clearAuthAndReturn()
                null
            }
        } finally {
            synchronized(this) {
                isRefreshing = false
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

        val baseUrl = encryptedPreferences.serverUrl ?: "https://kotkit.pro/"
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
            Log.e(TAG, "Error refreshing token", e)
            null
        }
    }

    private fun clearAuthAndReturn() {
        encryptedPreferences.clearAuth()
    }
}
