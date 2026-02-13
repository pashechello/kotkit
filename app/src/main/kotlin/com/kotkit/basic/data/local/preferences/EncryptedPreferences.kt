package com.kotkit.basic.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kotkit.basic.sound.SoundSettings

class EncryptedPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "autoposter_encrypted_prefs"

        // Keys
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_APP_LANGUAGE = "app_language"

        // Sound settings keys
        private const val KEY_SOUNDS_ENABLED = "sounds_enabled"
        private const val KEY_WARNING_SOUND_ENABLED = "warning_sound_enabled"
        private const val KEY_STARTING_SOUND_ENABLED = "starting_sound_enabled"
        private const val KEY_SUCCESS_SOUND_ENABLED = "success_sound_enabled"
        private const val KEY_ERROR_SOUND_ENABLED = "error_sound_enabled"
        private const val KEY_UI_SOUNDS_ENABLED = "ui_sounds_enabled"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Auth Token
    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    // Onboarding
    var isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    var isAccessibilityEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACCESSIBILITY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ACCESSIBILITY_ENABLED, value).apply()

    // Server URL (for custom server setup)
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    // App Language (default is Russian "ru")
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "ru") ?: "ru"
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    // Sound Settings
    var soundSettings: SoundSettings
        get() = SoundSettings(
            soundsEnabled = prefs.getBoolean(KEY_SOUNDS_ENABLED, true),
            warningEnabled = prefs.getBoolean(KEY_WARNING_SOUND_ENABLED, true),
            startingEnabled = prefs.getBoolean(KEY_STARTING_SOUND_ENABLED, true),
            successEnabled = prefs.getBoolean(KEY_SUCCESS_SOUND_ENABLED, true),
            errorEnabled = prefs.getBoolean(KEY_ERROR_SOUND_ENABLED, true),
            uiSoundsEnabled = prefs.getBoolean(KEY_UI_SOUNDS_ENABLED, true),
            volume = prefs.getInt(KEY_SOUND_VOLUME, 80),
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        )
        set(value) {
            prefs.edit()
                .putBoolean(KEY_SOUNDS_ENABLED, value.soundsEnabled)
                .putBoolean(KEY_WARNING_SOUND_ENABLED, value.warningEnabled)
                .putBoolean(KEY_STARTING_SOUND_ENABLED, value.startingEnabled)
                .putBoolean(KEY_SUCCESS_SOUND_ENABLED, value.successEnabled)
                .putBoolean(KEY_ERROR_SOUND_ENABLED, value.errorEnabled)
                .putBoolean(KEY_UI_SOUNDS_ENABLED, value.uiSoundsEnabled)
                .putInt(KEY_SOUND_VOLUME, value.volume)
                .putBoolean(KEY_VIBRATION_ENABLED, value.vibrationEnabled)
                .apply()
        }

    fun isLoggedIn(): Boolean {
        return !authToken.isNullOrBlank()
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // Generic methods for other components
    fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}
