# Technical Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              ANDROID APP                                    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      USER INTERFACE                                  │   │
│  │  HomeScreen → ScheduleScreen → QueueScreen → SettingsScreen         │   │
│  └──────────────────────────────────┬──────────────────────────────────┘   │
│                                     │                                      │
│                                     ▼                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    ACCESSIBILITY SERVICE                            │   │
│  │  - Screenshot capture (MediaProjection)                             │   │
│  │  - UI Tree extraction (AccessibilityNodeInfo)                       │   │
│  │  - Action dispatch (dispatchGesture / AdvancedExecutor)             │   │
│  └──────────────────────────────────┬──────────────────────────────────┘   │
│                                     │                                      │
│                    ┌────────────────┴────────────────┐                     │
│                    ▼                                 ▼                     │
│  ┌──────────────────────────┐      ┌──────────────────────────────────┐   │
│  │     BASIC EXECUTOR       │      │      ADVANCED EXECUTOR           │   │
│  │     (Accessibility)      │      │      (Privileged Server)         │   │
│  │                          │      │                                  │   │
│  │  dispatchGesture()       │      │  LocalSocket IPC                 │   │
│  │  + Gaussian jitter       │      │  + Full humanizer:               │   │
│  │  + Random delays         │      │    • Pressure curve              │   │
│  │                          │      │    • Touch size                  │   │
│  │  No setup required       │      │    • Micro-movements             │   │
│  │  Android 7.0+            │      │                                  │   │
│  └──────────────────────────┘      │  Requires Wireless Debugging     │   │
│                                    │  Android 11+ for wireless pair   │   │
│                                    └──────────────────────────────────┘   │
│                                                   ▲                       │
│                                                   │ LocalSocket           │
│                                                   │ IPC + Auth            │
│  ┌────────────────────────────────────────────────┴────────────────────┐  │
│  │                    PRIVILEGED SERVER (UID 2000)                     │  │
│  │                                                                     │  │
│  │  Started via: app_process (through ADB shell)                       │  │
│  │  Runs in background until reboot/shutdown                           │  │
│  │  Has access to /dev/input/eventX                                    │  │
│  │  Accepts commands via LocalServerSocket                             │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                     ▲                                     │
│                                     │ ADB shell command                   │
│  ┌──────────────────────────────────┴──────────────────────────────────┐  │
│  │                         ADB CLIENT                                  │  │
│  │                                                                     │  │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐ │  │
│  │  │  mDNS Discovery │  │  SPAKE2 Pairing │  │  TLS Connection     │ │  │
│  │  │  NsdManager     │  │  6-digit code   │  │  ADB Protocol       │ │  │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────────┘ │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

## Component Details

### 1. ADB Wireless Pairing

#### Flow
```
┌──────────┐     ┌─────────────┐     ┌──────────────┐
│  User    │     │   App       │     │ ADB Daemon   │
└────┬─────┘     └──────┬──────┘     └──────┬───────┘
     │                  │                   │
     │ Enable Wireless  │                   │
     │ Debugging        │                   │
     │ ─────────────────┼──────────────────>│
     │                  │                   │
     │ Open "Pair with  │                   │
     │ pairing code"    │                   │
     │ ─────────────────┼──────────────────>│
     │                  │                   │
     │                  │<── mDNS broadcast │
     │                  │    _adb-tls-pair  │
     │                  │                   │
     │                  │── TCP connect ───>│
     │                  │                   │
     │                  │<─ SPAKE2 msg ────│
     │ Enter 6-digit    │                   │
     │ code in app      │                   │
     │ ────────────────>│                   │
     │                  │── SPAKE2 msg ───>│
     │                  │                   │
     │                  │<─ Encrypted ─────│
     │                  │   PeerInfo       │
     │                  │                   │
     │                  │── Encrypted ────>│
     │                  │   Certificate    │
     │                  │                   │
     │                  │ PAIRED!          │
     └──────────────────┴───────────────────┘
```

#### Key Files
- `MdnsDiscovery.kt` - mDNS service discovery
- `Spake2Client.kt` - SPAKE2 key exchange
- `Spake2Parameters.kt` - P-256 curve parameters
- `AdbPairingConnection.kt` - Pairing protocol

### 2. Privileged Server

#### Startup Sequence
```kotlin
// 1. App generates auth token
AuthTokenManager.getOrCreateToken(context)

// 2. App starts server via ADB
adbClient.shell("""
    CLASSPATH=/data/app/.../base.apk \
    app_process / \
    com.autoposter.privileged.PrivilegedServer \
    --socket=autoposter_privileged \
    --data-dir=/data/data/com.autoposter
""")

// 3. Server loads token and starts listening
// PrivilegedServer.main():
val authToken = AuthTokenManager.loadToken(dataDir)
LocalServerSocket(socketName).accept()
```

#### Command Protocol
```
┌─────────┬──────────┬─────────────────────┐
│  Type   │  Length  │      Payload        │
│ 1 byte  │ 4 bytes  │   Length bytes      │
└─────────┴──────────┴─────────────────────┘
```

| Command | Type | Payload |
|---------|------|---------|
| AUTH | 0x00 | 32-byte token |
| PING | 0x01 | (empty) |
| TAP | 0x02 | x, y, events[] |
| SWIPE | 0x03 | points[], duration, events[] |
| TEXT | 0x04 | UTF-8 string |
| GET_DEVICE_INFO | 0x05 | (empty) |
| SHUTDOWN | 0xFF | (empty) |

