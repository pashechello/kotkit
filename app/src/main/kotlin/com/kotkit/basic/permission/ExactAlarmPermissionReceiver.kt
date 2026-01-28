package com.kotkit.basic.permission

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * BroadcastReceiver for tracking SCHEDULE_EXACT_ALARM permission changes.
 *
 * Android 12+ sends ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED broadcast
 * when the user enables/disables the permission in settings.
 */
@AndroidEntryPoint
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ExactAlarmPermReceiver"
    }

    @Inject
    lateinit var permissionManager: ExactAlarmPermissionManager

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
                Log.i(TAG, "Exact alarm permission state changed")

                // Update state in manager
                permissionManager.refreshPermissionState()

                val hasPermission = permissionManager.canScheduleExactAlarms()
                Log.i(TAG, "New permission state: hasPermission=$hasPermission")

                // If permission was revoked, reset banner dismissal
                // so user sees the warning
                if (!hasPermission) {
                    permissionManager.resetBannerDismissal()
                }
            }
        }
    }
}
