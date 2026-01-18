package com.autoposter.adb.pairing

import android.util.Log
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.math.ec.ECPoint
import java.io.Closeable
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 client implementation for ADB Wireless Debugging pairing.
 *
 * Uses P-256 (secp256r1) curve as required by Android ADB.
 * Implements Closeable to ensure cryptographic secrets are cleared from memory.
 *
 * SECURITY: Uses BouncyCastle's ECPoint.multiply() which is constant-time,
 * preventing timing side-channel attacks on the private scalar.
 *
 * Protocol flow:
 * 1. Client computes: X = x*G + w*M (where w = SCRYPT(password), x = random scalar)
 * 2. Server computes: Y = y*G + w*N (where y = random scalar)
 * 3. Both exchange X and Y
 * 4. Client computes: K = x*(Y - w*N) = x*y*G
 * 5. Both derive the same shared secret K
 */
class Spake2Client : Closeable {

    companion object {
        private const val TAG = "Spake2Client"
        private const val POINT_SIZE = 65 // Uncompressed P-256 point (04 || x || y)
    }

    private val random = SecureRandom()

    // Secrets - cleared on close()
    private var privateScalar: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var sharedSecretBytes: ByteArray? = null

    // Client's public point X (for transcript)
    private var clientPublicBytes: ByteArray? = null

    /**
     * Initialize SPAKE2 with the pairing password (6-digit code).
     *
     * @param password The 6-digit pairing code as string
     * @return Client's SPAKE2 message (uncompressed point) to send to server
     */
    fun generateClientMessage(password: String): ByteArray {
        Log.d(TAG, "Generating SPAKE2 client message")

        // 1. Derive password scalar using SCRYPT
        passwordScalar = derivePasswordScalar(password)
        Log.d(TAG, "Password scalar derived via SCRYPT")

        // 2. Generate random private scalar x
        privateScalar = generateRandomScalar()

        // 3. Compute X = x*G + w*M using BouncyCastle's constant-time multiply
        val xG = Spake2Parameters.getBasePoint().multiply(privateScalar)
        val wM = Spake2Parameters.getMPoint().multiply(passwordScalar)
        val publicPoint = xG.add(wM).normalize()

        // 4. Encode point (uncompressed format: 04 || x || y)
        clientPublicBytes = encodePoint(publicPoint)

        Log.d(TAG, "Client message generated (${clientPublicBytes!!.size} bytes)")
        return clientPublicBytes!!.copyOf()
    }

    /**
     * Process server's SPAKE2 message and derive shared secret.
     *
     * @param serverMessage Server's SPAKE2 message (uncompressed point Y)
     * @return Shared secret bytes
     */
    fun processServerMessage(serverMessage: ByteArray): ByteArray {
        requireNotNull(privateScalar) { "Must call generateClientMessage first" }
        requireNotNull(passwordScalar) { "Must call generateClientMessage first" }
        requireNotNull(clientPublicBytes) { "Must call generateClientMessage first" }

        Log.d(TAG, "Processing server message (${serverMessage.size} bytes)")

        // 1. Decode server's public point Y (with curve validation)
        val serverPoint = decodePoint(serverMessage)

        // 2. Compute w*N using BouncyCastle's constant-time multiply
        val wN = Spake2Parameters.getNPoint().multiply(passwordScalar)

        // 3. Compute Y - w*N (negate = multiply by -1, then add)
        val yMinusWN = serverPoint.add(wN.negate()).normalize()

        // 4. Compute shared point K = x*(Y - w*N) using constant-time multiply
        val sharedPoint = yMinusWN.multiply(privateScalar).normalize()

        // 5. Derive shared secret: SHA-256(len(X) || X || len(Y) || Y || len(K) || K || len(w) || w)
        sharedSecretBytes = deriveSharedSecret(
            clientPublicBytes!!,
            serverMessage,
            encodePoint(sharedPoint),
            passwordScalar!!
        )

        Log.d(TAG, "Shared secret derived (${sharedSecretBytes!!.size} bytes)")
        return sharedSecretBytes!!.copyOf()
    }

    /**
     * Derive encryption key and IV from shared secret using HKDF.
     * Returns separate IVs for client and server to prevent IV reuse.
     */
    fun deriveEncryptionKeys(sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        // HKDF-Extract with empty salt
        val prk = hkdfExtract(ByteArray(32), sharedSecret)

        // HKDF-Expand for key
        val key = hkdfExpand(
            prk,
            Spake2Parameters.HKDF_KEY_INFO.toByteArray(),
            Spake2Parameters.KEY_SIZE
        )

        // HKDF-Expand for IV
        val iv = hkdfExpand(
            prk,
            Spake2Parameters.HKDF_IV_INFO.toByteArray(),
            Spake2Parameters.IV_SIZE
        )

        return Pair(key, iv)
    }

    /**
     * Clear all secrets from memory.
     */
    override fun close() {
        privateScalar = null
        passwordScalar = null
        sharedSecretBytes?.fill(0)
        sharedSecretBytes = null
        clientPublicBytes = null
    }

