package com.autoposter.adb.pairing

import android.content.Context
import android.util.Log
import com.autoposter.adb.KeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WirelessPairingManager orchestrates the complete Wireless Debugging pairing flow.
 *
 * Complete flow:
 * 1. User opens Settings > Developer Options > Wireless Debugging
 * 2. User taps "Pair device with pairing code"
 * 3. App discovers pairing service via mDNS
 * 4. App shows notification with RemoteInput for 6-digit code
 * 5. User enters code in notification
 * 6. App performs SPAKE2 pairing
 * 7. App saves credentials for future connections
 *
 * After pairing, the app can:
 * - Connect to ADB daemon without re-pairing
 * - Launch Privileged Server for advanced input injection
 */
@Singleton
class WirelessPairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val mdnsDiscovery: MdnsDiscovery
) {

    companion object {
        private const val TAG = "WirelessPairingManager"
        private const val PREFS_NAME = "wireless_pairing"
        private const val KEY_PAIRED = "is_paired"
        private const val KEY_SERVER_PUBLIC_KEY = "server_public_key"
        private const val KEY_PAIRED_DEVICE = "paired_device"
    }

    // Pairing state
    sealed class PairingState {
        object Idle : PairingState()
        object DiscoveringService : PairingState()
        data class WaitingForCode(val host: String, val port: Int) : PairingState()
        object Pairing : PairingState()
        object Success : PairingState()
        data class Error(val message: String) : PairingState()
    }

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Pending pairing info
    private var pendingPairingHost: String? = null
    private var pendingPairingPort: Int? = null

    /**
     * Check if device is already paired.
     */
    fun isPaired(): Boolean {
        return prefs.getBoolean(KEY_PAIRED, false)
    }

    /**
     * Get paired device name.
     */
    fun getPairedDeviceName(): String? {
        return prefs.getString(KEY_PAIRED_DEVICE, null)
    }

    /**
     * Start the pairing process.
     * This will discover the pairing service via mDNS.
     */
    suspend fun startPairing(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting pairing process")
            _pairingState.value = PairingState.DiscoveringService

            // Discover pairing service
            val service = mdnsDiscovery.discoverPairingService()

            if (service == null) {
                val error = "Pairing service not found. Make sure Wireless Debugging is enabled and you've tapped 'Pair device with pairing code'"
                Log.e(TAG, error)
                _pairingState.value = PairingState.Error(error)
                return@withContext Result.failure(PairingException(error))
            }

            Log.i(TAG, "Found pairing service at ${service.host}:${service.port}")

            // Store for later use when code is entered
            pendingPairingHost = service.host
            pendingPairingPort = service.port

            _pairingState.value = PairingState.WaitingForCode(service.host, service.port)

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error starting pairing", e)
            _pairingState.value = PairingState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Complete pairing with the 6-digit code.
     * Call this after user enters the code from Settings.
     */
    suspend fun completeWithCode(pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val host = pendingPairingHost
        val port = pendingPairingPort

        if (host == null || port == null) {
            val error = "No pending pairing. Call startPairing() first"
            _pairingState.value = PairingState.Error(error)
            return@withContext Result.failure(PairingException(error))
        }

        try {
            Log.i(TAG, "Completing pairing with code: ${pairingCode.take(2)}****")
            _pairingState.value = PairingState.Pairing

            // Get our device name and public key
            val deviceName = getDeviceName()
            val publicKey = keyManager.getPublicKeyAdbFormat().toByteArray(Charsets.UTF_8)

            // Perform pairing
            val connection = AdbPairingConnection()
            val result = connection.pair(host, port, pairingCode, deviceName, publicKey)

            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Pairing failed"
                Log.e(TAG, "Pairing failed: $error")
                _pairingState.value = PairingState.Error(error)
                return@withContext Result.failure(PairingException(error))
            }

            val serverPublicKey = result.getOrThrow()

            // Save pairing info
            savePairingInfo(deviceName, serverPublicKey)

            Log.i(TAG, "Pairing successful!")
            _pairingState.value = PairingState.Success

            // Clear pending state
            pendingPairingHost = null
            pendingPairingPort = null

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error completing pairing", e)
            _pairingState.value = PairingState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Cancel ongoing pairing.
     */
    fun cancelPairing() {
        Log.i(TAG, "Pairing cancelled")
        mdnsDiscovery.stopDiscovery()
        pendingPairingHost = null
        pendingPairingPort = null
        _pairingState.value = PairingState.Idle
    }

    /**
     * Clear pairing info (unpair).
     */
    fun unpair() {
        Log.i(TAG, "Unpairing device")
        prefs.edit()
            .remove(KEY_PAIRED)
            .remove(KEY_SERVER_PUBLIC_KEY)
            .remove(KEY_PAIRED_DEVICE)
            .apply()

        // Also delete RSA keys
        keyManager.deleteKeys()

        _pairingState.value = PairingState.Idle
    }

    /**
     * Discover and return the connect service (for ADB connection after pairing).
     */
    suspend fun discoverConnectService(): MdnsDiscovery.AdbService? {
        return mdnsDiscovery.discoverConnectService()
    }

    /**
     * Get server's public key (after pairing).
     */
    fun getServerPublicKey(): ByteArray? {
        val base64Key = prefs.getString(KEY_SERVER_PUBLIC_KEY, null) ?: return null
        return android.util.Base64.decode(base64Key, android.util.Base64.DEFAULT)
    }

    /**
     * Save pairing info to preferences.
     */
    private fun savePairingInfo(deviceName: String, serverPublicKey: ByteArray) {
        val base64Key = android.util.Base64.encodeToString(
            serverPublicKey,
            android.util.Base64.DEFAULT
        )

        prefs.edit()
            .putBoolean(KEY_PAIRED, true)
            .putString(KEY_SERVER_PUBLIC_KEY, base64Key)
            .putString(KEY_PAIRED_DEVICE, deviceName)
            .apply()
    }

    /**
     * Get device name for pairing.
     */
    private fun getDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        return "AutoPoster@${manufacturer}_${model}".replace(" ", "_")
    }
}
