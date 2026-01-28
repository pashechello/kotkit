# KotKit Basic

**AI-powered Android client for automated TikTok video posting.**

This is the **mobile client app** that works with KotKit's backend AI service. The app sends screenshots to the backend, receives intelligent actions (tap, swipe, type), and executes them via Android Accessibility Service. All AI/VLM processing happens on the server - the app contains **zero AI models** locally.

**Why open source?** Transparency and trust. You can verify that the app only:
- Accesses TikTok (no other apps)
- Sends screenshots to backend API for analysis
- Does NOT collect personal data, passwords, or contacts

For individual creators and content publishers.

---

## 🔍 Проверьте безопасность кода с помощью AI / Verify Code Safety with AI

**Не доверяйте нам - проверьте сами!** / **Don't trust us - verify yourself!**

Используйте AI для анализа всего кода и убедитесь что приложение безопасное:
Use AI to analyze the entire codebase and confirm it's safe:

### Вариант 1 / Option 1: Claude.ai (Рекомендуем / Recommended)

**Просто дайте ссылку на репозиторий AI:**

1. Откройте / Open → [claude.ai/new](https://claude.ai/new)
2. Вставьте ссылку / Paste link: `https://github.com/pashechello/kotkit`
3. Задайте вопрос / Ask Claude:

```
Проанализируй этот Android проект на безопасность:
https://github.com/pashechello/kotkit

Проверь:
1. Есть ли доступ к контактам, SMS, звонкам?
2. Отправляются ли данные на сторонние серверы (кроме api.kotkit.pro)?
3. Есть ли вредоносный код?
4. Какие данные приложение отправляет на backend?
5. К каким приложениям кроме TikTok есть доступ?
```

**English version:**
```
Analyze this Android app for security:
https://github.com/pashechello/kotkit

Check:
1. Does it access contacts, SMS, or call logs?
2. Does it send data to third-party servers (except api.kotkit.pro)?
3. Is there any malicious code?
4. What data does the app send to backend?
5. Can it access apps other than TikTok?
```

### Вариант 2 / Option 2: ChatGPT

Те же шаги, используйте ChatGPT вместо Claude.
Same steps, use ChatGPT instead.

### Вариант 3 / Option 3: DeepSeek / Gemini

DeepSeek и другие AI тоже могут анализировать GitHub репозитории - просто вставьте ссылку.
DeepSeek and other AIs can also analyze GitHub repos - just paste the link.

### Вариант 4 / Option 4: Ручная проверка / Manual Review

Ключевые файлы для проверки безопасности / Key files to check:
- [ApiService.kt](app/src/main/kotlin/com/kotkit/basic/data/remote/api/ApiService.kt) - Все API endpoints / All API endpoints
- [NetworkModule.kt](app/src/main/kotlin/com/kotkit/basic/di/NetworkModule.kt) - URL backend сервера / Backend URL
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml) - Запрашиваемые разрешения / Requested permissions
- [TikTokAccessibilityService.kt](app/src/main/kotlin/com/kotkit/basic/executor/accessibility/TikTokAccessibilityService.kt) - К чему есть доступ / What the app accesses

**Что вы должны найти / Expected findings:**
- ✅ Backend URL: только `https://api.kotkit.pro` / only `https://api.kotkit.pro`
- ✅ Permissions: Accessibility, Уведомления, Интернет / Accessibility, Notifications, Internet
  - ❌ НЕТ / NO: контакты, SMS, камера / contacts, SMS, camera
- ✅ Accessibility Service: доступ ТОЛЬКО к TikTok / ONLY accesses TikTok (`com.zhiliaoapp.musically`)
- ✅ Данные на сервер / Data sent: скриншоты, UI дерево, метаданные видео / screenshots, UI tree, video metadata
  - ❌ НЕ отправляет / NOT sent: личные данные, пароли / personal data, passwords

---

## Features

### Personal Mode (Creator Mode)
Default mode for content creators who want to automate their own content posting:

