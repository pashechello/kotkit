package com.autoposter.adb

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.autoposter.privileged.AuthTokenManager
import com.autoposter.privileged.PrivilegedServer
import com.autoposter.privileged.ServerProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ServerBootstrap handles starting and managing the Privileged Server.
 *
 * The Privileged Server runs with shell UID (2000) via app_process,
 * giving it access to /dev/input/eventX for direct input injection.
 *
 * Starting the server:
 * 1. App connects to ADB daemon (Wireless Debugging)
 * 2. App sends shell command to start app_process
 * 3. Server initializes and opens LocalServerSocket
 * 4. App connects to server via LocalSocket
 *
 * The server persists until the device is rebooted or explicitly stopped.
 */
@Singleton
class ServerBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adbClient: AdbClient
) {

    companion object {
        private const val TAG = "ServerBootstrap"

        private const val SOCKET_NAME = PrivilegedServer.DEFAULT_SOCKET_NAME
        private const val START_TIMEOUT_MS = 10_000L
        private const val CHECK_INTERVAL_MS = 500L
    }

    /**
     * Check if server is running.
     */
    fun isServerRunning(): Boolean {
        return try {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(SOCKET_NAME))
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Start the Privileged Server via ADB.
     *
     * @param adbPort The ADB daemon port (from mDNS discovery after pairing)
     */
    suspend fun startServer(adbPort: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Check if already running
            if (isServerRunning()) {
                Log.i(TAG, "Server already running")
                return@withContext Result.success(Unit)
            }

            // Ensure auth token exists before starting server
            AuthTokenManager.getOrCreateToken(context)
            Log.d(TAG, "Auth token prepared")

            // Connect to ADB
            if (!adbClient.isConnected()) {
                Log.i(TAG, "Connecting to ADB on port $adbPort")
                val connectResult = adbClient.connect(adbPort)
                if (connectResult.isFailure) {
                    return@withContext Result.failure(
                        connectResult.exceptionOrNull() ?: Exception("ADB connection failed")
                    )
                }
            }

            // Build start command with data directory for auth token
            val apkPath = context.applicationInfo.sourceDir
            val dataDir = context.applicationInfo.dataDir
            val command = buildStartCommand(apkPath, dataDir)

            Log.i(TAG, "Starting server with command: $command")

            // Execute in background (nohup)
            val result = adbClient.shell("nohup $command > /dev/null 2>&1 &")
            if (result.isFailure) {
                return@withContext Result.failure(
                    result.exceptionOrNull() ?: Exception("Failed to start server")
                )
            }

            // Wait for server to start
            val started = waitForServer()
            if (!started) {
                return@withContext Result.failure(Exception("Server failed to start within timeout"))
            }

            Log.i(TAG, "Server started successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            Result.failure(e)
        }
    }

    /**
     * Stop the Privileged Server.
     */
    suspend fun stopServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isServerRunning()) {
                Log.i(TAG, "Server not running")
                return@withContext Result.success(Unit)
            }

            // Send shutdown command via socket (with authentication)
            try {
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress(SOCKET_NAME))

                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)

                // Authenticate first
                val authToken = AuthTokenManager.getOrCreateToken(context)
                val authCmd = ServerProtocol.AuthCommand(authToken)
                ServerProtocol.writeCommand(output, ServerProtocol.CMD_AUTH, authCmd.toBytes())
                ServerProtocol.readResponse(input)

                // Send shutdown
                ServerProtocol.writeCommand(output, ServerProtocol.CMD_SHUTDOWN)

                socket.close()
                Log.i(TAG, "Shutdown command sent")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send shutdown command: ${e.message}")
            }

            // Also try to kill via pkill
            if (adbClient.isConnected()) {
                adbClient.shell("pkill -f 'com.autoposter.privileged.PrivilegedServer'")
            }

            // Wait for server to stop
            delay(1000)

            if (isServerRunning()) {
                Log.w(TAG, "Server still running after shutdown")
            } else {
                Log.i(TAG, "Server stopped")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop server", e)
            Result.failure(e)
        }
    }

    /**
     * Restart the server.
     */
    suspend fun restartServer(adbPort: Int): Result<Unit> {
        stopServer()
        delay(1000)
        return startServer(adbPort)
    }

    /**
     * Get server status info.
     */
    suspend fun getServerInfo(): ServerInfo {
        val running = isServerRunning()

        var deviceInfo: ServerProtocol.DeviceInfo? = null
        if (running) {
            try {
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress(SOCKET_NAME))

                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)

                // Authenticate first
                val authToken = AuthTokenManager.getOrCreateToken(context)
                val authCmd = ServerProtocol.AuthCommand(authToken)
                ServerProtocol.writeCommand(output, ServerProtocol.CMD_AUTH, authCmd.toBytes())
                val (authSuccess, _) = ServerProtocol.readResponse(input)

                if (authSuccess) {
                    // Send GET_DEVICE_INFO command
                    ServerProtocol.writeCommand(output, ServerProtocol.CMD_GET_DEVICE_INFO)

                    // Read response
                    val (success, payload) = ServerProtocol.readResponse(input)
                    if (success && payload.isNotEmpty()) {
                        deviceInfo = ServerProtocol.DeviceInfo.fromBytes(payload)
                    }
                }

                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get device info: ${e.message}")
            }
        }

        return ServerInfo(
            isRunning = running,
            socketName = SOCKET_NAME,
            devicePath = deviceInfo?.devicePath,
            maxX = deviceInfo?.maxX,
            maxY = deviceInfo?.maxY,
            maxPressure = deviceInfo?.maxPressure
        )
    }

    /**
     * Build the command to start the Privileged Server.
     */
    private fun buildStartCommand(apkPath: String, dataDir: String): String {
        return buildString {
            append("CLASSPATH=$apkPath ")
            append("app_process / ")
            append("com.autoposter.privileged.PrivilegedServer ")
            append("--socket=$SOCKET_NAME ")
            append("--data-dir=$dataDir")
        }
    }

    /**
     * Wait for server to start.
     */
    private suspend fun waitForServer(): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < START_TIMEOUT_MS) {
            if (isServerRunning()) {
                return true
            }
            delay(CHECK_INTERVAL_MS)
        }

        return false
    }

    /**
     * Server status info.
     */
    data class ServerInfo(
        val isRunning: Boolean,
        val socketName: String,
        val devicePath: String?,
        val maxX: Int?,
        val maxY: Int?,
        val maxPressure: Int?
    )
}
