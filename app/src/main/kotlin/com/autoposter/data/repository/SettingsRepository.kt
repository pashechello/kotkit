package com.autoposter.data.repository

import com.autoposter.data.local.keystore.KeystoreManager
import com.autoposter.data.local.preferences.EncryptedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val encryptedPreferences: EncryptedPreferences,
    private val keystoreManager: KeystoreManager
) {
    // Onboarding
    var isOnboardingComplete: Boolean
        get() = encryptedPreferences.isOnboardingComplete
        set(value) { encryptedPreferences.isOnboardingComplete = value }

    var isAccessibilityEnabled: Boolean
        get() = encryptedPreferences.isAccessibilityEnabled
        set(value) { encryptedPreferences.isAccessibilityEnabled = value }

    // Unlock credentials
    fun hasUnlockCredentials(): Boolean = keystoreManager.hasUnlockCredentials()

    fun hasStoredPin(): Boolean = keystoreManager.hasStoredPin()

    fun hasStoredPassword(): Boolean = keystoreManager.hasStoredPassword()

    fun savePin(pin: String) {
        keystoreManager.clearPassword() // Only one type at a time
        keystoreManager.savePin(pin)
    }

    fun savePassword(password: String) {
        keystoreManager.clearPin() // Only one type at a time
        keystoreManager.savePassword(password)
    }

    fun getStoredPin(): String? = keystoreManager.getStoredPin()

    fun getStoredPassword(): String? = keystoreManager.getStoredPassword()

    fun clearUnlockCredentials() {
        keystoreManager.clearAll()
    }

    // Server URL
    var serverUrl: String?
        get() = encryptedPreferences.serverUrl
        set(value) { encryptedPreferences.serverUrl = value }

    // Clear all settings
    fun clearAll() {
        encryptedPreferences.clearAll()
        keystoreManager.clearAll()
    }
}
