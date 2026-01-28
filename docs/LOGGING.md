# Логирование в KotKit

## Обзор

KotKit использует [Timber](https://github.com/JakeWharton/timber) для логирования с кастомным `FileLoggingTree` для записи логов в файл на устройстве.

## Архитектура

```
┌─────────────────┐
│   Timber.i()    │  ← Вызов логирования в коде
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│ FileLoggingTree │────▶│  Файл на SD     │
│   (всегда)      │     │  /logs/*.log    │
└─────────────────┘     └─────────────────┘
         │
         ▼ (только debug build)
┌─────────────────┐
│   DebugTree     │────▶ Logcat
└─────────────────┘
```

## Где хранятся логи

```
/sdcard/Android/data/com.kotkit.basic/files/logs/
```

Формат имени файла: `kotkit_YYYY-MM-DD.log`

Пример: `kotkit_2025-01-26.log`

## Как получить логи

### Через ADB

```bash
# Скачать все логи
adb pull /sdcard/Android/data/com.kotkit.basic/files/logs/

# Скачать логи за сегодня
adb pull /sdcard/Android/data/com.kotkit.basic/files/logs/kotkit_$(date +%Y-%m-%d).log

# Смотреть логи в реальном времени (debug build)
adb logcat | grep -E "PostingAgent|PostWorker|ScreenUnlocker|SmartScheduler"
```

### Через файловый менеджер

1. Открыть файловый менеджер на телефоне
2. Перейти в: `Android/data/com.kotkit.basic/files/logs/`
3. Скопировать нужный .log файл

## Формат логов

```
2025-01-26 16:45:00.123 I/PostWorker: Starting post execution for ID: 42
2025-01-26 16:45:00.456 D/PostingAgent: Share Intent attempt: Success(SHARE_INTENT)
2025-01-26 16:45:01.789 W/ScreenUnlocker: isLocked: true
2025-01-26 16:45:02.012 E/PostingAgent: Screenshot failed: Permission denied
```

Формат: `TIMESTAMP LEVEL/TAG: MESSAGE`

Уровни:
- `V` - Verbose
- `D` - Debug
- `I` - Info
- `W` - Warning
- `E` - Error

## Ключевые теги для отладки автопубликации

| Тег | Описание |
|-----|----------|
| `PostWorker` | WorkManager задача публикации |
| `PostingAgent` | Основная логика автопубликации |
| `ScreenUnlocker` | Разблокировка экрана |
| `SmartScheduler` | Планировщик публикаций |
| `SchedulerReceiver` | Обработчик алармов |
| `TikTokA11yService` | Accessibility сервис |
| `ActionExecutor` | Выполнение жестов |
| `DeviceStateChecker` | Проверка состояния устройства |
| `RetryInterceptor` | Сетевые запросы и retry |

## Ротация логов

- Логи хранятся **7 дней**
- Старые файлы автоматически удаляются при запуске приложения
- Каждый день создаётся новый файл

## Использование в коде

```kotlin
import timber.log.Timber

class MyClass {
    companion object {
        private const val TAG = "MyClass"
    }

    fun doSomething() {
        Timber.tag(TAG).d("Debug message")
        Timber.tag(TAG).i("Info message")
        Timber.tag(TAG).w("Warning message")
        Timber.tag(TAG).e("Error message")

        // С исключением
        try {
            // ...
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Something failed")
        }
    }
}
```

## Конфигурация

### Включение/отключение файлового логирования

В `App.kt`:

```kotlin
private fun initTimber() {
    // Файловый логгер (всегда включен)
    Timber.plant(FileLoggingTree(this))

    // Logcat (только debug)
    if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
    }
}
```

Чтобы отключить файловое логирование в production, закомментируйте строку с `FileLoggingTree`.

### ProGuard

В `proguard-rules.pro` логи НЕ удаляются:

```proguard
# Keep Timber logging in release builds
-keep class timber.log.Timber { *; }
-keep class timber.log.Timber$Tree { *; }
-keep class com.kotkit.basic.logging.FileLoggingTree { *; }
```

## Отладка проблем с автопубликацией

1. **Запустите публикацию** (вручную или по расписанию)

2. **Дождитесь завершения** (успех или ошибка)

3. **Скачайте логи:**
   ```bash
   adb pull /sdcard/Android/data/com.kotkit.basic/files/logs/
   ```

4. **Найдите нужный временной диапазон** в логах

5. **Ищите ошибки:**
   ```bash
   grep -E " E/| W/" kotkit_2025-01-26.log
   ```

### Типичные проблемы и что искать в логах

| Проблема | Что искать |
|----------|------------|
| Не разблокируется | `ScreenUnlocker`, `isLocked`, `enterPin` |
| TikTok не открывается | `PostingAgent`, `openTikTok`, `Share Intent` |
| Accessibility не работает | `TikTokA11yService`, `getInstance`, `dispatchGesture` |
| Публикация зависает | `PostingAgent`, `Step`, `MAX_STEPS` |
| Не срабатывает по расписанию | `SmartScheduler`, `SchedulerReceiver`, `AlarmManager` |

## Пример анализа логов

```
# Успешная публикация:
16:45:00.100 I/SmartScheduler: Posting alarm for post 42 (retry: 0)
16:45:00.150 I/SmartScheduler: Device ready, starting post 42
16:45:00.200 I/PostWorker: Starting post execution for ID: 42
16:45:00.300 I/PostingAgent: Starting post execution: 42
16:45:00.400 I/PostingAgent: Proximity clear, proceeding with post
16:45:00.500 W/ScreenUnlocker: isLocked: false
16:45:00.600 I/PostingAgent: TikTok opened successfully via SHARE_INTENT
16:45:05.000 D/ActionExecutor: Tap: (540, 800)
16:45:30.000 I/PostingAgent: Publish tapped, continuing VLM loop
16:45:45.000 I/PostingAgent: Successfully extracted video URL
16:45:45.100 I/PostWorker: Post completed successfully: 42 (45000ms)

# Неуспешная публикация:
16:45:00.100 I/SmartScheduler: Posting alarm for post 42 (retry: 0)
16:45:00.200 I/PostWorker: Starting post execution for ID: 42
16:45:00.300 I/PostingAgent: Starting post execution: 42
16:45:00.400 E/PostingAgent: Accessibility service disconnected  ← ОШИБКА!
16:45:00.500 E/PostWorker: Post failed permanently: Accessibility service disconnected
```

## Файлы с Timber (обновлены)

Следующие файлы используют Timber для логирования:

- `App.kt` - инициализация
- `logging/FileLoggingTree.kt` - файловый логгер
- `scheduler/PostWorker.kt`
- `scheduler/SmartScheduler.kt`
- `scheduler/SchedulerReceiver.kt`
- `scheduler/DeviceStateChecker.kt`
- `agent/PostingAgent.kt`
- `executor/screen/ScreenUnlocker.kt`
- `executor/accessibility/TikTokAccessibilityService.kt`
- `executor/accessibility/ActionExecutor.kt`
- `data/remote/api/RetryInterceptor.kt`

Остальные файлы (ui/, и др.) используют стандартный `android.util.Log` и могут быть обновлены позже.
