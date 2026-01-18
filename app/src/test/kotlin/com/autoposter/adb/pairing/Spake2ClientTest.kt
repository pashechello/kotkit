package com.autoposter.adb.pairing

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for Spake2Client.
 *
 * Tests cover:
 * - SPAKE2 message generation
 * - EC point validation
 * - Constant-time operations (indirectly)
 */
class Spake2ClientTest {

    private lateinit var client: Spake2Client

    @Before
    fun setUp() {
        client = Spake2Client()
    }

    @After
    fun tearDown() {
        client.close()
    }

    @Test
    fun `generateClientMessage returns 65 byte uncompressed point`() {
        val message = client.generateClientMessage("123456")

        assertEquals("Message should be 65 bytes (uncompressed P-256 point)", 65, message.size)
        assertEquals("First byte should be 0x04 (uncompressed)", 0x04.toByte(), message[0])
    }

    @Test
    fun `generateClientMessage produces valid P-256 point`() {
        val message = client.generateClientMessage("123456")

        // Decode using BouncyCastle - will throw if invalid
        val point = Spake2Parameters.curve.decodePoint(message)

        assertTrue("Point should be valid on curve", point.isValid)
        assertFalse("Point should not be infinity", point.isInfinity)
    }

    @Test
    fun `different passwords produce different messages`() {
        val client1 = Spake2Client()
        val client2 = Spake2Client()

        val message1 = client1.generateClientMessage("123456")
        val message2 = client2.generateClientMessage("654321")

        assertFalse(
            "Different passwords should produce different messages",
            message1.contentEquals(message2)
        )

        client1.close()
        client2.close()
    }

    @Test
    fun `same password produces different messages due to random scalar`() {
        val client1 = Spake2Client()
        val client2 = Spake2Client()

        val message1 = client1.generateClientMessage("123456")
        val message2 = client2.generateClientMessage("123456")

        assertFalse(
            "Same password should produce different messages (random scalar)",
            message1.contentEquals(message2)
        )

        client1.close()
        client2.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `processServerMessage throws on invalid point size`() {
        client.generateClientMessage("123456")

        // Wrong size - should throw
        val invalidMessage = ByteArray(32) { 0x00 }
        client.processServerMessage(invalidMessage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `processServerMessage throws on invalid point prefix`() {
        client.generateClientMessage("123456")

        // Wrong prefix (should be 0x04 for uncompressed)
        val invalidMessage = ByteArray(65) { 0x00 }
        invalidMessage[0] = 0x02 // Compressed format prefix
        client.processServerMessage(invalidMessage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `processServerMessage throws on point not on curve`() {
        client.generateClientMessage("123456")

        // Create a point that's not on the P-256 curve
        val invalidPoint = ByteArray(65)
        invalidPoint[0] = 0x04
        // Set x and y to values that don't satisfy curve equation
        for (i in 1..32) invalidPoint[i] = 0xFF.toByte()
        for (i in 33..64) invalidPoint[i] = 0xFF.toByte()

        client.processServerMessage(invalidPoint)
    }

    @Test(expected = IllegalStateException::class)
    fun `processServerMessage throws if called before generateClientMessage`() {
        val validPoint = ByteArray(65)
        validPoint[0] = 0x04
        // This will fail because generateClientMessage was not called
        client.processServerMessage(validPoint)
    }

    @Test
    fun `deriveEncryptionKeys produces correct key sizes`() {
        // Generate a fake shared secret
        val sharedSecret = ByteArray(32) { it.toByte() }

        val (key, iv) = client.deriveEncryptionKeys(sharedSecret)

        assertEquals("Key should be ${Spake2Parameters.KEY_SIZE} bytes", Spake2Parameters.KEY_SIZE, key.size)
        assertEquals("IV should be ${Spake2Parameters.IV_SIZE} bytes", Spake2Parameters.IV_SIZE, iv.size)
    }

    @Test
    fun `deriveEncryptionKeys is deterministic`() {
        val sharedSecret = ByteArray(32) { it.toByte() }

        val (key1, iv1) = client.deriveEncryptionKeys(sharedSecret)
        val (key2, iv2) = client.deriveEncryptionKeys(sharedSecret)

        assertTrue("Same input should produce same key", key1.contentEquals(key2))
        assertTrue("Same input should produce same IV", iv1.contentEquals(iv2))
    }

    @Test
    fun `close clears secrets`() {
        client.generateClientMessage("123456")
        client.close()

        // After close, calling processServerMessage should fail
        // because internal state was cleared
        assertThrows(IllegalStateException::class.java) {
            val validPoint = Spake2Parameters.getBasePoint().getEncoded(false)
            client.processServerMessage(validPoint)
        }
    }
}

/**
 * Tests for Spake2Parameters.
 */
class Spake2ParametersTest {

    @Test
    fun `curve order N is correct for P-256`() {
        val expectedN = BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
            16
        )
        assertEquals("Curve order N should match P-256 spec", expectedN, Spake2Parameters.N)
    }

    @Test
    fun `base point G is valid`() {
        val G = Spake2Parameters.getBasePoint()

        assertTrue("Base point should be valid", G.isValid)
        assertFalse("Base point should not be infinity", G.isInfinity)
    }

    @Test
    fun `M point is valid and on curve`() {
        val M = Spake2Parameters.getMPoint()

        assertTrue("M point should be valid", M.isValid)
        assertFalse("M point should not be infinity", M.isInfinity)
    }

    @Test
    fun `N point is valid and on curve`() {
        val N = Spake2Parameters.getNPoint()

        assertTrue("N point should be valid", N.isValid)
        assertFalse("N point should not be infinity", N.isInfinity)
    }

    @Test
    fun `G, M, N are distinct points`() {
        val G = Spake2Parameters.getBasePoint()
        val M = Spake2Parameters.getMPoint()
        val N = Spake2Parameters.getNPoint()

        assertFalse("G and M should be different", G.equals(M))
        assertFalse("G and N should be different", G.equals(N))
        assertFalse("M and N should be different", M.equals(N))
    }

    @Test
    fun `scalar multiplication produces valid point`() {
        val G = Spake2Parameters.getBasePoint()
        val scalar = BigInteger("12345678901234567890")

        val result = G.multiply(scalar).normalize()

        assertTrue("Result should be valid point", result.isValid)
    }

    @Test
    fun `SCRYPT parameters match ADB spec`() {
        assertEquals("SCRYPT N", 16384, Spake2Parameters.SCRYPT_N)
        assertEquals("SCRYPT r", 8, Spake2Parameters.SCRYPT_R)
        assertEquals("SCRYPT p", 1, Spake2Parameters.SCRYPT_P)
        assertEquals("SCRYPT dkLen", 32, Spake2Parameters.SCRYPT_DK_LEN)
    }
}
