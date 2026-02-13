package com.kotkit.basic.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.judemanutd.autostarter.AutoStartPermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper для управления OEM-специфичными разрешениями автозапуска.
 *
 * Производители как Xiaomi, Samsung, Huawei имеют свои собственные "убийцы задач"
 * которые игнорируют стандартные Android правила. Для надежной работы Worker Mode
 * необходимо включить автозапуск в настройках OEM.
 *
 * Использует библиотеку AutoStarter для определения производителя и открытия настроек.
 */
@Singleton
class AutostartHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AutostartHelper"
        private const val PREFS_NAME = "autostart_prefs"
        private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"

        /**
         * Список OEM производителей с агрессивным battery management.
         * Эти устройства требуют дополнительных разрешений для фоновой работы.
         */
        private val AGGRESSIVE_OEMS = setOf(
            "xiaomi", "redmi", "poco",      // MIUI
            "samsung",                       // One UI
            "huawei", "honor",              // EMUI
            "oppo", "realme", "oneplus",    // ColorOS / OxygenOS
            "vivo", "iqoo",                 // FuntouchOS
            "asus",                          // ZenUI
            "nokia",                         // Stock-like but aggressive
            "lenovo", "motorola"            // Aggressive on some models
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val autoStartHelper = AutoStartPermissionHelper.getInstance()

    /**
     * Проверяет требуется ли на этом устройстве разрешение автозапуска.
     *
     * @return true если устройство от OEM с агрессивным battery management
     */
    fun isAutostartRequired(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isRequired = AGGRESSIVE_OEMS.any { manufacturer.contains(it) }

        Timber.tag(TAG).d("Autostart required: $isRequired (manufacturer: $manufacturer)")
        return isRequired
    }

    /**
     * Проверяет подтвердил ли пользователь включение автозапуска.
     *
     * Поскольку API для программной проверки autostart не существует,
     * мы полагаемся на подтверждение пользователя.
     *
     * @return true если пользователь подтвердил что включил автозапуск
     */
    fun isAutostartConfirmed(): Boolean {
        return prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false)
    }

    /**
     * Сохраняет подтверждение пользователя о включении автозапуска.
     *
     * @param confirmed true если пользователь подтвердил включение
     */
    fun setAutostartConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed).apply()
        Timber.tag(TAG).i("Autostart confirmed by user: $confirmed")
    }

    /**
     * Проверяет доступны ли на устройстве OEM-специфичные настройки автозапуска.
     *
     * Использует библиотеку AutoStarter для определения.
     *
     * @return true если устройство поддерживает autostart settings
     */
    fun isAutostartAvailable(): Boolean {
        return try {
            val available = autoStartHelper.isAutoStartPermissionAvailable(context)
            Timber.tag(TAG).d("Autostart settings available: $available")
            available
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check autostart availability")
            false
        }
    }

    /**
     * Возвращает название производителя устройства.
     *
     * @return Человекочитаемое имя (Xiaomi, Samsung, Huawei, и т.д.)
     */
    fun getManufacturerName(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> "Xiaomi"
            manufacturer.contains("samsung") -> "Samsung"
            manufacturer.contains("huawei") -> "Huawei"
            manufacturer.contains("honor") -> "Honor"
            manufacturer.contains("oppo") -> "Oppo"
            manufacturer.contains("realme") -> "Realme"
            manufacturer.contains("oneplus") -> "OnePlus"
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "Vivo"
            manufacturer.contains("asus") -> "Asus"
            manufacturer.contains("nokia") -> "Nokia"
            manufacturer.contains("lenovo") -> "Lenovo"
            manufacturer.contains("motorola") -> "Motorola"
            else -> Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * SECURITY: Проверяет что Intent ведет на системное приложение.
     *
     * Предотвращает intent injection атаки где злоумышленник может подменить
     * системное приложение на вредоносное и показать поддельные настройки.
     *
     * @param intent Intent для проверки
     * @return true если intent безопасен (ведет на system app)
     */
    private fun isIntentSafe(intent: Intent): Boolean {
        return try {
            // Resolve intent to activity
            val resolveInfo = context.packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )

            if (resolveInfo == null) {
                Timber.tag(TAG).w("SECURITY: Intent cannot be resolved (component not found)")
                return false
            }

            // Get package info
            val packageName = resolveInfo.activityInfo.packageName
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)

            // Check if system app (FLAG_SYSTEM)
            val isSystemApp = (packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            if (!isSystemApp) {
                Timber.tag(TAG).e("SECURITY: Intent resolves to NON-SYSTEM app: $packageName")
                return false
            }

            Timber.tag(TAG).d("SECURITY: Intent validated - system app: $packageName")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "SECURITY: Failed to validate intent safety")
            false
        }
    }

    /**
     * Открывает OEM-специфичные настройки автозапуска.
     *
     * Сначала пробует ручные OEM-интенты (с валидацией isIntentSafe),
     * потом библиотеку AutoStarter как fallback.
     * Ручные интенты надёжнее: они проверяют что Activity существует,
     * в то время как AutoStarter может молча "успешно" ничего не открыть.
     *
     * @return true если настройки были успешно открыты
     */
    fun openAutostartSettings(): Boolean {
        Timber.tag(TAG).i("Opening autostart settings for ${getManufacturerName()}")

        // Попытка 1: Ручные OEM-специфичные Intent (с проверкой isIntentSafe)
        if (openAutostartSettingsManual()) {
            return true
        }

        // Попытка 2: Библиотека AutoStarter (fallback, может молча не сработать)
        return try {
            autoStartHelper.getAutoStartPermission(context)
            Timber.tag(TAG).i("Opened autostart settings via AutoStarter library")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "AutoStarter library also failed")
            false
        }
    }

    /**
     * Ручные fallback методы для открытия настроек автозапуска.
     *
     * Используется если библиотека AutoStarter не смогла открыть настройки.
     * Пробует OEM-специфичные Intent'ы в зависимости от производителя.
     *
     * @return true если настройки были открыты
     */
    private fun openAutostartSettingsManual(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()

        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                openXiaomiAutostart() || openAppSettings()
            }
            manufacturer.contains("samsung") -> {
                openSamsungBatterySettings() || openAppSettings()
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                openHuaweiProtectedApps() || openAppSettings()
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                openOppoAutostart() || openAppSettings()
            }
            manufacturer.contains("vivo") -> {
                openVivoAutostart() || openAppSettings()
            }
            manufacturer.contains("asus") -> {
                openAsusAutostart() || openAppSettings()
            }
            else -> {
                Timber.tag(TAG).w("No manual fallback for manufacturer: $manufacturer")
                openAppSettings()
            }
        }
    }

    /**
     * Xiaomi/MIUI: Открывает страницу ВСЕХ разрешений приложения.
     * Включает: Автозапуск, Показ на экране блокировки, Всплывающие окна.
     */
    private fun openXiaomiAutostart(): Boolean {
        return try {
            // Открываем PermissionsEditorActivity - показывает ВСЕ разрешения MIUI
            // включая "Show on lock screen" которое нужно для запуска Activity из фона
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // SECURITY: Validate intent points to system app before launching
            if (!isIntentSafe(intent)) {
                Timber.tag(TAG).e("SECURITY: Xiaomi autostart intent validation failed!")
                return false
            }

            context.startActivity(intent)
            Timber.tag(TAG).i("Opened Xiaomi autostart settings")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open Xiaomi autostart settings")
            false
        }
    }

    /**
     * Samsung/One UI: Открывает настройки приложения где пользователь может
     * добавить приложение в "Never sleeping apps".
     */
    private fun openSamsungBatterySettings(): Boolean {
        return try {
            // Samsung One UI: Settings → Apps → [App] → Battery
            // Пользователь должен добавить в "Never sleeping apps"
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.tag(TAG).i("Opened Samsung app settings (battery)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open Samsung battery settings")
            false
        }
    }

    /**
     * Huawei/EMUI: Открывает Protected Apps настройки.
     */
    private fun openHuaweiProtectedApps(): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // SECURITY: Validate intent points to system app
            if (!isIntentSafe(intent)) {
                Timber.tag(TAG).e("SECURITY: Huawei protected apps intent validation failed!")
                return false
            }

            context.startActivity(intent)
            Timber.tag(TAG).i("Opened Huawei protected apps")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open Huawei protected apps")
            false
        }
    }

    /**
     * Oppo/ColorOS: Открывает Startup Manager.
     */
    private fun openOppoAutostart(): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // SECURITY: Validate intent points to system app
            if (!isIntentSafe(intent)) {
                Timber.tag(TAG).e("SECURITY: Oppo startup manager intent validation failed!")
                return false
            }

            context.startActivity(intent)
            Timber.tag(TAG).i("Opened Oppo startup manager")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open Oppo startup manager")
            // Alternative for older ColorOS versions
            try {
                val altIntent = Intent().apply {
                    component = ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // SECURITY: Validate alternative intent too
                if (!isIntentSafe(altIntent)) {
                    Timber.tag(TAG).e("SECURITY: Oppo startup manager (alt) intent validation failed!")
                    return false
                }

                context.startActivity(altIntent)
                Timber.tag(TAG).i("Opened Oppo startup manager (alternative)")
                true
            } catch (e2: Exception) {
                Timber.tag(TAG).w(e2, "Failed to open Oppo startup manager (alternative)")
                false
            }
        }
    }

    /**
     * Vivo/FuntouchOS: Открывает Background apps manager.
     */
    private fun openVivoAutostart(): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // SECURITY: Validate intent points to system app
            if (!isIntentSafe(intent)) {
                Timber.tag(TAG).e("SECURITY: Vivo autostart intent validation failed!")
                return false
            }

            context.startActivity(intent)
            Timber.tag(TAG).i("Opened Vivo background apps manager")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open Vivo background apps manager")
            false
        }
    }

    /**
     * Asus/ZenUI: Открывает Auto-start Manager.
     */
    private fun openAsusAutostart(): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.MainActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // SECURITY: Validate intent points to system app
            if (!isIntentSafe(intent)) {
                Timber.tag(TAG).e("SECURITY: Asus autostart intent validation failed!")
                return false
            }

            context.startActivity(intent)
            Timber.tag(TAG).i("Opened Asus mobile manager")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open Asus mobile manager")
            false
        }
    }

    /**
     * Последний fallback: открывает общую страницу настроек приложения.
     *
     * Пользователь должен вручную найти настройки battery/autostart.
     *
     * @return true если настройки были открыты
     */
    private fun openAppSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.tag(TAG).i("Opened app settings (fallback)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open app settings (final fallback)")
            false
        }
    }

    /**
     * Возвращает инструкцию для пользователя как включить автозапуск на его устройстве.
     *
     * @return Человекочитаемая инструкция специфичная для производителя
     */
    fun getInstructionsForManufacturer(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()

        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                "Включите ВСЕ разрешения: Автозапуск, Показ на экране блокировки, Всплывающие окна"
            }
            manufacturer.contains("samsung") -> {
                "Настройки → Батарея → Не переводить в спящий режим → Добавить KotKit"
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                "Настройки → Батарея → Запуск приложений → KotKit → Управлять вручную → Включить всё"
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                "Настройки → Батарея → Запуск приложений → KotKit → Разрешить"
            }
            manufacturer.contains("vivo") -> {
                "Настройки → Батарея → Фоновые приложения → KotKit → Разрешить"
            }
            manufacturer.contains("asus") -> {
                "Mobile Manager → Автозапуск → KotKit → Разрешить"
            }
            else -> {
                "Найдите настройки автозапуска или battery optimization и добавьте KotKit в исключения"
            }
        }
    }
}
