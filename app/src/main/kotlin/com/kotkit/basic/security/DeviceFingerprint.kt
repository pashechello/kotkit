package com.kotkit.basic.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceFingerprint @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "device_fingerprint"
        private const val KEY_DEVICE_ID = "device_id"
    }

    /**
     * Get unique device identifier
     * Uses Android ID combined with device info, or generates a UUID if unavailable
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        // Check if we have a cached device ID
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedId = prefs.getString(KEY_DEVICE_ID, null)
        if (cachedId != null) {
            return cachedId
        }

        // Generate device ID
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val deviceId = if (androidId != null && androidId != "9774d56d682e549c") {
            // Use Android ID if available and not the known buggy value
            generateHash(androidId + Build.MODEL + Build.MANUFACTURER)
        } else {
            // Fallback to generated UUID
            UUID.randomUUID().toString()
        }

        // Cache the device ID
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()

        return deviceId
    }

    /**
     * Get device info for analytics/debugging
     */
    fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk_version" to Build.VERSION.SDK_INT.toString(),
            "android_version" to Build.VERSION.RELEASE,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT
        )
    }

    private fun generateHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
