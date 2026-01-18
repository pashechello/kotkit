package com.autoposter.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AdbConnection manages TCP connection to ADB daemon.
 *
 * Handles:
 * - Socket connection and disconnection
 * - Sending and receiving ADB protocol messages
 * - Stream management for shell commands
 *
 * Thread-safe: Uses mutex for socket access.
 */
class AdbConnection(
    private val keyManager: KeyManager
) {
    companion object {
        private const val TAG = "AdbConnection"
        private const val SOCKET_TIMEOUT = 30_000 // 30 seconds
    }

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null

    private val socketMutex = Mutex()
    private val isConnected = AtomicBoolean(false)
    private val localIdCounter = AtomicInteger(1)

    private var maxPayload = AdbProtocol.MAX_PAYLOAD

    /**
     * Connect to ADB daemon.
     */
    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        socketMutex.withLock {
            try {
                Log.i(TAG, "Connecting to ADB at $host:$port")

                socket = Socket(host, port).apply {
                    soTimeout = SOCKET_TIMEOUT
                    tcpNoDelay = true
                }

                inputStream = DataInputStream(socket!!.getInputStream())
                outputStream = DataOutputStream(socket!!.getOutputStream())

                // Send CNXN
                val connectMsg = AdbProtocol.createConnectMessage()
                sendMessage(connectMsg)

                // Read response
                val response = readMessage()

                when (response.command) {
                    AdbProtocol.CMD_CNXN -> {
                        // Direct connection accepted
                        maxPayload = response.arg1
                        isConnected.set(true)
                        Log.i(TAG, "Connected to ADB (maxPayload: $maxPayload)")
                        Result.success(Unit)
                    }
                    AdbProtocol.CMD_AUTH -> {
                        // Authentication required
                        Log.i(TAG, "Authentication required")
                        authenticate(response)
                    }
                    else -> {
                        Result.failure(AdbException("Unexpected response: ${response.commandString()}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                disconnect()
                Result.failure(AdbException("Connection failed: ${e.message}", e))
            }
        }
    }

    /**
     * Disconnect from ADB.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        socketMutex.withLock {
            isConnected.set(false)
            try {
                inputStream?.close()
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing connection", e)
            } finally {
                inputStream = null
                outputStream = null
                socket = null
            }
        }
    }

    /**
     * Check if connected.
     */
    fun isConnected(): Boolean = isConnected.get() && socket?.isConnected == true

    /**
     * Execute shell command and return output.
     */
    suspend fun executeShell(command: String): String = withContext(Dispatchers.IO) {
        require(isConnected()) { "Not connected" }

        val localId = localIdCounter.getAndIncrement()
        val destination = "shell:$command"

        socketMutex.withLock {
            // Open shell stream
            val openMsg = AdbProtocol.createOpenMessage(localId, destination)
            sendMessage(openMsg)

            // Wait for OKAY
            val okayResponse = readMessage()
            if (okayResponse.command != AdbProtocol.CMD_OKAY) {
                throw AdbException("Failed to open shell: ${okayResponse.commandString()}")
            }

            val remoteId = okayResponse.arg0
            val output = StringBuilder()

            // Read output
            while (true) {
                val response = readMessage()

                when (response.command) {
                    AdbProtocol.CMD_WRTE -> {
                        // Received data
                        output.append(String(response.payload, Charsets.UTF_8))
                        // Send OKAY to acknowledge
                        sendMessage(AdbProtocol.createOkayMessage(localId, remoteId))
                    }
                    AdbProtocol.CMD_CLSE -> {
                        // Stream closed
                        Log.d(TAG, "Shell stream closed")
                        break
                    }
                    else -> {
                        Log.w(TAG, "Unexpected message: ${response.commandString()}")
                    }
                }
            }

            output.toString()
        }
    }

    /**
     * Open a shell stream (for interactive commands).
     */
    suspend fun openShell(): ShellStream = withContext(Dispatchers.IO) {
        require(isConnected()) { "Not connected" }

        val localId = localIdCounter.getAndIncrement()

        socketMutex.withLock {
            val openMsg = AdbProtocol.createOpenMessage(localId, "shell:")
            sendMessage(openMsg)

            val response = readMessage()
            if (response.command != AdbProtocol.CMD_OKAY) {
                throw AdbException("Failed to open shell: ${response.commandString()}")
            }

            val remoteId = response.arg0
            ShellStream(this@AdbConnection, localId, remoteId)
        }
    }

    /**
     * Handle ADB authentication flow.
     */
    private suspend fun authenticate(authMessage: AdbProtocol.AdbMessage): Result<Unit> {
        require(authMessage.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) { "Expected AUTH_TYPE_TOKEN" }

        val token = authMessage.payload
        Log.d(TAG, "Received auth token (${token.size} bytes)")

        // Sign token with private key
        val signature = keyManager.sign(token)
        val signatureMsg = AdbProtocol.createAuthSignatureMessage(signature)
        sendMessage(signatureMsg)

        // Read response
        val response = readMessage()

        return when (response.command) {
            AdbProtocol.CMD_CNXN -> {
                // Signature accepted
                maxPayload = response.arg1
                isConnected.set(true)
                Log.i(TAG, "Authenticated and connected")
                Result.success(Unit)
            }
            AdbProtocol.CMD_AUTH -> {
                // Need to send public key (first connection)
                Log.i(TAG, "Sending public key for authorization")
                val publicKey = keyManager.getPublicKeyAdbFormat().toByteArray(Charsets.UTF_8)
                val pubKeyMsg = AdbProtocol.createAuthPublicKeyMessage(publicKey)
                sendMessage(pubKeyMsg)

                // Wait for user to accept on device
                val finalResponse = readMessage()
                if (finalResponse.command == AdbProtocol.CMD_CNXN) {
                    maxPayload = finalResponse.arg1
                    isConnected.set(true)
                    Log.i(TAG, "Public key accepted, connected")
                    Result.success(Unit)
                } else {
                    Result.failure(AdbException("Authorization denied"))
                }
            }
            else -> {
                Result.failure(AdbException("Auth failed: ${response.commandString()}"))
            }
        }
    }

    /**
     * Send message to ADB.
     */
    internal suspend fun sendMessage(message: AdbProtocol.AdbMessage) {
        Log.d(TAG, "Sending: ${message.commandString()} (${message.payload.size} bytes)")
        outputStream?.apply {
            write(message.toBytes())
            flush()
        } ?: throw AdbException("Not connected")
    }

    /**
     * Read message from ADB.
     */
    internal suspend fun readMessage(): AdbProtocol.AdbMessage {
        val input = inputStream ?: throw AdbException("Not connected")

        // Read header
        val headerBytes = ByteArray(AdbProtocol.HEADER_SIZE)
        input.readFully(headerBytes)

        val headerInfo = AdbProtocol.parseHeader(headerBytes)

        // Read payload if present
        val payload = if (headerInfo.dataLength > 0) {
            ByteArray(headerInfo.dataLength).also { input.readFully(it) }
        } else {
            ByteArray(0)
        }

        val message = AdbProtocol.parseMessage(headerInfo, payload)
        Log.d(TAG, "Received: ${message.commandString()} (${message.payload.size} bytes)")

        return message
    }

    /**
     * Get maximum payload size.
     */
    fun getMaxPayload(): Int = maxPayload
}

/**
 * Shell stream for interactive commands.
 */
class ShellStream(
    private val connection: AdbConnection,
    private val localId: Int,
    private val remoteId: Int
) {
    private val isClosed = AtomicBoolean(false)

    suspend fun write(data: String) {
        if (isClosed.get()) throw AdbException("Stream closed")
        val msg = AdbProtocol.createWriteMessage(localId, remoteId, data.toByteArray())
        connection.sendMessage(msg)
    }

    suspend fun read(): String {
        if (isClosed.get()) throw AdbException("Stream closed")
        val msg = connection.readMessage()

        return when (msg.command) {
            AdbProtocol.CMD_WRTE -> {
                connection.sendMessage(AdbProtocol.createOkayMessage(localId, remoteId))
                String(msg.payload, Charsets.UTF_8)
            }
            AdbProtocol.CMD_CLSE -> {
                isClosed.set(true)
                ""
            }
            else -> throw AdbException("Unexpected: ${msg.commandString()}")
        }
    }

    suspend fun close() {
        if (isClosed.compareAndSet(false, true)) {
            connection.sendMessage(AdbProtocol.createCloseMessage(localId, remoteId))
        }
    }
}

class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)
