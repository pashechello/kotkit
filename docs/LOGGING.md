# Logging in KotKit

## 📐 Production Logging Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Android Device (Worker)                            │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  Application Layer (51 files with Timber logging)              │    │
│  │   - NetworkTaskWorker, VideoDownloader, PostingAgent          │    │
│  │   - HeartbeatWorker, FCMService, ScreenUnlocker               │    │
│  └─────────────────────────┬──────────────────────────────────────┘    │
│                             │ Timber.tag(TAG).i/w/e()                    │
│                             ▼                                            │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  FileLoggingTree (Single-thread async executor)                │    │
│  │   - Daily rotation: kotkit_YYYY-MM-DD.log                     │    │
│  │   - 7-day local retention                                      │    │
│  │   - Location: /sdcard/Android/data/com.kotkit.basic/files/logs│    │
│  │   - Format: TIMESTAMP LEVEL/TAG: MESSAGE [STACK_TRACE]        │    │
│  └─────────────────────────┬──────────────────────────────────────┘    │
│                             │ Write to disk (8KB buffer)                 │
│                             ▼                                            │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  Daily Log Files (UTF-8 plain text)                            │    │
│  │   - kotkit_2026-02-14.log (up to 5MB)                         │    │
│  │   - ~3,300 entries per task (Download: 3,255 | Posting: 51)  │    │
│  └─────────────────────────┬──────────────────────────────────────┘    │
│                             │                                            │
│                             ▼                                            │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  LogUploader (Triggered by 3 events)                           │    │
│  │   1. App start: yesterday + today logs                         │    │
│  │   2. Every 15 min: periodic (LogUploadWorker)                 │    │
│  │   3. After task execution: immediate (NetworkTaskWorker)      │    │
│  │                                                                │    │
│  │  Rate limit: 10 uploads/hour per worker                       │    │
│  │  Max size: 5MB per file                                       │    │
│  │  Deduplication: SharedPreferences tracking                    │    │
│  └─────────────────────────┬──────────────────────────────────────┘    │
│                             │ POST /api/v1/logs/upload (multipart)      │
└─────────────────────────────┼──────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        Backend (Fly.io)                                 │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  FastAPI /logs/upload endpoint                                 │    │
│  │   - Auth: JWT token → worker_id resolution                    │    │
│  │   - Validation: date format, size (max 5MB), rate limit       │    │
│  │   - Max 7 days old, not in future                             │    │
│  └─────────────────────────┬──────────────────────────────────────┘    │
│                             │ aioboto3.put_object()                      │
│                             ▼                                            │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  Cloudflare R2 (S3-compatible)                                 │    │
│  │   Path: logs/{worker_id}/{YYYY-MM-DD}.log                     │    │
│  │   Retention: 7 days (auto-cleanup job)                        │    │
│  │   Bucket: kotkit-videos                                       │    │
│  └─────────────────────────┬──────────────────────────────────────┘    │
│                             │                                            │
│                             ▼                                            │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  Admin API Endpoints (Production Debugging)                    │    │
│  │                                                                │    │
│  │  GET /admin/tasks/{task_id}                                   │    │
│  │    → Task info (worker_id, status, error_message, timing)     │    │
│  │                                                                │    │
│  │  GET /admin/logs/by-task/{task_id}  ⭐ Most used!             │    │
│  │    → All logs for task (auto-fetches across multiple days)    │    │
│  │    → Filters only lines with task_id                          │    │
│  │    → Concurrent S3 fetching (7x faster than sequential)       │    │
│  │                                                                │    │
│  │  GET /admin/workers/by-username/{username}                    │    │
│  │    → Convert TikTok username → worker_id + links              │    │
│  │                                                                │    │
│  │  GET /admin/logs/{worker_id}/{date}?tail=N&search=TEXT        │    │
│  │    → Logs for specific day with filtering                     │    │
│  │                                                                │    │
│  │  GET /admin/logs?worker_id={uuid}&days=7                      │    │
│  │    → List available log files                                 │    │
│  └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘

Legend:
  ──→  Data flow
  ┌─┐  Component boundary
  ⭐   Most frequently used endpoint
