# KotKit Basic

Android application for automated video posting to TikTok. Version for individual creators and bloggers.

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

📚 Подробнее: [docs/network/README.md](../docs/network/README.md)

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

KotKit Basic uses Android Accessibility Service to automate TikTok video publishing:

1. **Unlock Screen** - Automatically unlocks the device if needed
2. **Launch TikTok** - Opens TikTok via share intent with the video
3. **AI-Guided Navigation** - Backend VLM analyzes screenshots and provides actions
4. **Add Caption** - Enters the caption and hashtags
5. **Publish** - Taps the publish button and verifies success
6. **Extract Link** - Copies the published video URL for tracking

All AI/VLM processing happens on the backend server - the mobile app only sends screenshots and executes UI actions.

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
