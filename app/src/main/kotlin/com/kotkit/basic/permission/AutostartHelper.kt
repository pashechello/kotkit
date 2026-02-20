package com.kotkit.basic.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
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
            "xiaomi", "redmi", "poco",      // MIUI / HyperOS
            "samsung",                       // One UI
            "huawei", "honor",              // EMUI / MagicOS
            "oppo", "realme", "oneplus",    // ColorOS / OxygenOS
            "vivo", "iqoo",                 // FuntouchOS / OriginOS
            "asus"                           // ZenUI
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val autoStartHelper = AutoStartPermissionHelper.getInstance()

    /** Кешированное lowercase название производителя (null-safe). */
    private val manufacturer: String by lazy {
        Build.MANUFACTURER?.lowercase().orEmpty()
    }

    /**
     * Проверяет требуется ли на этом устройстве разрешение автозапуска.
     *
     * @return true если устройство от OEM с агрессивным battery management
     */
    fun isAutostartRequired(): Boolean {
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
            else -> manufacturer.replaceFirstChar { it.uppercase() }
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
            // Action-only интенты (без component) могут не иметь CATEGORY_DEFAULT,
            // поэтому используем MATCH_ALL (0) для их резолва
            val flags = if (intent.component != null)
                PackageManager.MATCH_DEFAULT_ONLY else 0
            val resolveInfo = context.packageManager.resolveActivity(intent, flags)

            if (resolveInfo == null) {
                Timber.tag(TAG).w("SECURITY: Intent cannot be resolved (component not found)")
                return false
            }

            val packageName = resolveInfo.activityInfo.packageName

            // ResolverActivity ("Open with" dialog) lives in package "android" on stock Android.
            // Huawei/EMUI uses "com.huawei.android.internal.app" (HwResolverActivity) instead.
            // Both mean no real handler exists for this intent.
            if (packageName == "android" || packageName == "com.huawei.android.internal.app") {
                Timber.tag(TAG).w("SECURITY: Intent resolved to system resolver ($packageName) — no real handler")
                return false
            }

            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)

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
     * Пробует запустить Activity с проверкой безопасности.
     *
     * @param intent Intent для запуска
     * @param description Описание для логов
     * @return true если Activity успешно запущена
     */
    private fun tryStartActivity(intent: Intent, description: String): Boolean {
        return try {
            if (!isIntentSafe(intent)) {
                Timber.tag(TAG).w("SECURITY: $description — intent validation failed")
                return false
            }
            context.startActivity(intent)
            Timber.tag(TAG).i("Opened: $description")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open: $description")
            false
        }
    }

    /**
     * Открывает OEM-специфичные настройки автозапуска.
     *
     * Сначала пробует наши OEM-интенты (с валидацией isIntentSafe),
     * потом библиотеку AutoStarter как fallback.
     * Если ничего не сработало — возвращает false,
     * и ViewModel покажет диалог с ручными инструкциями.
     *
     * @return true если настройки были успешно открыты
     */
    fun openAutostartSettings(): Boolean {
        Timber.tag(TAG).i("Opening autostart settings for ${getManufacturerName()}")

        // Попытка 1: Наши OEM-специфичные Intent'ы
        if (openAutostartSettingsManual()) {
            return true
        }

        // Попытка 2: Библиотека AutoStarter (fallback)
        return try {
            val opened = autoStartHelper.getAutoStartPermission(context)
            if (opened) {
                Timber.tag(TAG).i("Opened autostart settings via AutoStarter library")
            } else {
                Timber.tag(TAG).w("AutoStarter library returned false")
            }
            opened
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "AutoStarter library also failed")
            false
        }
    }

    /**
     * Пробует открыть настройки через OEM-специфичные Intent'ы.
     *
     * НЕ делает fallback на generic app settings — если все интенты
     * не сработали, возвращает false для показа ручных инструкций.
     *
     * @return true если настройки были открыты
     */
    private fun openAutostartSettingsManual(): Boolean {
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                openXiaomiAutostart()
            }
            manufacturer.contains("samsung") -> {
                openSamsungNeverSleeping()
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                openHuaweiStartupManager()
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                openOppoAutostart()
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                openVivoAutostart()
            }
            manufacturer.contains("asus") -> {
                openAsusAutostart()
            }
            else -> {
                Timber.tag(TAG).w("No manual fallback for manufacturer: $manufacturer")
                false
            }
        }
    }

    /**
     * Xiaomi/MIUI/HyperOS: Открывает страницу ВСЕХ разрешений приложения.
     * Включает: Автозапуск, Показ на экране блокировки, Всплывающие окна.
     */
    private fun openXiaomiAutostart(): Boolean {
        return tryStartActivity(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Xiaomi MIUI Permission Editor"
        )
    }

    /**
     * Samsung/One UI: Открывает "Никогда не переводить в спящий режим".
     *
     * Цепочка fallback:
     * 1. "Never sleeping apps" напрямую (One UI 3.0+, Android 11+)
     * 2. Device Care → Battery (One UI)
     * 3. Smart Manager → Battery (старые Samsung)
     */
    private fun openSamsungNeverSleeping(): Boolean {
        // Try 1: "Never sleeping apps" напрямую (One UI 3.0+)
        // Official Samsung API: https://developer.samsung.com/mobile/app-management.html
        return tryStartActivity(
            Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").apply {
                setPackage("com.samsung.android.lool")
                putExtra("activity_type", 2) // 0=Sleeping, 1=Deep sleeping, 2=Never sleeping
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Samsung Never Sleeping Apps"
        ) || tryStartActivity(
            // Try 2: Device Care Battery page (One UI)
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Samsung Device Care Battery"
        ) || tryStartActivity(
            // Try 3: Smart Manager Battery (старые Samsung, Android 5-6)
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Samsung Smart Manager Battery"
        )
    }

    /**
     * Huawei/Honor (EMUI/MagicOS): Открывает Startup Manager.
     *
     * ВАЖНО: Honor после отделения от Huawei использует пакет com.hihonor.systemmanager
     * вместо com.huawei.systemmanager. Activity names остались те же.
     *
     * Цепочка fallback:
     * 1. HSM_BOOTAPP_MANAGER action — EMUI 8+ / HarmonyOS (самый надёжный)
     * 2. com.hihonor.systemmanager StartupNormalAppListActivity — Honor MagicOS
     * 3. com.huawei.systemmanager StartupNormalAppListActivity — Huawei EMUI 9+
     * 4. StartupAppControlActivity (EMUI 5-8)
     * 5. ProtectActivity (EMUI < 5)
     * 6. getLaunchIntentForPackage — открывает System Manager напрямую
     */
    private fun openHuaweiStartupManager(): Boolean {
        // Try 1: Action-based intent (EMUI 8+ / HarmonyOS) — самый надёжный,
        // т.к. не зависит от конкретных Activity names которые меняются между версиями
        return tryStartActivity(
            Intent("huawei.intent.action.HSM_BOOTAPP_MANAGER").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Huawei HSM Boot App Manager"
        ) || tryStartActivity(
            // Try 2: Honor MagicOS (com.hihonor.systemmanager)
            // Honor после отделения от Huawei использует свой пакет, но те же Activity names
            Intent().apply {
                component = ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Honor Startup Manager"
        ) || tryStartActivity(
            // Try 3: Huawei EMUI 9+ (com.huawei.systemmanager)
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Huawei Startup Manager"
        ) || tryStartActivity(
            // Try 4: Startup App Control (EMUI 5-8)
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Huawei Startup App Control"
        ) || tryStartActivity(
            // Try 5: Protected Apps (EMUI < 5)
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Huawei Protected Apps"
        ).also { opened ->
            // Если ни один специфичный экран не открылся — открываем System Manager
            // как вспомогательный контекст, но возвращаем false чтобы показался
            // manual dialog с инструкциями (пользователю нужно объяснить куда идти)
            if (!opened) openSystemManagerApp()
        }
    }

    /**
     * Fallback: открывает приложение System Manager напрямую.
     * Пользователь сам навигирует к разделу автозапуска.
     * Проходит через tryStartActivity() для проверки isIntentSafe().
     */
    private fun openSystemManagerApp(): Boolean {
        val packages = listOf("com.hihonor.systemmanager", "com.huawei.systemmanager")
        for (pkg in packages) {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (tryStartActivity(launchIntent, "System Manager app: $pkg")) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to get launch intent for: $pkg")
            }
        }
        return false
    }

    /**
     * Oppo/Realme/OnePlus (ColorOS/OxygenOS): Открывает Startup Manager.
     *
     * С OxygenOS 13+ OnePlus использует ColorOS, поэтому интенты общие.
     *
     * Цепочка fallback:
     * 1. com.coloros.safecenter (ColorOS 7-12)
     * 2. com.coloros.safecenter alt activity path
     * 3. com.oplus.safecenter (ColorOS 13+ ребренд, те же Activity names)
     * 4. com.oppo.safe (pre-ColorOS 7)
     * 5. com.color.safecenter (ещё старее)
     * 6. com.oneplus.security (OxygenOS ≤ 12)
     * 7. ACTION BACKGROUND_OPTIMIZE (OnePlus fallback, OxygenOS 12+)
     */
    private fun openOppoAutostart(): Boolean {
        // Try 1: ColorOS 7-12 (Oppo/Realme/OnePlus)
        return tryStartActivity(
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "ColorOS Startup Manager"
        ) || tryStartActivity(
            // Try 2: Alternate activity path
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "ColorOS Startup Manager (alt)"
        ) || tryStartActivity(
            // Try 3: ColorOS 13+ / OxygenOS 13+ (oplus ребренд, Activity names те же)
            Intent().apply {
                component = ComponentName(
                    "com.oplus.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Oplus Safe Center (ColorOS 13+)"
        ) || tryStartActivity(
            // Try 4: Older Oppo (pre-ColorOS 7)
            Intent().apply {
                component = ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Oppo Safe Center"
        ) || tryStartActivity(
            // Try 5: Even older (com.color)
            Intent().apply {
                component = ComponentName(
                    "com.color.safecenter",
                    "com.color.safecenter.permission.startup.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "ColorOS Safe Center (legacy)"
        ) || tryStartActivity(
            // Try 6: OnePlus OxygenOS ≤ 12
            Intent().apply {
                component = ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "OnePlus Chain Launch Manager"
        ) || tryStartActivity(
            // Try 7: OnePlus fallback action (OxygenOS 12+, когда ChainLaunch не работает)
            Intent("com.android.settings.action.BACKGROUND_OPTIMIZE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "OnePlus Background Optimize"
        )
    }

    /**
     * Vivo/iQOO (FuntouchOS/OriginOS): Открывает Background app management.
     *
     * Цепочка fallback:
     * 1. com.iqoo.secure whitelist (iQOO / старые Vivo)
     * 2. com.iqoo.secure BgStartUpManager
     * 3. com.vivo.permissionmanager (стандартные Vivo)
     */
    private fun openVivoAutostart(): Boolean {
        // Try 1: iQOO Secure whitelist
        return tryStartActivity(
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Vivo/iQOO Whitelist"
        ) || tryStartActivity(
            // Try 2: iQOO Background Startup Manager
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Vivo/iQOO Background Startup"
        ) || tryStartActivity(
            // Try 3: Vivo Permission Manager
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Vivo Permission Manager"
        )
    }

    /**
     * Asus/ZenUI: Открывает Auto-start Manager.
     *
     * Цепочка fallback:
     * 1. AutoStartActivity (напрямую)
     * 2. MainActivity (главная Mobile Manager)
     */
    private fun openAsusAutostart(): Boolean {
        // Try 1: Direct Auto-start Activity
        return tryStartActivity(
            Intent().apply {
                component = ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Asus Auto-Start Manager"
        ) || tryStartActivity(
            // Try 2: Main Mobile Manager
            Intent().apply {
                component = ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.MainActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Asus Mobile Manager"
        )
    }

    /**
     * Возвращает инструкцию для пользователя как включить автозапуск на его устройстве.
     * Показывается в диалоге ручных инструкций когда автоматическое открытие не сработало.
     *
     * @return Человекочитаемая инструкция специфичная для производителя
     */
    fun getInstructionsForManufacturer(): String {
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                "Включите ВСЕ разрешения: Автозапуск, Показ на экране блокировки, Всплывающие окна"
            }
            manufacturer.contains("samsung") -> {
                "Настройки → Обслуживание устройства → Батарея → Ограничения в фоне → Никогда не переводить в спящий режим → Добавьте KotKit"
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                "Настройки → Приложения → Запуск приложений → KotKit → Управлять вручную → Включите ВСЕ: Автозапуск, Вторичный запуск, Работа в фоне"
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                "Настройки → Приложения → Управление автозапуском → KotKit → Разрешить"
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                "Настройки → Батарея → Фоновые приложения → KotKit → Разрешить"
            }
            manufacturer.contains("asus") -> {
                "Mobile Manager → Автозапуск → KotKit → Разрешить"
            }
            else -> {
                "Найдите настройки автозапуска или оптимизации батареи и добавьте KotKit в исключения"
            }
        }
    }

    /**
     * Возвращает список пунктов для диалога подтверждения.
     * Разные OEM имеют разные названия для разрешений фоновой работы.
     *
     * @return Список названий разрешений которые пользователь должен был включить
     */
    fun getConfirmationChecklist(): List<String> {
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                listOf("Автозапуск", "Показ на экране блокировки", "Всплывающие окна")
            }
            manufacturer.contains("samsung") -> {
                listOf("KotKit добавлен в «Никогда не переводить в спящий режим»")
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                listOf("Автозапуск", "Вторичный запуск", "Работа в фоне")
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                listOf("Автозапуск", "Работа в фоне")
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                listOf("Автозапуск", "Работа в фоне")
            }
            manufacturer.contains("asus") -> {
                listOf("Автозапуск")
            }
            else -> {
                listOf("Автозапуск", "Работа в фоне")
            }
        }
    }
}
