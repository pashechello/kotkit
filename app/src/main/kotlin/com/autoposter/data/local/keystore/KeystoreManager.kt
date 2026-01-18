package com.autoposter.data.local.keystore

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "autoposter_unlock_key"
        private const val PREFS_NAME = "autoposter_secure_prefs"
        private const val KEY_PIN = "encrypted_pin"
        private const val KEY_PASSWORD = "encrypted_password"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKey()
        }
    }

    private fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // Needed for background access
            .build()

        keyGenerator.init(keySpec)
        keyGenerator.generateKey()
    }

    fun savePin(pin: String) {
        val encrypted = encrypt(pin)
        getPrefs().edit().putString(KEY_PIN, encrypted).apply()
    }

    fun getStoredPin(): String? {
        val encrypted = getPrefs().getString(KEY_PIN, null) ?: return null
        return decrypt(encrypted)
    }

    fun hasStoredPin(): Boolean = getPrefs().contains(KEY_PIN)

    fun clearPin() {
        getPrefs().edit().remove(KEY_PIN).apply()
    }

    fun savePassword(password: String) {
        val encrypted = encrypt(password)
        getPrefs().edit().putString(KEY_PASSWORD, encrypted).apply()
    }

    fun getStoredPassword(): String? {
        val encrypted = getPrefs().getString(KEY_PASSWORD, null) ?: return null
        return decrypt(encrypted)
    }

    fun hasStoredPassword(): Boolean = getPrefs().contains(KEY_PASSWORD)

    fun clearPassword() {
        getPrefs().edit().remove(KEY_PASSWORD).apply()
    }

    fun clearAll() {
        getPrefs().edit().clear().apply()
    }

    fun hasUnlockCredentials(): Boolean {
        return hasStoredPin() || hasStoredPassword()
    }

    private fun encrypt(plaintext: String): String {
        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combine IV + encrypted data
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(ciphertext: String): String? {
        return try {
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP)

            // Extract IV and encrypted data
            val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
            val encrypted = combined.sliceArray(GCM_IV_LENGTH until combined.size)

            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getPrefs(): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
