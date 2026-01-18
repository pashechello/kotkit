package com.autoposter.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KeyManager handles RSA key pair generation and secure storage for ADB authentication.
 *
 * ADB requires RSA-2048 keys for authentication. The public key is sent to the device
 * during connection, and the device signs a token with its private key to authenticate.
 *
 * Security:
 * - Private keys are encrypted using AES-GCM with a key stored in Android Keystore
 * - Android Keystore keys are hardware-backed on supported devices
 * - Public keys don't need encryption (they're public by definition)
 */
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "KeyManager"
        private const val KEY_ALGORITHM = "RSA"
        private const val KEY_SIZE = 2048
        private const val PRIVATE_KEY_FILE = "adb_private_key.enc"  // Encrypted
        private const val PUBLIC_KEY_FILE = "adb_public_key"

        // Android Keystore configuration
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "autoposter_adb_key_encryption"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    private var cachedKeyPair: KeyPair? = null

    /**
     * Get or create RSA key pair for ADB authentication.
     */
    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }

        // Ensure Keystore encryption key exists
        ensureKeystoreKeyExists()

        // Try to load existing keys
        val existingKeyPair = loadKeyPair()
        if (existingKeyPair != null) {
            Log.i(TAG, "Loaded existing RSA key pair")
            cachedKeyPair = existingKeyPair
            return existingKeyPair
        }

        // Generate new key pair
        Log.i(TAG, "Generating new RSA key pair")
        val newKeyPair = generateKeyPair()
        saveKeyPair(newKeyPair)
        cachedKeyPair = newKeyPair
        return newKeyPair
    }

    /**
     * Get public key in ADB format.
     * ADB expects: base64(public_key_bytes) + " " + user@host
     */
    fun getPublicKeyAdbFormat(): String {
        val keyPair = getOrCreateKeyPair()
        val publicKeyBytes = keyPair.public.encoded
        val base64Key = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)

        // ADB format: base64_key user@host\0
        val user = "autoposter"
        val host = android.os.Build.MODEL.replace(" ", "_")
        return "$base64Key $user@$host\u0000"
    }

    /**
     * Get public key bytes for ADB AUTH message.
     */
    fun getPublicKeyBytes(): ByteArray {
        val keyPair = getOrCreateKeyPair()
        return keyPair.public.encoded
    }

    /**
     * Sign data with private key (for ADB AUTH).
     */
    fun sign(data: ByteArray): ByteArray {
        val keyPair = getOrCreateKeyPair()
        val signature = java.security.Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(data)
        return signature.sign()
    }

    /**
     * Delete stored keys.
     */
    fun deleteKeys() {
        Log.i(TAG, "Deleting stored RSA keys")
        getPrivateKeyFile().delete()
        getPublicKeyFile().delete()
        cachedKeyPair = null

        // Also remove Keystore key
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Keystore key", e)
        }
    }

    /**
     * Check if keys exist.
     */
    fun hasKeys(): Boolean {
        return getPrivateKeyFile().exists() && getPublicKeyFile().exists()
    }

    /**
     * Ensure the AES key for encrypting private keys exists in Android Keystore.
     */
    private fun ensureKeystoreKeyExists() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            Log.i(TAG, "Creating new AES key in Android Keystore")

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )

            val keyGenSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Require user authentication for additional security (optional)
                // .setUserAuthenticationRequired(true)
                // .setUserAuthenticationValidityDurationSeconds(300)
                .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()

            Log.i(TAG, "AES key created in Android Keystore")
        }
    }

    /**
     * Get the AES key from Android Keystore.
     */
    private fun getKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
    }

    /**
     * Encrypt data using the Keystore-backed AES key.
     * Format: [IV (12 bytes)][encrypted data with tag]
     */
    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, getKeystoreKey())

        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)

        // Prepend IV to encrypted data
        return iv + encrypted
    }

    /**
     * Decrypt data using the Keystore-backed AES key.
     */
    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > GCM_IV_LENGTH) { "Encrypted data too short" }

        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = data.copyOfRange(GCM_IV_LENGTH, data.size)

        val cipher = Cipher.getInstance(AES_MODE)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getKeystoreKey(), gcmSpec)

        return cipher.doFinal(encrypted)
    }

    private fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        keyGen.initialize(KEY_SIZE)
        return keyGen.generateKeyPair()
    }

    private fun loadKeyPair(): KeyPair? {
        return try {
            val privateKeyFile = getPrivateKeyFile()
            val publicKeyFile = getPublicKeyFile()

            if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
                return null
            }

            // Load and decrypt private key
            val encryptedPrivateKey = Base64.decode(privateKeyFile.readText(), Base64.DEFAULT)
            val privateKeyBytes = decrypt(encryptedPrivateKey)

            // Load public key (not encrypted)
            val publicKeyBytes = Base64.decode(publicKeyFile.readText(), Base64.DEFAULT)

            val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))

            // Clear decrypted key bytes from memory
            privateKeyBytes.fill(0)

            KeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load key pair", e)
            null
        }
    }

    private fun saveKeyPair(keyPair: KeyPair) {
        try {
            val privateKeyBytes = keyPair.private.encoded

            // Encrypt private key before saving
            val encryptedPrivateKey = encrypt(privateKeyBytes)

            // Clear unencrypted key from memory
            privateKeyBytes.fill(0)

            // Save encrypted private key
            getPrivateKeyFile().writeText(
                Base64.encodeToString(encryptedPrivateKey, Base64.DEFAULT)
            )

            // Save public key (no encryption needed)
            val publicKeyBytes = keyPair.public.encoded
            getPublicKeyFile().writeText(
                Base64.encodeToString(publicKeyBytes, Base64.DEFAULT)
            )

            Log.i(TAG, "Saved RSA key pair (private key encrypted with Android Keystore)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save key pair", e)
        }
    }

    private fun getPrivateKeyFile(): File {
        return File(context.filesDir, PRIVATE_KEY_FILE)
    }

    private fun getPublicKeyFile(): File {
        return File(context.filesDir, PUBLIC_KEY_FILE)
    }
}