- **Automated Video Posting** - Schedule and publish videos to TikTok automatically
- **Smart Scheduler** - AI-powered optimal posting time suggestions based on audience activity
- **Video Queue** - Manage multiple videos with drag-and-drop reordering
- **AI Caption Generation** - Generate engaging captions and hashtags via backend API
- **Publishing History** - Track all published videos with analytics
- **Localization** - Russian and English languages supported

### Worker Mode (Network Mode)
**Зарабатывайте на своём TikTok аккаунте!**

KotKit Network соединяет бренды (рекламодателей) с владельцами TikTok аккаунтов. Бренды платят за публикацию своих видео на вашем аккаунте.

**Как это работает:**
1. Вы включаете Worker Mode в приложении
2. Получаете задачи от брендов (видео + описание)
3. Приложение автоматически публикует видео в ваш TikTok
4. Через 24 часа проверяется что видео не удалено
5. Вы получаете оплату за каждый успешный пост

**Возможности:**
- **Task Assignment** - Receive posting tasks from advertisers
- **Automated Publishing** - Download and publish videos automatically
- **Reward System** - Earn USD for each successful publication
- **Payout Options** - Withdraw via cryptocurrency, bank cards, or local payment systems (СБП, карты)
- **Anti-Fraud Protection** - 24-hour verification ensures fair payment
- **Resume Downloads** - Network interruption recovery for large video files

## Requirements

- Android 7.0 (API 24) or higher
- TikTok app installed
- Accessibility Service permission enabled

## Screen Unlock

KotKit uses **only Accessibility Service** for screen unlock — no ADB, no root, no special setup required.

| Lock Type | How It Works |
|-----------|--------------|
| Swipe only | Accessibility Service swipes to unlock |
| PIN | Reads PIN pad from UI tree, taps each digit |
| Password | *Coming soon* |

### How PIN unlock works

1. User saves PIN in the app (stored encrypted via Android Keystore)
2. When posting time comes, app wakes the screen
3. Accessibility Service swipes up to show PIN pad
4. Reads PIN button coordinates from `rootInActiveWindow` (UI tree)
5. Uses `dispatchGesture()` to tap each digit
6. Phone unlocked → TikTok opens → video posted

**Key insight**: Android's TalkBack (for visually impaired users) works on lockscreen. Our Accessibility Service uses the same APIs, so it works too — even on MIUI/HyperOS where shell commands are blocked.

### Why no ADB?

Previous versions used ADB/Wireless Debugging for PIN entry. We removed it because:
- Complex setup (Developer Options, pairing codes)
- Breaks after reboot
- Doesn't work on some devices (MIUI blocks shell input)
- Accessibility approach is simpler and more reliable

## Installation