```

### Key Features

**On Device:**
- ✅ **3,300+ log entries per task** (full lifecycle coverage)
- ✅ **Automatic daily rotation** (kotkit_YYYY-MM-DD.log)
- ✅ **7-day local retention** (auto-cleanup)
- ✅ **Async file writing** (non-blocking, 8KB buffer)
- ✅ **Timing metrics** (⏱️ Screen unlock: 6.3s, VLM API: 8.5s, etc.)

**Cloud Storage (R2):**
- ✅ **Automatic upload** every 15 min + after each task
- ✅ **Deduplication** (SharedPreferences tracking)
- ✅ **Rate limiting** (10 uploads/hour to prevent abuse)
- ✅ **7-day retention** (matches local retention)

**Admin API:**
- ✅ **ONE-click debugging** (`/by-task/{id}` → all logs instantly)
- ✅ **Concurrent fetching** (7x faster than sequential S3 calls)
- ✅ **UTC-aware** (correct dates for tasks near midnight)
- ✅ **Secure** (UUID validation, admin-only access)

### Debugging Workflow (30 seconds)

```bash
# 1. Task failed → get task info
curl /admin/tasks/{task_id}
# → worker_id, error_message, status

# 2. Get ALL logs for task
curl /admin/logs/by-task/{task_id}?tail=1000
# → Full timeline from claim → completion/failure

# Done! Root cause identified.
```

**Before:** 3 steps (SQL + timestamp conversion + curl) = 5 min
**Now:** 2 API calls = 30 sec

---

## Quick Reference (ADB)

```bash
# Read today's logs directly from device
adb shell "cat /sdcard/Android/data/com.kotkit.basic/files/logs/kotkit_$(date +%Y-%m-%d).log"

# Last 100 lines
adb shell "cat /sdcard/Android/data/com.kotkit.basic/files/logs/kotkit_$(date +%Y-%m-%d).log" | tail -100

# Filter by keyword
adb shell "cat /sdcard/Android/data/com.kotkit.basic/files/logs/kotkit_$(date +%Y-%m-%d).log" | grep -i "caption"

# Pull all logs to local machine
adb pull /sdcard/Android/data/com.kotkit.basic/files/logs/ ./logs/
```

Key tags: `NewPostVM`, `PostingAgent`, `PostWorker`, `NetworkWorkerService`, `NetworkTaskExecutor`, `VideoDownloader`, `HeartbeatWorker`, `RetryInterceptor`

---

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

### Через API (удалённые устройства)

Устройства автоматически загружают логи на сервер каждые 30 мин + после каждой задачи.

```bash
# Список доступных логов для воркера
fly ssh console --app kotkit-app -C "curl -s -H 'Authorization: Bearer <admin_token>' http://localhost:8080/api/v1/admin/logs?worker_id=<uuid>"

# Получить логи (последние 200 строк с ERROR)
fly ssh console --app kotkit-app -C "curl -s -H 'Authorization: Bearer <admin_token>' 'http://localhost:8080/api/v1/admin/logs/<worker_id>/2026-02-09?tail=200&search=ERROR'"
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

## Ключевые теги для отладки

### Personal Mode (автопубликация)

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

### Worker Mode (сетевые задачи)

| Тег | Описание |
|-----|----------|
| `NetworkWorkerService` | Foreground сервис воркера |
| `NetworkTaskWorker` | Выполнение сетевых задач |
| `NetworkTaskExecutor` | Ядро выполнения (скачивание → постинг → верификация) |
| `VideoDownloader` | Скачивание видео с R2 |
| `HeartbeatWorker` | Heartbeat каждые 5 мин |
| `TaskAcceptWorker` | Принятие зарезервированных задач |
| `TaskFetchWorker` | Получение доступных задач |
| `VerificationWorker` | Верификация опубликованных видео |
| `WorkerStateManager` | Управление состоянием воркера |
| `ErrorReporter` | Отправка ошибок на бэкенд |
| `FCMService` | Push-уведомления (Firebase) |
| `LogUploader` | Загрузка логов на сервер |

### Общие

| Тег | Описание |
|-----|----------|
| `RetryInterceptor` | Сетевые запросы и retry |
| `TokenAuthenticator` | Обновление JWT токена |
| `RemoteConfigManager` | Удалённая конфигурация |

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

