package com.autoposter.adb.pairing

import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

/**
 * SPAKE2 protocol parameters for ADB Wireless Debugging.
 *
 * ADB uses SPAKE2 over P-256 (secp256r1) curve, NOT ED25519.
 * The M and N points are ADB-specific constants derived from
 * "ADB SPAKE2 M" and "ADB SPAKE2 N" strings.
 *
 * Uses BouncyCastle's ECPoint for constant-time scalar multiplication,
 * preventing timing side-channel attacks.
 */
object Spake2Parameters {

    // Get P-256 curve from BouncyCastle (uses constant-time operations)
    private val curveSpec: ECNamedCurveParameterSpec =
        ECNamedCurveTable.getParameterSpec("secp256r1")

    val curve: ECCurve = curveSpec.curve

    // P-256 (secp256r1) curve parameters (for validation)
    val P: BigInteger = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
        16
    )

    // Curve order n
    val N: BigInteger = curveSpec.n

    // Curve coefficients a = -3, b (for point validation)
    val A: BigInteger = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC",
        16
    )

    val B: BigInteger = BigInteger(
        "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
        16
    )

    // ADB SPAKE2 M point (derived from "ADB SPAKE2 M")
    // These are the actual values used by Android's ADB implementation
    private val MX: BigInteger = BigInteger(
        "886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f",
        16
    )
    private val MY: BigInteger = BigInteger(
        "5ff355163e43ce224e0b0e65ff02ac8e5c7be09419c785e0ca547d55a12e2d20",
        16
    )

    // ADB SPAKE2 N point (derived from "ADB SPAKE2 N")
    private val NX: BigInteger = BigInteger(
        "d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49",
        16
    )
    private val NY: BigInteger = BigInteger(
        "07d60aa6bfade45008a636337f5168c64d9bd36034808cd564490b1e656edbe7",
        16
    )

    // ADB-specific info strings
    const val SPAKE2_INFO = "adb pairing_auth adb"

    // HKDF info strings for key derivation
    const val HKDF_KEY_INFO = "adb pairing_encryption key"
    const val HKDF_IV_INFO = "adb pairing_encryption iv"

    // Encryption parameters
    const val KEY_SIZE = 16  // AES-128
    const val IV_SIZE = 12   // GCM nonce size
    const val TAG_SIZE = 16  // GCM tag size

    // Password derivation
    const val SCRYPT_N = 16384
    const val SCRYPT_R = 8
    const val SCRYPT_P = 1
    const val SCRYPT_DK_LEN = 32

    /**
     * Get base point G as BouncyCastle ECPoint.
     * BouncyCastle's multiply() is constant-time.
     */
    fun getBasePoint(): ECPoint = curveSpec.g

    /**
     * Get M point as BouncyCastle ECPoint.
     */
    fun getMPoint(): ECPoint = curve.createPoint(MX, MY)

    /**
     * Get N point as BouncyCastle ECPoint.
     */
    fun getNPoint(): ECPoint = curve.createPoint(NX, NY)
}