### 3. Input Humanization

#### Touch Event Structure
```kotlin
data class InputEvent(
    val type: Int,    // EV_ABS, EV_KEY, EV_SYN
    val code: Int,    // ABS_MT_POSITION_X, etc.
    val value: Int    // Coordinate, pressure, etc.
)
```

#### Humanization Features

| Feature | Implementation |
|---------|----------------|
| Coordinate jitter | Gaussian distribution, sigma = element_size / 6 |
| Pressure curve | Sinusoidal: 0 → max → 0 |
| Touch size | Correlated with pressure |
| Micro-movements | Random walk during touch |
| Duration | Log-normal distribution (mode ~100ms) |

#### Example Tap Sequence
```
1. EV_KEY  BTN_TOUCH     1        # Finger down
2. EV_ABS  MT_TRACKING_ID 1234   # New touch
3. EV_ABS  MT_POSITION_X  540    # X coordinate
4. EV_ABS  MT_POSITION_Y  960    # Y coordinate
5. EV_ABS  MT_PRESSURE    128    # Initial pressure
6. EV_ABS  MT_TOUCH_MAJOR 32     # Touch size
7. EV_SYN  SYN_REPORT     0      # Sync
   ... (pressure ramps up) ...
   ... (micro-movements) ...
   ... (pressure ramps down) ...
N. EV_ABS  MT_TRACKING_ID -1     # End touch
N+1. EV_KEY BTN_TOUCH    0       # Finger up
N+2. EV_SYN SYN_REPORT   0       # Sync
```

## Data Flow

### Screenshot → Action Flow
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ Screenshot  │────>│  Backend    │────>│   Action    │
│ + UI Tree   │     │   API       │     │  Response   │
└─────────────┘     └─────────────┘     └─────────────┘
      │                                       │
      │                                       ▼
      │                              ┌─────────────────┐
      │                              │ ActionDispatcher│
      │                              └────────┬────────┘
      │                                       │
      │                    ┌──────────────────┼──────────────────┐
      │                    ▼                  ▼                  ▼
      │            ┌─────────────┐    ┌─────────────┐    ┌───────────┐
      │            │BasicExecutor│    │AdvancedExec │    │ LaunchApp │
      │            │(Accessibility)│   │(Privileged) │    │  (Intent) │
      │            └─────────────┘    └─────────────┘    └───────────┘
```

### Action Response Format
```json
{
  "action": "TAP",
  "x": 540,
  "y": 960,
  "elementWidth": 200,
  "elementHeight": 80,
  "waitAfter": 500,
  "message": "Tap on 'Post' button"
}
```

## File Structure

```
app/src/main/kotlin/com/autoposter/
├── adb/
│   ├── AdbClient.kt                 # ADB connection management
│   ├── AdbConnection.kt             # TCP + TLS connection
│   ├── AdbProtocol.kt               # ADB message protocol
│   ├── ServerBootstrap.kt           # Server lifecycle
│   └── pairing/
│       ├── MdnsDiscovery.kt         # mDNS for pairing port
│       ├── WirelessPairingManager.kt # Pairing coordinator
│       ├── Spake2Client.kt          # SPAKE2 key exchange
│       ├── Spake2Parameters.kt      # P-256 curve params
│       └── AdbPairingConnection.kt  # Pairing protocol
│
├── privileged/
│   ├── PrivilegedServer.kt          # Shell-level server
│   ├── InputInjector.kt             # /dev/input writer
│   ├── ServerProtocol.kt            # IPC protocol
│   └── AuthTokenManager.kt          # Token management
│
├── executor/
│   ├── accessibility/
│   │   └── BasicExecutor.kt         # Accessibility-based
│   └── advanced/
│       ├── AdvancedExecutor.kt      # Server-based
│       └── AdvancedHumanizer.kt     # Full humanization
│
└── docs/
    ├── SECURITY.md                  # Security architecture
    └── ARCHITECTURE.md              # This file
```

## Compatibility Matrix

| Feature | Min Android | Notes |
|---------|-------------|-------|
| Basic Mode | 7.0 (API 24) | Accessibility Service only |
| Advanced Mode | 9.0 (API 28) | app_process required |
| Wireless Pairing | 11.0 (API 30) | No PC needed |
| Auto-reconnect | 13.0 (API 33) | Survives Wi-Fi changes |

## Dependencies

```kotlin
// Cryptography (for SPAKE2)
implementation("org.bouncycastle:bcprov-jdk18on:1.77")

// TLS 1.3 (for ADB connection)
implementation("org.conscrypt:conscrypt-android:2.5.2")
```

## Security Fixes Applied

| Issue | Fix | File |
|-------|-----|------|
| Timing-vulnerable EC multiply | BouncyCastle constant-time | `Spake2Client.kt` |
| No EC point validation | `curve.decodePoint()` + `isValid` | `Spake2Client.kt` |
| Fail-open server auth | Exit if no token found | `PrivilegedServer.kt` |
| Unauthenticated PING | Remove PING from `isServerAvailable()` | `AdvancedExecutor.kt` |
| Payload size attacks | Bounds checking on all payloads | `ServerProtocol.kt` |
| GCM IV reuse | Separate prefixes for each direction | `AdbPairingConnection.kt` |