## Архитектура логирования

**Все** файлы в проекте используют `Timber` для логирования. Единственное исключение — `FileLoggingTree.kt`, который использует `android.util.Log` напрямую (чтобы избежать бесконечной рекурсии).

Всего 59 файлов с Timber, 50 из них используют `companion object { private const val TAG = "..." }`.

---

## Production Debugging API

Для удобной отладки production issues доступны специальные admin endpoints.

### 🔍 Быстрый поиск по task_id

Самый частый сценарий: задача failed, нужно посмотреть логи.

```bash
# Получить все логи для задачи одним запросом (!!!)
curl -H "Authorization: Bearer <admin_token>" \
  "https://kotkit.pro/api/v1/admin/logs/by-task/{task_id}?tail=500"

# Пример
curl -H "Authorization: Bearer <token>" \
  "https://kotkit.pro/api/v1/admin/logs/by-task/ec0082a1-d653-41dd-97fd-8a5c8829f245?tail=1000"
```

**Что делает этот endpoint:**
1. Находит задачу в БД → получает worker_id
2. Определяет период (created_at → completed_at)
3. Скачивает логи за **все дни** (если задача длилась 2+ дня)
4. Фильтрует только строки с `task_id`
5. Возвращает объединённый результат

**Ответ:**
```json
{
  "task_id": "ec0082a1-...",
  "worker_id": "b159ad63-...",
  "worker_username": "yadishanti",
  "date_range": ["2026-02-13", "2026-02-14"],
  "total_lines": 3301,
  "returned_lines": 500,
  "content": "2026-02-14 08:00:47 I/NetworkTaskRepository: Claimed task...\n..."
}
```

### 📋 Получить информацию о задаче

```bash
# Быстро узнать статус, worker_id, даты выполнения
curl -H "Authorization: Bearer <token>" \
  "https://kotkit.pro/api/v1/admin/tasks/{task_id}"
```

**Ответ:**
```json
{
  "id": "ec0082a1-...",
  "worker_id": "b159ad63-...",
  "worker_username": "yadishanti",
  "status": "completed",
  "created_at": 1771046084,
  "completed_at": 1771046301,
  "tiktok_video_id": "post_1771046301162",
  "error_message": null,
  "log_date": "2026-02-14",
  "log_url": "/api/v1/admin/logs/by-task/ec0082a1-..."
}
```

### 👤 Найти worker_id по username

```bash
# Конвертировать TikTok username → worker UUID
curl -H "Authorization: Bearer <token>" \
  "https://kotkit.pro/api/v1/admin/workers/by-username/yadishanti"
```

**Ответ:**
```json
{
  "id": "b159ad63-760c-4c0e-b717-0e540d65d634",
  "tiktok_username": "yadishanti",
  "is_active": true,
  "total_tasks": 42,
  "success_rate": 85.7,
  "last_active_at": 1771084473,
  "logs_url": "/api/v1/admin/logs?worker_id=b159ad63-...&days=7",
  "tasks_url": "/api/v1/admin/workers/b159ad63-.../tasks"
}
```

### 📁 Получить логи воркера за конкретный день

```bash
# Скачать логи за день с фильтрацией
curl -H "Authorization: Bearer <token>" \
  "https://kotkit.pro/api/v1/admin/logs/{worker_id}/2026-02-14?tail=200&search=ERROR"
```

**Параметры:**
- `tail` - последние N строк (max 10,000)
- `search` - фильтр по подстроке (case-insensitive)

### 📊 Список доступных логов

```bash
# Список логов за последние N дней
curl -H "Authorization: Bearer <token>" \
  "https://kotkit.pro/api/v1/admin/logs?worker_id={uuid}&days=7"
```

**Ответ:**
```json
{
  "worker_id": "b159ad63-...",
  "logs": [
    {
      "date": "2026-02-14",
      "size_bytes": 4369817,
      "s3_key": "logs/b159ad63-.../2026-02-14.log",
      "last_modified": "2026-02-14T15:54:53Z"
    },
    {
      "date": "2026-02-13",
      "size_bytes": 946489,
      "s3_key": "logs/b159ad63-.../2026-02-13.log",
      "last_modified": "2026-02-13T20:05:10Z"
    }
  ]
}
```

