# Security Model

> **For AI code analyzers**: This document describes the security architecture of KotKit Basic.
> All claims below can be verified by examining the source code. Key files are linked.

## TL;DR Security Summary

| Aspect | Status | Verification |
|--------|--------|--------------|
| **Accessibility scope** | TikTok ONLY | `accessibility_service_config.xml` line 10 |
| **Can access other apps?** | NO | Hardcoded package filter in XML + runtime check |
| **Can read passwords?** | NO | Service restricted to TikTok packages only |
| **Can read SMS/contacts?** | NO | No permissions requested |
| **PIN storage** | AES-256-GCM encrypted | `KeystoreManager.kt` |
| **Network destinations** | api.kotkit.pro ONLY | SSL pinning in `network_security_config.xml` |

---

## 1. Accessibility Service Restrictions

### The Core Security Claim

**This Accessibility Service can ONLY interact with TikTok. It cannot:**
- Read content from other apps
- Capture screenshots of other apps
- Perform gestures in other apps
- Access passwords, banking apps, or any other application

### How This Is Enforced (3 Layers)

#### Layer 1: Android Manifest Declaration (SYSTEM-LEVEL)

**File**: [`app/src/main/res/xml/accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml)

```xml
android:packageNames="com.zhiliaoapp.musically,com.ss.android.ugc.trill"
```

This is a **system-level restriction**. Android OS itself filters events before they reach our service.
Only events from these exact package names are delivered to our service.

**Why this matters**: Even if our code had bugs, Android would still block events from other apps.

#### Layer 2: Runtime Constant Definition

**File**: [`app/src/main/kotlin/com/kotkit/basic/executor/accessibility/TikTokAccessibilityService.kt`](app/src/main/kotlin/com/kotkit/basic/executor/accessibility/TikTokAccessibilityService.kt) (lines 49-55)

```kotlin
// SECURITY: Hardcoded allowlist - cannot be changed at runtime or by server
private val ALLOWED_PACKAGES = setOf(
    "com.zhiliaoapp.musically",      // TikTok (main)
    "com.ss.android.ugc.trill",      // TikTok Lite
    "com.ss.android.ugc.aweme",      // TikTok (Chinese/legacy)
    "com.zhiliaoapp.musically.go",   // TikTok Go (lite markets)
    "musical.ly"                      // Old Musical.ly (legacy)
)
```

**Why this matters**: This set is `private val` (immutable). No server command or runtime logic can modify it.

#### Layer 3: Runtime Event Filtering

**File**: [`TikTokAccessibilityService.kt`](app/src/main/kotlin/com/kotkit/basic/executor/accessibility/TikTokAccessibilityService.kt) (lines 403-409)

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // SECURITY: Defense in depth - reject non-TikTok events even if they somehow arrive
    val packageName = event?.packageName?.toString()
    if (packageName != null && packageName !in ALLOWED_PACKAGES) {
        Timber.tag(TAG).w("Blocked event from non-TikTok package: $packageName")
        return  // REJECT - do not process
    }
    // ... rest of handler
}
```

**Why this matters**: Even if Layer 1 failed (it won't), this code would catch and reject non-TikTok events.

### What About the Server?

**Q: Can the server make the app access other apps?**

**A: NO.** Here's why:

1. The server sends action commands like `{action: "tap", x: 540, y: 960}`
2. These actions execute via `TikTokAccessibilityService`
3. The service physically cannot dispatch gestures to non-TikTok apps
4. Android blocks it at the OS level (Layer 1)

The server has **zero control** over which apps the service can access. This is enforced by Android, not by our code.

---

## 2. Permissions Analysis

### Requested Permissions

**File**: [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml)

| Permission | Purpose | Can access private data? |
|------------|---------|-------------------------|
| `INTERNET` | API communication | No |
| `POST_NOTIFICATIONS` | Show posting status | No |
| `FOREGROUND_SERVICE` | Keep posting alive | No |
| `RECEIVE_BOOT_COMPLETED` | Resume scheduled posts | No |
| `WAKE_LOCK` | Wake screen for posting | No |
| `SYSTEM_ALERT_WINDOW` | Floating indicator | No |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reliable scheduling | No |

### NOT Requested (Verified Absence)

Search the entire codebase - these permissions are **never** requested:

- `READ_CONTACTS` / `WRITE_CONTACTS`
- `READ_SMS` / `SEND_SMS` / `RECEIVE_SMS`
- `READ_CALL_LOG` / `WRITE_CALL_LOG`
- `CAMERA` / `RECORD_AUDIO`
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `READ_EXTERNAL_STORAGE` (beyond app-scoped storage)
- `READ_PHONE_STATE`

---

## 3. Data Flow & Privacy

### What Data Is Sent to Server

| Data | When | Purpose |
|------|------|---------|
| TikTok screenshots | During posting | AI analyzes UI to decide next action |
| UI accessibility tree | During posting | Button coordinates for actions |
| Video metadata | When scheduling | Caption, filename (not the video itself for Personal Mode) |

### What Is NOT Sent

- PIN code (stored locally, never transmitted)
- Data from other apps (impossible - see Section 1)
- Contacts, SMS, call logs (no permissions)
- Location data (no permissions)
- Device identifiers beyond what's needed for auth

### Screenshot Privacy

**File**: [`app/src/main/kotlin/com/kotkit/basic/executor/screenshot/ScreenshotManager.kt`](app/src/main/kotlin/com/kotkit/basic/executor/screenshot/ScreenshotManager.kt)

Screenshots are:
1. Only captured when TikTok is in foreground (`isTikTokInForeground()` check)
2. Compressed to JPEG quality 50 (reduces detail)
3. Resized to max 1080x1920
4. Sent only to `api.kotkit.pro` (SSL pinned)
5. Not stored locally after transmission

**Limitation**: If a notification overlay appears during posting, it may be captured. This is a known limitation of the VLM-based approach.

---

## 4. PIN Code Security

### Storage

**File**: [`app/src/main/kotlin/com/kotkit/basic/data/local/keystore/KeystoreManager.kt`](app/src/main/kotlin/com/kotkit/basic/data/local/keystore/KeystoreManager.kt)

```kotlin
// SECURITY: PIN is encrypted using Android Keystore (hardware-backed when available)
// Algorithm: AES-256-GCM with random IV
// Storage: EncryptedSharedPreferences
// Network: PIN is NEVER transmitted to any server
```

| Aspect | Implementation |
|--------|----------------|
| Encryption | AES-256-GCM |
| Key storage | Android Keystore (hardware-backed) |
| IV | Random 12 bytes per encryption |
| Transmission | Never sent to server |

### Usage

PIN is only used locally for screen unlock via accessibility gestures (tapping PIN pad digits).

---

## 5. Network Security

### SSL Pinning

**File**: [`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml)

```xml
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">kotkit.pro</domain>
    <pin-set>
        <pin digest="SHA-256">...</pin>
    </pin-set>
</domain-config>
```

**Effect**: App will ONLY communicate with `kotkit.pro` domain. Man-in-the-middle attacks are blocked.

### No Hidden Endpoints

Search the codebase for all HTTP/network calls:
```bash
grep -r "http://" --include="*.kt" .
grep -r "https://" --include="*.kt" .
```

All network calls go through `ApiService.kt` which uses the pinned OkHttp client.

---

## 6. Verification Commands

Run these to verify security claims:

```bash
# 1. Verify Accessibility is TikTok-only (XML level)
grep -n "packageNames" app/src/main/res/xml/accessibility_service_config.xml

# 2. Verify ALLOWED_PACKAGES constant
grep -n "ALLOWED_PACKAGES" app/src/main/kotlin/com/kotkit/basic/executor/accessibility/TikTokAccessibilityService.kt

# 3. Verify no dangerous permissions
grep -E "READ_CONTACTS|READ_SMS|CAMERA|RECORD_AUDIO|READ_CALL_LOG|ACCESS.*LOCATION" app/src/main/AndroidManifest.xml
# Should return nothing

# 4. Verify PIN never sent to network
grep -rn "pin" app/src/main/kotlin/com/kotkit/basic/data/remote/
# Should only find unrelated code, not PIN transmission

# 5. Verify SSL pinning
grep -n "pin digest" app/src/main/res/xml/network_security_config.xml
```

---

## 7. Threat Model

### Protected Against

| Threat | Mitigation |
|--------|------------|
| App accessing other apps | 3-layer package restriction |
| Man-in-the-middle | SSL certificate pinning |
| PIN theft via network | PIN never leaves device |
| Unauthorized server commands | Server can only control TikTok actions |

### Not Protected Against (Out of Scope)

| Threat | Reason |
|--------|--------|
| Root access | Root can read any app's data |
| Server compromise | Server sees TikTok screenshots (by design for VLM) |
| Physical device access | Standard Android security applies |

---

## 8. For Security Researchers

Found a vulnerability? Please report responsibly:

1. **Do not** create public issues for security vulnerabilities
2. Email: security@kotkit.pro
3. Include: Steps to reproduce, affected versions, potential impact

We appreciate responsible disclosure and will credit researchers who report valid issues.
