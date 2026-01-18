package com.autoposter.adb.pairing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ADB Pairing Connection handles the pairing protocol over TLS.
 *
 * Pairing protocol flow:
 * 1. TCP connect to pairing port
 * 2. SPAKE2 key exchange (plain)
 * 3. Switch to encrypted channel (AES-GCM)
 * 4. Exchange PeerInfo (device name, GUID)
 * 5. Exchange certificates (RSA public keys)
 * 6. Pairing complete
 *
 * After pairing, the app can connect to the ADB daemon using the exchanged keys.
 *
 * Implements Closeable to ensure secrets are cleared from memory.
 */
class AdbPairingConnection : Closeable {

    companion object {
        private const val TAG = "AdbPairingConnection"

        // Pairing protocol constants
        private const val PAIRING_VERSION = 1
        private const val SPAKE2_MSG_SIZE = 65 // Uncompressed P-256 point (04 || x || y)

        // GCM IV prefixes to prevent IV reuse in bidirectional communication
        private const val CLIENT_IV_PREFIX = 0x00000000
        private const val SERVER_IV_PREFIX = 0x00000001

        // Message types
        private const val MSG_SPAKE2 = 0
        private const val MSG_PEER_INFO = 1

        // Peer info types
        private const val PEER_TYPE_ADB_CLIENT = 0
        private const val PEER_TYPE_ADB_SERVER = 1

        // Max message size
        private const val MAX_PEER_INFO_SIZE = 8192
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private var encryptionKey: ByteArray? = null
    private var decryptionKey: ByteArray? = null
    private var encryptIv: Long = 0
    private var decryptIv: Long = 0

    private val spake2 = Spake2Client()

    /**
     * Pair with ADB daemon using the 6-digit pairing code.
     *
     * @param host Host address (usually 127.0.0.1 for local)
     * @param port Pairing port (from mDNS discovery)
     * @param pairingCode 6-digit pairing code from Settings
     * @param deviceName Our device name for identification
     * @param publicKey Our RSA public key for ADB auth
     * @return Result with the server's public key on success
     */
    suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        deviceName: String,
        publicKey: ByteArray
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting pairing with $host:$port")

            // 1. Connect to pairing server
            socket = Socket(host, port).apply {
                soTimeout = 30_000
                tcpNoDelay = true
            }
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())

            // 2. SPAKE2 key exchange
            val sharedSecret = performSpake2Exchange(pairingCode)
            Log.d(TAG, "SPAKE2 exchange complete")

            // 3. Derive encryption keys
            setupEncryption(sharedSecret)
            Log.d(TAG, "Encryption setup complete")

            // 4. Send our peer info
            sendPeerInfo(deviceName, publicKey)
            Log.d(TAG, "Sent peer info")

            // 5. Receive server's peer info
            val serverPublicKey = receivePeerInfo()
            Log.i(TAG, "Pairing complete, received server public key (${serverPublicKey.size} bytes)")

