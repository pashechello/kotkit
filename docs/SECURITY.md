# Security Architecture

## Overview

The TikTok AutoPoster uses a privileged server architecture to inject touch events directly into `/dev/input/eventX`. This requires careful security design to prevent unauthorized access.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           ANDROID DEVICE                                │
│                                                                         │
│  ┌─────────────────────┐         ┌─────────────────────────────────┐   │
│  │     Main App        │         │    Privileged Server            │   │
│  │   (App UID ~10xxx)  │         │      (Shell UID 2000)           │   │
│  │                     │         │                                 │   │
│  │  ┌───────────────┐  │  Auth   │  ┌─────────────────────────┐   │   │
│  │  │AdvancedExec   │──┼────────>│  │ LocalServerSocket       │   │   │
│  │  └───────────────┘  │  Token  │  │ "autoposter_privileged" │   │   │
│  │                     │         │  └───────────┬─────────────┘   │   │
│  │  ┌───────────────┐  │         │              │                 │   │
│  │  │AuthTokenMgr   │  │         │  ┌───────────▼─────────────┐   │   │
│  │  │ (filesDir)    │  │         │  │ InputInjector           │   │   │
│  │  └───────────────┘  │         │  │ /dev/input/eventX       │   │   │
│  │                     │         │  └─────────────────────────┘   │   │
│  └─────────────────────┘         └─────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Authentication Flow

### 1. Token Generation (App Side)

```kotlin
// AuthTokenManager.kt
val token = ByteArray(32)  // 256-bit
SecureRandom().nextBytes(token)
// Saved to: /data/data/com.autoposter/files/server_auth_token
```

### 2. Server Startup (via ADB)

```bash
CLASSPATH=/path/to/apk app_process / \
  com.autoposter.privileged.PrivilegedServer \
  --socket=autoposter_privileged \
  --data-dir=/data/data/com.autoposter
```

### 3. Server Token Loading

```kotlin
// PrivilegedServer.kt - main()
val authToken = AuthTokenManager.loadToken(dataDir)
if (authToken == null) {
    System.exit(1)  // FAIL-CLOSED: No token = no server
}
```

### 4. Client Authentication

```
Client                              Server
   │                                   │
   ├──── [CMD_AUTH + token] ──────────>│
   │                                   │ verifyToken(token)
   │<─────── [RESP_OK] ───────────────┤  (constant-time)
   │                                   │
   ├──── [CMD_TAP + data] ────────────>│
   │                                   │ (authenticated)
   │<─────── [RESP_OK] ───────────────┤
   │                                   │
```

## Security Controls

### 1. Fail-Closed Authentication

| Component | Behavior |
|-----------|----------|
| Server startup | Exits if no `--data-dir` provided |
| Server startup | Exits if token file not found |
| Client connection | `isAuthenticated = false` by default |
| Command handling | Rejects all commands until AUTH succeeds |

### 2. Rate Limiting

```kotlin
private const val MAX_AUTH_ATTEMPTS = 3
private const val SOCKET_READ_TIMEOUT_MS = 30_000
```

After 3 failed auth attempts, client is disconnected.

### 3. Resource Exhaustion Protection

```kotlin
const val MAX_COMMAND_SIZE = 1024 * 1024  // 1 MB max
const val MAX_EVENTS = 10000               // Per command
const val MAX_POINTS = 1000                // Swipe path
const val MAX_TEXT_LENGTH = 10000
```

### 4. Constant-Time Token Comparison

```kotlin
fun verifyToken(expected: ByteArray): Boolean {
    if (token.size != expected.size) return false
    var result = 0
    for (i in token.indices) {
        result = result or (token[i].toInt() xor expected[i].toInt())
    }
    return result == 0  // Timing-safe
}
```

## Threat Model

### Protected Against

| Threat | Mitigation |
|--------|------------|
| Other apps | Token in app private directory (`filesDir`) |
| Timing attacks | Constant-time token comparison |
| DoS via large payloads | Payload size limits |
| DoS via many connections | Thread pool limits (4 threads) |
| Slow loris attacks | Socket read timeout (30s) |

### Not Protected Against (By Design)

| Threat | Reason |
|--------|--------|
| Root access | Root can read anything, including token |
| ADB access | ADB already has shell privileges |
| Physical access | Out of scope; device encryption helps |

## SPAKE2 Pairing Security

### Cryptographic Primitives

| Component | Implementation |
|-----------|----------------|
| Curve | P-256 (secp256r1) |
| Scalar multiplication | BouncyCastle (constant-time) |
| Point validation | `curve.decodePoint()` + `isValid` |
| Password derivation | SCRYPT (N=16384, r=8, p=1) |
| Key derivation | HKDF-SHA256 |
| Encryption | AES-128-GCM |

### IV Reuse Prevention

```kotlin
// Different prefixes for each direction
private const val CLIENT_IV_PREFIX = 0x00000000
private const val SERVER_IV_PREFIX = 0x00000001

// IV = [prefix 4 bytes][counter 8 bytes]
val iv = ByteBuffer.allocate(12)
iv.putInt(CLIENT_IV_PREFIX)  // or SERVER_IV_PREFIX
iv.putLong(counter++)
```

### Known Limitations

1. **SCRYPT salt = password**: This is per ADB protocol spec, not a bug
2. **BigInteger memory**: Cannot be securely cleared (JVM limitation)
3. **Token storage**: Plaintext in `filesDir` (Keystore not accessible by shell UID)

## Files Changed

| File | Security Feature |
|------|------------------|
| `Spake2Parameters.kt` | BouncyCastle ECPoint for constant-time ops |
| `Spake2Client.kt` | Point validation, timing-safe multiply |
| `PrivilegedServer.kt` | Fail-closed auth, rate limiting |
| `ServerProtocol.kt` | Payload bounds checking |
| `AuthTokenManager.kt` | Token generation, security docs |
| `AdvancedExecutor.kt` | Removed unauthenticated PING |
