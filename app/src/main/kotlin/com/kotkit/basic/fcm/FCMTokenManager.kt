package com.kotkit.basic.fcm

import timber.log.Timber
import com.google.firebase.messaging.FirebaseMessaging
import com.kotkit.basic.data.repository.WorkerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages FCM token lifecycle:
 * - Initial token retrieval
 * - Token refresh handling
 * - Backend registration with retry
 */
@Singleton
class FCMTokenManager @Inject constructor(
    private val workerRepository: WorkerRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // FIX: Thread-safe token management
    private val tokenLock = Any()
    @Volatile
    private var currentToken: String? = null

    /**
     * Initialize FCM token on app start.
     * Call this when worker mode is activated.
     */
    fun initializeToken() {
        scope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                Timber.tag(TAG).i("FCM token retrieved: ${token.take(20)}...")
                updateToken(token)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to get FCM token")
            }
        }
    }

    /**
     * Update FCM token (called on refresh or initialization).
     * Thread-safe with retry logic.
     */
    fun updateToken(token: String) {
        // FIX: Synchronized block to prevent race condition
        synchronized(tokenLock) {
            if (token == currentToken) {
                Timber.tag(TAG).d("Token unchanged, skipping update")
                return
            }
            currentToken = token
        }

        scope.launch {
            registerWithRetry(token)
        }
    }

    /**
     * Register token with backend using exponential backoff retry.
     */
    private suspend fun registerWithRetry(
        token: String,
        maxRetries: Int = MAX_RETRIES,
        initialDelayMs: Long = INITIAL_RETRY_DELAY_MS
    ) {
        var attempt = 0
        var delayMs = initialDelayMs

        while (attempt < maxRetries) {
            try {
                val success = workerRepository.registerDeviceToken(token)
                if (success) {
                    Timber.tag(TAG).i("✓ FCM token registered with backend (attempt ${attempt + 1})")
                    return
                } else {
                    Timber.tag(TAG).w("Backend rejected token registration (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error registering FCM token (attempt ${attempt + 1})")
            }

            attempt++
            if (attempt < maxRetries) {
                Timber.tag(TAG).d("Retrying in ${delayMs}ms...")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS) // Exponential backoff with cap
            }
        }

        Timber.tag(TAG).e("Failed to register FCM token after $maxRetries attempts")
    }

    companion object {
        private const val TAG = "FCMTokenManager"
        private const val MAX_RETRIES = 5
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
    }
}
