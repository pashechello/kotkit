package com.kotkit.basic.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kotkit.basic.data.remote.api.ApiService
import com.kotkit.basic.data.remote.api.models.UpdateProfileRequest
import com.kotkit.basic.data.repository.AuthRepository
import com.kotkit.basic.scheduler.AudiencePersona
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for audience persona preferences with hybrid storage:
 * - Local SharedPreferences (for immediate UI response)
 * - Backend sync (for persistence across devices/reinstalls)
 *
 * Flow:
 * 1. On app start: load local -> sync from server (if logged in)
 * 2. On change: save local immediately -> sync to server in background
 */
@Singleton
class AudiencePersonaPreferencesManager @Inject constructor(
    @ApplicationContext context: Context,
    private val apiService: ApiService,
    private val authRepository: AuthRepository
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _personaFlow = MutableStateFlow(getLocalPersona())
    val personaFlow: StateFlow<AudiencePersona> = _personaFlow.asStateFlow()

    // AtomicBoolean for thread-safe race condition prevention
    private val isUserChangePending = AtomicBoolean(false)

    /**
     * Get persona from local storage.
     */
    private fun getLocalPersona(): AudiencePersona {
        val name = prefs.getString(KEY_PERSONA, null)
        return name?.let {
            try {
                AudiencePersona.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: AudiencePersona.DEFAULT
    }

    /**
     * Sync persona from server.
     * Call on app start or after login.
     * Skips if user is not logged in or has pending changes.
     */
    suspend fun syncFromServer() {
        // Skip if not logged in
        if (!authRepository.isLoggedIn()) {
            Log.d(TAG, "syncFromServer: skipped - user not logged in")
            return
        }

        // Skip if user has pending changes to avoid overwriting
        if (isUserChangePending.get()) {
            Log.d(TAG, "syncFromServer: skipped - user change pending")
            return
        }

        try {
            val profile = apiService.getProfile()
            val serverPersona = parsePersona(profile.audiencePersona)

            // Double-check before saving (in case user changed while request was in flight)
            if (!isUserChangePending.get()) {
                saveLocal(serverPersona)
                Log.d(TAG, "syncFromServer: synced persona = $serverPersona")
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncFromServer: failed - using local cache", e)
        }
    }

    /**
     * Set persona - saves locally and syncs to server.
     */
    suspend fun setPersona(persona: AudiencePersona) {
        // Mark that user has pending changes (atomic operation)
        isUserChangePending.set(true)

        // Save locally first (immediate UI update)
        saveLocal(persona)

        // Only sync to server if logged in
        if (!authRepository.isLoggedIn()) {
            Log.d(TAG, "setPersona: saved locally only - user not logged in")
            isUserChangePending.set(false)
            return
        }

        // Sync to server in background
        try {
            apiService.updateProfile(UpdateProfileRequest(audiencePersona = persona.name))
            Log.d(TAG, "setPersona: synced to server = $persona")
        } catch (e: Exception) {
            Log.w(TAG, "setPersona: failed to sync to server", e)
            // Local value is still saved, will sync on next app start
        } finally {
            isUserChangePending.set(false)
        }
    }

    /**
     * Parse persona string from server with null-safety.
     */
    private fun parsePersona(personaName: String?): AudiencePersona {
        if (personaName.isNullOrBlank()) {
            return AudiencePersona.DEFAULT
        }
        return try {
            AudiencePersona.valueOf(personaName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown persona from server: $personaName, using default")
            AudiencePersona.DEFAULT
        }
    }

    /**
     * Save persona to local storage and update flow.
     */
    private fun saveLocal(persona: AudiencePersona) {
        prefs.edit().putString(KEY_PERSONA, persona.name).apply()
        _personaFlow.value = persona
    }

    companion object {
        private const val TAG = "AudiencePersonaPrefs"
        private const val PREFS_NAME = "audience_persona_prefs"
        private const val KEY_PERSONA = "persona"
    }
}