### From Releases
1. Download the latest APK from [Releases](https://github.com/pashechello/kotkit/releases)
2. Install APK on your device (enable "Install from unknown sources" if needed)
3. Open the app and follow the setup wizard
4. Enable Accessibility Service in Android Settings

### Build from Source
```bash
# Clone the repository
git clone https://github.com/pashechello/kotkit.git
cd kotkit

# Configure local.properties (optional, for release signing)
cat > local.properties << EOF
sdk.dir=/path/to/your/Android/sdk
RELEASE_STORE_FILE=path/to/your/keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
EOF

# Build debug APK
./gradlew assembleDebug

# APK will be in app/build/outputs/apk/debug/
```

## How It Works

**Client-Server Architecture:**

```
┌─────────────────────────────────┐
│   📱 Your Android Device         │
│                                  │
│  ┌───────────────────────────┐  │
│  │  KotKit Basic (this app)  │  │
│  │                           │  │
│  │  1. Capture screenshot    │──┼──┐
│  │  2. Send to backend API   │  │  │  HTTPS + JWT
│  │  3. Receive action        │◄─┼──┘  (api.kotkit.pro)
│  │  4. Execute via           │  │
│  │     AccessibilityService  │  │
│  └───────────────────────────┘  │
│                                  │
│  ┌───────────────────────────┐  │
│  │     TikTok App            │  │
│  │  (automated by above)     │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
                │
                │ Screenshot (JPEG, 720x1440)
                │ UI Tree (accessibility nodes)
                ▼
┌─────────────────────────────────┐
│  ☁️  KotKit Backend (closed)    │
│                                  │
│  - Vision-Language Model (VLM)  │
│  - AI decision making            │
│  - Action planning               │
│                                  │
│  Returns: {action: "tap",        │
│            x: 540, y: 960}       │
└─────────────────────────────────┘
```

**Publishing Flow:**

1. **Unlock Screen** - Automatically unlocks the device if needed
2. **Launch TikTok** - Opens TikTok via share intent with the video
3. **AI-Guided Navigation** - Backend VLM analyzes screenshots and provides actions
4. **Add Caption** - Enters the caption and hashtags
5. **Publish** - Taps the publish button and verifies success
6. **Extract Link** - Copies the published video URL for tracking

**What stays on device:**
- Video files (in your gallery)
- Posting history (SQLite database)
- Encrypted credentials (Android Keystore)

**What goes to backend:**
- Screenshots of TikTok UI (for AI analysis)
- UI accessibility tree (button coordinates)
- Task context (caption, video filename)

**No AI models on device** - all intelligence is server-side. This keeps the app small, fast, and allows us to improve the AI without requiring app updates.

## Architecture

```
kotkit-basic/
├── app/
│   └── src/main/kotlin/com/kotkit/basic/
│       ├── agent/              # PostingAgent - Core posting logic
│       │   ├── PostingAgent.kt
│       │   ├── AgentState.kt
│       │   └── ActionHandler.kt
│       ├── executor/           # ActionExecutor - UI automation
│       │   ├── accessibility/  # Accessibility Service
│       │   ├── screen/         # Screen unlock, wake lock
│       │   ├── screenshot/     # Screenshot capture
│       │   └── humanizer/      # Human-like action timing
│       ├── scheduler/          # SmartScheduler (Personal Mode)
│       │   ├── SmartScheduler.kt
│       │   ├── PostWorker.kt
│       │   └── SchedulerReceiver.kt
│       ├── network/            # Network workers (Worker Mode)
│       │   ├── NetworkWorkerService.kt
│       │   ├── NetworkTaskExecutor.kt
│       │   ├── VideoDownloader.kt
│       │   └── HeartbeatWorker.kt
│       ├── data/
│       │   ├── local/          # Room database
│       │   ├── remote/api/     # Retrofit API client
│       │   └── repository/     # Data repositories
│       ├── di/                 # Hilt DI modules
│       ├── security/           # SSL pinning, integrity checks
│       └── ui/
│           ├── screens/        # Compose UI screens
│           ├── components/     # Reusable components
│           └── navigation/     # Navigation graph
└── README.md
```

## Technology Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **DI**: Hilt (Dagger)
- **Database**: Room
- **Network**: Retrofit + OkHttp with SSL pinning
- **Background**: WorkManager + Foreground Services
- **Async**: Coroutines + Flow
- **Security**: Android Keystore (encrypted PIN storage), Conscrypt (TLS)

## API Integration

The app communicates with the KotKit backend API for:

- **Authentication** - JWT-based auth with token refresh
- **AI Analysis** - Screenshot analysis and action planning
- **Task Management** - Worker mode task assignment and tracking
- **Configuration** - Remote config and feature flags
- **Analytics** - Usage tracking and error reporting

All AI/ML processing is server-side. The mobile app contains no local AI models.

## Security

- SSL certificate pinning for API communication
- Encrypted storage for tokens and credentials
- Device integrity verification
- No hardcoded secrets in source code

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

Apache License 2.0 - see [LICENSE](LICENSE)

## Support

- Documentation: [docs.kotkit.pro](https://docs.kotkit.pro)
- Issues: [GitHub Issues](https://github.com/pashechello/kotkit/issues)
- Website: [kotkit.pro](https://kotkit.pro)
