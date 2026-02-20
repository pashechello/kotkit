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

    /**
     * Called when token expires and refresh fails.
     * Silently clears auth — UI reacts via authState, no notification needed.
     */
    fun onTokenExpired() {
        Timber.tag(TAG).w("Token expired, clearing auth")
        encryptedPreferences.clearAuth()
        CorrelationIdInterceptor.resetSessionId()
        _authState.value = AuthState.NotAuthenticated
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
