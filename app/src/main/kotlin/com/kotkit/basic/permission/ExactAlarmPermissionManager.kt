package com.kotkit.basic.permission

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the exact alarm permission.
 */
data class ExactAlarmPermissionState(
    /** Permission is granted */
    val hasPermission: Boolean = true,
    /** Show warning banner on home screen */
    val showBanner: Boolean = false,
    /** Banner is dismissed until this timestamp */
    val bannerDismissedUntil: Long = 0,
    /** Android 12+ device where permission is relevant */
    val isRelevant: Boolean = false
)

/**
 * Manager for SCHEDULE_EXACT_ALARM permission.
 *
 * On Android 12+ (API 31), exact alarms require this permission.
 * Without it, AlarmManager uses inexact alarms with ±15 minute accuracy.
 *
 * Usage:
 * - Inject via Hilt into ViewModels and screens
 * - Provides StateFlow for reactive UI
 * - Call refreshPermissionState() when returning from settings
 */
@Singleton
class ExactAlarmPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: EncryptedPreferences
) {
    companion object {
        private const val PREF_BANNER_DISMISSED_UNTIL = "exact_alarm_banner_dismissed_until"
        private const val DISMISS_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val alarmManager: AlarmManager? = context.getSystemService()

    private val _permissionState = MutableStateFlow(checkCurrentState())
    val permissionState: StateFlow<ExactAlarmPermissionState> = _permissionState.asStateFlow()

    /**
     * Check if exact alarms can be scheduled.
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    /**
     * Check if this permission is relevant on this device.
     */
    fun isPermissionRelevant(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Open system settings for granting the permission.
     */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Refresh permission state.
     * Call when:
     * - Returning from system settings
     * - Receiving broadcast about state change
     * - OnResume of main screens
     */
    fun refreshPermissionState() {
        _permissionState.update { checkCurrentState() }
    }

    /**
     * Dismiss the banner for 24 hours.
     */
    fun dismissBannerTemporarily() {
        val dismissUntil = System.currentTimeMillis() + DISMISS_DURATION_MS
        preferences.putLong(PREF_BANNER_DISMISSED_UNTIL, dismissUntil)
        _permissionState.update {
            it.copy(
                showBanner = false,
                bannerDismissedUntil = dismissUntil
            )
        }
    }

    /**
     * Reset banner dismissal (show it again).
     */
    fun resetBannerDismissal() {
        preferences.putLong(PREF_BANNER_DISMISSED_UNTIL, 0)
        refreshPermissionState()
    }

    private fun checkCurrentState(): ExactAlarmPermissionState {
        val isRelevant = isPermissionRelevant()
        val hasPermission = canScheduleExactAlarms()
        val dismissedUntil = preferences.getLong(PREF_BANNER_DISMISSED_UNTIL, 0)
        val isBannerDismissed = System.currentTimeMillis() < dismissedUntil

        return ExactAlarmPermissionState(
            hasPermission = hasPermission,
            showBanner = isRelevant && !hasPermission && !isBannerDismissed,
            bannerDismissedUntil = dismissedUntil,
            isRelevant = isRelevant
        )
    }
}
