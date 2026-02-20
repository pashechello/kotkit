package com.kotkit.basic.data.repository

import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.LoginRequest
import com.kotkit.basic.data.remote.api.models.RefreshTokenRequest
import com.kotkit.basic.data.remote.api.models.RegisterRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val encryptedPreferences: EncryptedPreferences
) {
    fun isLoggedIn(): Boolean = encryptedPreferences.isLoggedIn()

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            val response = apiService.login(
                LoginRequest(
                    email = email,
                    password = password
                )
            )

            if (response.success && response.accessToken != null) {
                encryptedPreferences.authToken = response.accessToken
                encryptedPreferences.refreshToken = response.refreshToken
                encryptedPreferences.userId = response.userId
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.error ?: "Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(email: String, password: String, username: String): Result<Unit> {
        return try {
            val response = apiService.register(
                RegisterRequest(
                    email = email,
                    password = password,
                    name = username
                )
            )

            if (response.success && response.accessToken != null) {
                encryptedPreferences.authToken = response.accessToken
                encryptedPreferences.refreshToken = response.refreshToken
                encryptedPreferences.userId = response.userId
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.error ?: "Registration failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshToken(): Result<Unit> {
        val currentRefreshToken = encryptedPreferences.refreshToken
            ?: return Result.failure(Exception("No refresh token"))

        return try {
            val response = apiService.refreshToken(
                RefreshTokenRequest(currentRefreshToken)
            )

            if (response.success && response.accessToken != null) {
                encryptedPreferences.authToken = response.accessToken
                response.refreshToken?.let { encryptedPreferences.refreshToken = it }
                Result.success(Unit)
            } else {
                // Refresh failed - clear auth
                logout()
                Result.failure(Exception(response.error ?: "Token refresh failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        encryptedPreferences.clearAuth()
    }

    fun getUserId(): String? = encryptedPreferences.userId

    /**
     * Get cached user email (stored locally).
     */
    fun getCachedEmail(): String? = encryptedPreferences.userEmail

    /**
     * Get user profile (email, name) from server.
     * Also caches email locally for offline access.
     */
    suspend fun getProfile(): Result<UserProfile> {
        return try {
            val response = apiService.getProfile()
            // Cache email locally
            encryptedPreferences.userEmail = response.email
            Result.success(UserProfile(
                email = response.email,
                name = response.name
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class UserProfile(
    val email: String,
    val name: String?
)
