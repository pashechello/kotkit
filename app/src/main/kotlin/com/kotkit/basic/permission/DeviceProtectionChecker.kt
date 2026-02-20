package com.kotkit.basic.permission

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.kotkit.basic.executor.accessibility.TikTokAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Централизованная проверка всех критических защит устройства для Worker Mode.
 *
 * Агрегирует проверки: Accessibility, Notifications, Battery Optimization, OEM Autostart.
 * Используется в WorkerDashboardViewModel для блокировки кнопки БАБЛО
 * до тех пор, пока все защиты не настроены.
 */
@Singleton
class DeviceProtectionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val batteryOptimizationHelper: BatteryOptimizationHelper,
    private val autostartHelper: AutostartHelper
) {

    data class ProtectionStatus(
        val isAccessibilityEnabled: Boolean,
        val isNotificationsEnabled: Boolean,
        val isBatteryOptimizationDisabled: Boolean,
        val isAutostartRequired: Boolean,
        val isAutostartConfirmed: Boolean,
        val manufacturerName: String
    ) {
        val isAccessibilityOk: Boolean get() = isAccessibilityEnabled
        val isNotificationsOk: Boolean get() = isNotificationsEnabled
        val isBatteryOk: Boolean get() = isBatteryOptimizationDisabled
        val isAutostartOk: Boolean get() = !isAutostartRequired || isAutostartConfirmed

        val allCriticalOk: Boolean get() = isAccessibilityOk && isNotificationsOk && isBatteryOk && isAutostartOk

        val missingItems: List<MissingProtection>
            get() = buildList {
                if (!isAccessibilityOk) add(MissingProtection.ACCESSIBILITY)
                if (!isNotificationsOk) add(MissingProtection.NOTIFICATIONS)
                if (!isBatteryOk) add(MissingProtection.BATTERY_OPTIMIZATION)
                if (!isAutostartOk) add(MissingProtection.AUTOSTART)
            }
    }

    enum class MissingProtection {
        ACCESSIBILITY,
        NOTIFICATIONS,
        BATTERY_OPTIMIZATION,
        AUTOSTART
    }

    fun check(): ProtectionStatus {
        return ProtectionStatus(
            isAccessibilityEnabled = TikTokAccessibilityService.isServiceEnabled(context),
            isNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            isBatteryOptimizationDisabled = batteryOptimizationHelper.isBatteryOptimizationDisabled(),
            isAutostartRequired = autostartHelper.isAutostartRequired(),
            isAutostartConfirmed = autostartHelper.isAutostartConfirmed(),
            manufacturerName = autostartHelper.getManufacturerName()
        )
    }
}
