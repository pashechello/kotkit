package com.kotkit.basic.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper для управления исключениями из оптимизации батареи.
 *
 * Android Doze mode агрессивно убивает фоновые процессы для экономии батареи.
 * Для надежной работы Worker Mode необходимо получить исключение.
 *
 * Требует разрешение: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS в AndroidManifest.xml
 */
@Singleton
class BatteryOptimizationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BatteryOptHelper"
    }

    /**
     * Проверяет, отключена ли оптимизация батареи для приложения.
     *
     * @return true если приложение в whitelist (Doze mode не применяется)
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            Timber.tag(TAG).d("Battery optimization status: ${if (isIgnoring) "DISABLED (good)" else "ENABLED (bad)"}")
            isIgnoring
        } else {
            // Android < 6.0 не имеет Doze mode
            Timber.tag(TAG).d("Battery optimization: N/A (Android < 6.0)")
            true
        }
    }

    /**
     * Открывает диалог системы для запроса исключения из оптимизации батареи.
     *
     * Показывает стандартный Android диалог: "Allow [App] to ignore battery optimizations?"
     * Пользователь может разрешить или отклонить.
     *
     * @return true если intent был успешно запущен
     */
    fun openBatteryOptimizationSettings(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    // FLAG_ACTIVITY_NEW_TASK требуется если вызываем из non-Activity контекста
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Timber.tag(TAG).i("Opened battery optimization dialog")
                true
            } else {
                Timber.tag(TAG).w("Battery optimization settings N/A on Android < 6.0")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open battery optimization dialog")
            // Fallback: попробовать открыть общий список исключений
            openBatteryOptimizationListSettings()
        }
    }

    /**
     * Запасной вариант: открывает системный список всех приложений с исключениями.
     *
     * Пользователь должен найти приложение вручную в списке и включить исключение.
     * Используется если прямой intent (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) не работает.
     *
     * @return true если intent был успешно запущен
     */
    fun openBatteryOptimizationListSettings(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Timber.tag(TAG).i("Opened battery optimization list settings (fallback)")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open battery optimization list settings")
            false
        }
    }

    /**
     * Открывает страницу настроек приложения в системе.
     *
     * Последний fallback если оба предыдущих метода не работают.
     * Пользователь увидит все настройки приложения включая батарею.
     *
     * @return true если intent был успешно запущен
     */
    fun openAppSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.tag(TAG).i("Opened app settings page")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open app settings")
            false
        }
    }

    /**
     * Проверяет доступно ли разрешение REQUEST_IGNORE_BATTERY_OPTIMIZATIONS в манифесте.
     *
     * @return true если разрешение объявлено в AndroidManifest.xml
     */
    fun isPermissionDeclared(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_PERMISSIONS
            )
            val permissions = packageInfo.requestedPermissions ?: emptyArray()
            val declared = permissions.contains(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)

            if (!declared) {
                Timber.tag(TAG).w("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS not declared in manifest!")
            }

            declared
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check permission declaration")
            false
        }
    }
}
