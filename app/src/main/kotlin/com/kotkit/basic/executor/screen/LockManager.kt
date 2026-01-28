package com.kotkit.basic.executor.screen

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    /**
     * Check if the device is currently locked
     */
    fun isLocked(): Boolean {
        return keyguardManager.isKeyguardLocked
    }

    /**
     * Check if the device has a secure lock (PIN/password/pattern)
     */
    fun isDeviceSecure(): Boolean {
        return keyguardManager.isDeviceSecure
    }

    /**
     * Get the current lock type
     */
    fun getLockType(): LockType {
        return when {
            !isLocked() -> LockType.NONE
            !isDeviceSecure() -> LockType.SWIPE
            // For PIN/PASSWORD/PATTERN we need to check what's stored
            else -> LockType.UNKNOWN
        }
    }
}

enum class LockType {
    NONE,
    SWIPE,
    PIN,
    PASSWORD,
    PATTERN,
    BIOMETRIC,
    UNKNOWN
}