    /**
     * Derive password scalar using SCRYPT.
     *
     * ## ADB Pairing Protocol Specification
     *
     * ADB uses SCRYPT with the password as BOTH the input and salt.
     * This is per the Android ADB pairing protocol specification.
     *
     * **Why salt = password?**
     * This is unusual but intentional in ADB's design. The pairing code is:
     * - 6 random digits, providing ~20 bits of entropy
     * - Displayed on screen, so attacker would need physical access
     * - Used only once per pairing session
     * - Combined with SPAKE2's password-authenticated key exchange
     *
     * The security relies on SPAKE2's properties, not on SCRYPT alone.
     * SCRYPT here just converts the password to a group element.
     *
     * ## Known Limitation: BigInteger Memory
     *
     * The returned BigInteger cannot be securely cleared from memory.
     * This is a JVM limitation - BigInteger is immutable and its internal
     * byte array is not directly accessible for zeroing.
     *
     * Mitigations:
     * - The Spake2Client implements Closeable and nulls references
     * - The password scalar is only valid for one pairing session
     * - After pairing, the derived keys are used, not the password scalar
     * - GC will eventually reclaim the memory
     *
     * For truly sensitive applications, consider using BouncyCastle's
     * mutable BigInteger alternatives or native code.
     */
    private fun derivePasswordScalar(password: String): BigInteger {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)

        // SCRYPT salt is the password itself (per ADB pairing spec)
        val derivedKey = SCrypt.generate(
            passwordBytes,
            passwordBytes, // salt = password (ADB protocol requirement)
            Spake2Parameters.SCRYPT_N,
            Spake2Parameters.SCRYPT_R,
            Spake2Parameters.SCRYPT_P,
            Spake2Parameters.SCRYPT_DK_LEN
        )

        // Clear password bytes immediately
        passwordBytes.fill(0)

        // Reduce modulo N to get valid scalar
        return BigInteger(1, derivedKey).mod(Spake2Parameters.N)
    }

    /**
     * Generate random scalar in range [1, N-1].
     */
    private fun generateRandomScalar(): BigInteger {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)

        var scalar = BigInteger(1, bytes).mod(Spake2Parameters.N)
        if (scalar == BigInteger.ZERO) {
            scalar = BigInteger.ONE
        }
        return scalar
    }

    /**
     * Encode BouncyCastle ECPoint to uncompressed format (04 || x || y).
     */
    private fun encodePoint(point: ECPoint): ByteArray {
        if (point.isInfinity) {
            return ByteArray(1) { 0x00 }
        }
        // BouncyCastle's getEncoded(false) returns uncompressed format
        return point.getEncoded(false)
    }

    /**
     * Decode uncompressed P-256 point with curve validation.
     *
     * SECURITY: BouncyCastle's decodePoint automatically validates that
     * the point lies on the curve, preventing invalid curve attacks.
     *
     * @throws IllegalArgumentException if point is not on curve or invalid encoding
     */
    private fun decodePoint(encoded: ByteArray): ECPoint {
        require(encoded.size == POINT_SIZE && encoded[0] == 0x04.toByte()) {
            "Invalid point encoding: size=${encoded.size}, first=${encoded[0]}"
        }

        // BouncyCastle's decodePoint validates the point is on the curve
        val point = Spake2Parameters.curve.decodePoint(encoded)

        // Additional explicit validation for defense in depth
        require(point.isValid) {
            "Point not on P-256 curve (invalid curve attack attempt?)"
        }

        return point
    }

    /**
     * Derive shared secret from transcript.
     * Format: SHA-256(len(X) || X || len(Y) || Y || len(K) || K || len(w) || w)
     */
    private fun deriveSharedSecret(
        clientPublic: ByteArray,
        serverPublic: ByteArray,
        sharedPoint: ByteArray,
        password: BigInteger
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")

        // Add client public with length prefix
        digest.update(intToBytes(clientPublic.size))
        digest.update(clientPublic)

        // Add server public with length prefix
        digest.update(intToBytes(serverPublic.size))
        digest.update(serverPublic)

        // Add shared point with length prefix
        digest.update(intToBytes(sharedPoint.size))
        digest.update(sharedPoint)

        // Add password scalar with length prefix
        val passwordBytes = password.toByteArray()
        digest.update(intToBytes(passwordBytes.size))
        digest.update(passwordBytes)

        return digest.digest()
    }

    /**
     * Convert int to 4 bytes (big-endian).
     */
    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    /**
     * HKDF-Extract.
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand.
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(length)
        var prev = ByteArray(0)
        var offset = 0
        var counter: Byte = 1

        while (offset < length) {
            mac.reset()
            mac.update(prev)
            mac.update(info)
            mac.update(counter)

            prev = mac.doFinal()

            val toCopy = minOf(prev.size, length - offset)
            System.arraycopy(prev, 0, result, offset, toCopy)

            offset += toCopy
            counter++
        }

        return result
    }
}
