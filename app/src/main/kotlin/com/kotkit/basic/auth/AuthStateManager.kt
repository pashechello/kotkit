package com.kotkit.basic.auth

import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.data.remote.api.CorrelationIdInterceptor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authentication state for the app
 */
sealed class AuthState {
    object Authenticated : AuthState()
    object NotAuthenticated : AuthState()
}

/**
 * Events related to authentication changes
 */
sealed class AuthEvent {
    object TokenExpired : AuthEvent()
    object LoggedOut : AuthEvent()
    object LoggedIn : AuthEvent()
}

/**
 * Centralized manager for authentication state.
 * Single source of truth for auth status across the app.
 */
@Singleton
class AuthStateManager @Inject constructor(
    private val encryptedPreferences: EncryptedPreferences
) {
    companion object {
        private const val TAG = "AuthStateManager"
        private const val TOKEN_EXPIRED_DEBOUNCE_MS = 5_000L
    }

    private val _authState = MutableStateFlow(getCurrentAuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _authEvents = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val authEvents: SharedFlow<AuthEvent> = _authEvents.asSharedFlow()

    /**
     * Check if user is currently authenticated.
     * Uses StateFlow value to stay in sync with reactive state.
     */
    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.Authenticated

    /**
     * Refresh auth state from preferences
     */
    fun refreshAuthState() {
        val newState = getCurrentAuthState()
        Timber.tag(TAG).d("Refreshing auth state: $newState")
        _authState.value = newState
    }

    // Debounce: prevent multiple TokenExpired events from concurrent 401 responses
    private val tokenExpiredLock = Any()
    @Volatile
    private var lastTokenExpiredTime = 0L

    /**
     * Called when token expires and refresh fails.
     * Debounced to prevent multiple snackbars from concurrent 401 responses.
     */
    fun onTokenExpired() {
        val now = System.currentTimeMillis()
        synchronized(tokenExpiredLock) {
            if (now - lastTokenExpiredTime < TOKEN_EXPIRED_DEBOUNCE_MS) {
                Timber.tag(TAG).d("Token expired debounced (${now - lastTokenExpiredTime}ms since last)")
                return
            }
            lastTokenExpiredTime = now
        }

        Timber.tag(TAG).w("Token expired, clearing auth")
        encryptedPreferences.clearAuth()
        CorrelationIdInterceptor.resetSessionId() // New session on re-login
        _authState.value = AuthState.NotAuthenticated
        _authEvents.tryEmit(AuthEvent.TokenExpired)
    }

    /**
     * Called when user logs out manually
     */
    fun onLogout() {
        Timber.tag(TAG).i("User logged out")
        encryptedPreferences.clearAuth()
        CorrelationIdInterceptor.resetSessionId() // New session on re-login
        _authState.value = AuthState.NotAuthenticated
        _authEvents.tryEmit(AuthEvent.LoggedOut)
    }

    /**
     * Called when user successfully logs in (e.g., after deep link auth)
     */
    fun onLogin() {
        Timber.tag(TAG).i("User logged in")
        _authState.value = AuthState.Authenticated
        _authEvents.tryEmit(AuthEvent.LoggedIn)
    }

    private fun getCurrentAuthState(): AuthState {
        return if (encryptedPreferences.isLoggedIn()) {
            AuthState.Authenticated
        } else {
            AuthState.NotAuthenticated
        }
    }
}