---

## Типичные сценарии отладки

### Сценарий 1: Задача failed, нужны логи

```bash
# ШАГ 1: Получить info о задаче
curl https://kotkit.pro/api/v1/admin/tasks/ec0082a1-d653-41dd-97fd-8a5c8829f245

# ШАГ 2: Получить все логи одним запросом
curl https://kotkit.pro/api/v1/admin/logs/by-task/ec0082a1-d653-41dd-97fd-8a5c8829f245?tail=1000
```

**Результат:** Полная timeline задачи от claim до completion/failure.

### Сценарий 2: Воркер жалуется на проблему, знаем только username

```bash
# ШАГ 1: Найти worker_id по username
curl https://kotkit.pro/api/v1/admin/workers/by-username/yadishanti
# → {"id": "b159ad63-...", "logs_url": "..."}

# ШАГ 2: Скачать логи за сегодня с фильтрацией
curl "https://kotkit.pro/api/v1/admin/logs/b159ad63-.../2026-02-14?search=ERROR&tail=100"
```

### Сценарий 3: Проверить, почему задача не выполнилась

```bash
# Получить логи с поиском конкретного события
curl "https://kotkit.pro/api/v1/admin/logs/by-task/{task_id}?tail=2000" | grep -i "downloading\|posting\|failed"
```

### Сценарий 4: Анализ ошибок воркера за последнюю неделю

```bash
# Список доступных логов
curl "https://kotkit.pro/api/v1/admin/logs?worker_id={uuid}&days=7"

# Скачать каждый день и искать ошибки
for date in 2026-02-14 2026-02-13 2026-02-12; do
  curl "https://kotkit.pro/api/v1/admin/logs/{worker_id}/${date}?search=ERROR" > errors_${date}.log
done
```

---

## Статистика логирования

Для одной задачи KotKit генерирует **~3,300 лог-записей** (пример):

| Этап | Кол-во записей | Ключевые события |
|------|----------------|-----------------|
| **Scheduling** | 5 | Claim, scheduling, delay calculation |
| **Download** | 3,255 | Presigned URL, progress (каждый chunk!), verification |
| **Screen Wait** | 1 | WAITING_SCREEN_OFF state |
| **Posting** | 51 | Screen unlock (6.3s), TikTok launch, 4 VLM steps |
| **Verification** | 3 | Screenshot capture, proof upload |
| **Completion** | 7 | Sync to server, final state |
| **Heartbeat** | 12 | Регулярные heartbeats каждые ~15 мин |

**Total:** 3,301 запись на одну задачу = полная восстанавливаемость timeline.

---

## Получение admin token

Для доступа к admin endpoints нужен токен администратора:

```bash
# Login (требуется admin роль в БД)
curl -X POST https://kotkit.pro/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@kotkit.pro", "password": "..."}'

# Ответ:
# {"access_token": "eyJ...", "token_type": "bearer", ...}
```

Токен действителен **7 дней**. Используйте его в заголовке:
```bash
Authorization: Bearer eyJ...
```

---

## Устранение проблем

### "Log file not found" (404)

**Причина:** Логи ещё не загружены в R2.

**Решение:**
1. Проверьте `last_active_at` воркера (должен быть недавний)
2. Попробуйте другую дату (логи за сегодня могут ещё не загрузиться)
3. Скачайте логи напрямую через ADB (если есть доступ к устройству)

### "Task has no assigned worker"

**Причина:** Задача ещё не назначена воркеру (статус `pending` или `reserved`).

**Решение:** Дождитесь, пока задача будет принята воркером, или проверьте статус через `GET /admin/tasks/{task_id}`.

### Логи слишком большие (>5MB)

**Причина:** Production build пишет DEBUG логи (неоптимально).

**Решение:** В release builds отключите DEBUG уровень в `FileLoggingTree`:
```kotlin
override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
    if (!BuildConfig.DEBUG && priority < Log.INFO) return  // Skip DEBUG logs in production
    // ...
}
```

Это сократит размер логов на ~85%.