            Result.success(serverPublicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed", e)
            Result.failure(PairingException("Pairing failed: ${e.message}", e))
        } finally {
            disconnect()
        }
    }

    /**
     * Perform SPAKE2 key exchange.
     */
    private suspend fun performSpake2Exchange(password: String): ByteArray {
        // Generate and send client message
        val clientMessage = spake2.generateClientMessage(password)

        // Send with header: [version (4)] [msg_type (4)] [payload_size (4)] [payload]
        val headerBuffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        headerBuffer.putInt(PAIRING_VERSION)
        headerBuffer.putInt(MSG_SPAKE2)
        headerBuffer.putInt(clientMessage.size)

        output!!.write(headerBuffer.array())
        output!!.write(clientMessage)
        output!!.flush()

        // Read server response header
        val serverHeader = ByteArray(12)
        input!!.readFully(serverHeader)

        val headerBuf = ByteBuffer.wrap(serverHeader).order(ByteOrder.BIG_ENDIAN)
        val version = headerBuf.int
        val msgType = headerBuf.int
        val payloadSize = headerBuf.int

        require(version == PAIRING_VERSION) { "Unsupported pairing version: $version" }
        require(msgType == MSG_SPAKE2) { "Expected SPAKE2 message, got: $msgType" }
        require(payloadSize == SPAKE2_MSG_SIZE) { "Invalid SPAKE2 message size: $payloadSize" }

        // Read server's SPAKE2 message
        val serverMessage = ByteArray(payloadSize)
        input!!.readFully(serverMessage)

        // Process and derive shared secret
        return spake2.processServerMessage(serverMessage)
    }

    /**
     * Setup AES-GCM encryption using derived keys.
     *
     * CRITICAL: We use different IV prefixes for client->server and server->client
     * to prevent IV reuse which would compromise GCM security.
     */
    private fun setupEncryption(sharedSecret: ByteArray) {
        val (key, _) = spake2.deriveEncryptionKeys(sharedSecret)

        // Same key for both directions (symmetric)
        encryptionKey = key.copyOf()
        decryptionKey = key.copyOf()

        // Start counters at 0, prefixes differentiate directions
        encryptIv = 0
        decryptIv = 0
    }

    /**
     * Send our peer info (encrypted).
     */
    private suspend fun sendPeerInfo(deviceName: String, publicKey: ByteArray) {
        // Build peer info structure
        val peerInfo = buildPeerInfo(deviceName, publicKey)

        // Encrypt
        val encrypted = encrypt(peerInfo)

        // Send header + encrypted payload
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        header.putInt(PAIRING_VERSION)
        header.putInt(MSG_PEER_INFO)
        header.putInt(encrypted.size)

        output!!.write(header.array())
        output!!.write(encrypted)
        output!!.flush()
    }

    /**
     * Receive server's peer info (encrypted).
     */
    private suspend fun receivePeerInfo(): ByteArray {
        // Read header
        val header = ByteArray(12)
        input!!.readFully(header)

        val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = headerBuf.int
        val msgType = headerBuf.int
        val payloadSize = headerBuf.int

        require(version == PAIRING_VERSION) { "Unsupported version: $version" }
        require(msgType == MSG_PEER_INFO) { "Expected peer info, got: $msgType" }
        require(payloadSize in 1..MAX_PEER_INFO_SIZE) { "Invalid payload size: $payloadSize" }

        // Read encrypted payload
        val encrypted = ByteArray(payloadSize)
        input!!.readFully(encrypted)

        // Decrypt
        val decrypted = decrypt(encrypted)

        // Parse peer info and extract public key
        return parsePeerInfo(decrypted)
    }

    /**
     * Build peer info structure.
     *
     * Format:
     * [type (1)] [name_length (4)] [name] [public_key_length (4)] [public_key]
     */
    private fun buildPeerInfo(name: String, publicKey: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(1 + 4 + nameBytes.size + 4 + publicKey.size)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.put(PEER_TYPE_ADB_CLIENT.toByte())
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putInt(publicKey.size)
        buffer.put(publicKey)

        return buffer.array()
    }

    /**
     * Parse peer info and extract public key.
     */
    private fun parsePeerInfo(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val type = buffer.get().toInt()
        require(type == PEER_TYPE_ADB_SERVER) { "Expected server type, got: $type" }

        val nameLength = buffer.int
        require(nameLength in 0..256) { "Invalid name length: $nameLength" }

        val name = ByteArray(nameLength)
        buffer.get(name)
        Log.d(TAG, "Server name: ${String(name, Charsets.UTF_8)}")

        val publicKeyLength = buffer.int
        require(publicKeyLength in 1..4096) { "Invalid public key length: $publicKeyLength" }

        val publicKey = ByteArray(publicKeyLength)
        buffer.get(publicKey)

        return publicKey
    }

    /**
     * Encrypt data using AES-GCM.
     * Uses CLIENT_IV_PREFIX to differentiate from server->client messages.
     */
    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(encryptionKey, "AES")

        // Build IV: [client prefix 4 bytes][counter 8 bytes]
        // Different prefix than decrypt to prevent IV collision
        val iv = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        iv.putInt(CLIENT_IV_PREFIX)
        iv.putLong(encryptIv++)

        val gcmSpec = GCMParameterSpec(Spake2Parameters.TAG_SIZE * 8, iv.array())
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(data)
    }

    /**
     * Decrypt data using AES-GCM.
     * Uses SERVER_IV_PREFIX to differentiate from client->server messages.
     */
    private fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(decryptionKey, "AES")

        // Build IV: [server prefix 4 bytes][counter 8 bytes]
        // Different prefix than encrypt to prevent IV collision
        val iv = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        iv.putInt(SERVER_IV_PREFIX)
        iv.putLong(decryptIv++)

        val gcmSpec = GCMParameterSpec(Spake2Parameters.TAG_SIZE * 8, iv.array())
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(data)
    }

    /**
     * Disconnect from pairing server and clear secrets.
     */
    private fun disconnect() {
        try {
            input?.close()
            output?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        } finally {
            input = null
            output = null
            socket = null
        }

        // Clear cryptographic secrets from memory
        clearSecrets()
    }

    /**
     * Clear all secrets from memory.
     */
    private fun clearSecrets() {
        encryptionKey?.fill(0)
        encryptionKey = null
        decryptionKey?.fill(0)
        decryptionKey = null
        encryptIv = 0
        decryptIv = 0

        // Close SPAKE2 client to clear its secrets
        spake2.close()
    }

    /**
     * Closeable implementation - ensures secrets are cleared.
     */
    override fun close() {
        disconnect()
    }
}

class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)
