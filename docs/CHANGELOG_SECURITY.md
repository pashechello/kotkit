# Security Changelog

## Session: 2025-01-18 - Security Hardening

### Summary

Reviewed and fixed all security issues identified in code review for the ADB pairing and Privileged Server components.

---

## Changes by File

### 1. `Spake2Parameters.kt`

**Before:**
```kotlin
import java.security.spec.ECPoint

// Manual ECPoint (just x,y coordinates, no crypto operations)
fun getBasePoint(): ECPoint = ECPoint(GX, GY)
```

**After:**
```kotlin
import org.bouncycastle.math.ec.ECPoint

// BouncyCastle ECPoint with constant-time multiply()
private val curveSpec = ECNamedCurveTable.getParameterSpec("secp256r1")
val curve: ECCurve = curveSpec.curve

fun getBasePoint(): ECPoint = curveSpec.g
fun getMPoint(): ECPoint = curve.createPoint(MX, MY)
```

**Why:** BouncyCastle's `ECPoint.multiply()` is constant-time, preventing timing attacks on the private scalar.

---

### 2. `Spake2Client.kt`

**Change 1: Constant-time scalar multiplication**

Before:
```kotlin
// Manual double-and-add (timing vulnerable!)
private fun scalarMult(scalar: BigInteger, point: ECPoint): ECPoint {
    while (k > BigInteger.ZERO) {
        if (k.testBit(0)) {        // <-- Timing leak!
            result = pointAdd(result, current)
        }
        current = pointDouble(current)
        k = k.shiftRight(1)
    }
}
```

After:
```kotlin
// BouncyCastle constant-time
val xG = Spake2Parameters.getBasePoint().multiply(privateScalar)
val wM = Spake2Parameters.getMPoint().multiply(passwordScalar)
val publicPoint = xG.add(wM).normalize()
```

**Change 2: EC point validation**

Before:
```kotlin
private fun decodePoint(encoded: ByteArray): ECPoint {
    val x = BigInteger(1, encoded.copyOfRange(1, 33))
    val y = BigInteger(1, encoded.copyOfRange(33, 65))
    return ECPoint(x, y)  // No validation!
}
```

After:
```kotlin
private fun decodePoint(encoded: ByteArray): ECPoint {
    // BouncyCastle validates point is on curve
    val point = Spake2Parameters.curve.decodePoint(encoded)
    require(point.isValid) { "Invalid curve attack!" }
    return point
}
```

**Change 3: Documentation for known limitations**

Added comprehensive KDoc explaining:
- Why SCRYPT uses password as salt (ADB protocol requirement)
- Why BigInteger cannot be securely cleared (JVM limitation)
- Mitigations in place

---

### 3. `PrivilegedServer.kt`

**Change 1: Fail-closed startup**

Before:
```kotlin
val authToken = if (dataDir != null) {
    AuthTokenManager.loadToken(dataDir)
} else {
    null  // <-- Runs without auth!
}
```

After:
```kotlin
if (dataDir == null) {
    println("FATAL: --data-dir is required")
    System.exit(1)
}
val authToken = AuthTokenManager.loadToken(dataDir)
if (authToken == null) {
    println("FATAL: Auth token not found")
    System.exit(1)
}
```

**Change 2: Non-nullable authToken**

Before:
```kotlin
class PrivilegedServer(
    private val authToken: ByteArray?  // Nullable
)

private fun handleClient(socket: LocalSocket) {
    var isAuthenticated = (authToken == null)  // Fail-open!
}
```

After:
```kotlin
class PrivilegedServer(
    private val authToken: ByteArray  // Required
)

private fun handleClient(socket: LocalSocket) {
    var isAuthenticated = false  // Fail-closed
}
```

---

### 4. `AdvancedExecutor.kt`

**Change: Remove unauthenticated PING**

Before:
```kotlin
fun isServerAvailable(): Boolean {
    // Sends PING without authentication
    ServerProtocol.writeCommand(testOutput, ServerProtocol.CMD_PING)
    val (success, _) = ServerProtocol.readResponse(testInput)
    return success
}
```

After:
```kotlin
fun isServerAvailable(): Boolean {
    // Only checks socket connection, no commands
    val testSocket = LocalSocket()
    testSocket.connect(LocalSocketAddress(SOCKET_NAME))
    testSocket.close()
    return true
}
```

---

### 5. `AuthTokenManager.kt`

**Change: Security documentation**

Added comprehensive security model documentation explaining:
- Why Keystore encryption isn't used (shell UID can't access)
- Threat model (protects against other apps, not root)
- Threat analysis table
- Recommendations for token rotation

---

## Security Issues Status

| Issue | Priority | Status |
|-------|----------|--------|
| No EC point validation | Important | FIXED |
| Timing-vulnerable scalar mult | Important | FIXED |
| Fail-open server auth | Important | FIXED |
| isServerAvailable() bypass | Low | FIXED |
| Auth token plaintext | Low | DOCUMENTED (by design) |
| SCRYPT salt = password | Low | DOCUMENTED (ADB spec) |
| BigInteger memory | Known | DOCUMENTED (JVM limitation) |

---

## Testing Recommendations

1. **SPAKE2 pairing**
   - Test with correct 6-digit code
   - Test with wrong code (should fail)
   - Test with invalid EC points (should throw)

2. **Server authentication**
   - Test with correct token
   - Test with wrong token (should reject)
   - Test without token (should reject)
   - Test > 3 auth attempts (should disconnect)

3. **Payload validation**
   - Test with oversized payloads (should reject)
   - Test with too many events (should reject)
   - Test with malformed data (should reject)
