package com.autoposter.adb

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdbClient - High-level interface for ADB operations.
 *
 * Provides:
 * - Connection to local ADB daemon (Wireless Debugging)
 * - Shell command execution
 * - Privileged server management
 *
 * Usage flow for Advanced Mode:
 * 1. User enables Wireless Debugging on device
 * 2. App discovers pairing port via mDNS
 * 3. User enters 6-digit pairing code
 * 4. App pairs with SPAKE2 protocol
 * 5. App connects to ADB daemon
 * 6. App launches Privileged Server via app_process
 * 7. App communicates with server via LocalSocket
 */
@Singleton
class AdbClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager
) {
    companion object {
        private const val TAG = "AdbClient"
        private const val PRIVILEGED_SERVER_SOCKET = "autoposter_privileged"
        private const val SERVER_START_TIMEOUT_MS = 5000L
        private const val SERVER_CHECK_INTERVAL_MS = 500L
    }

    private var connection: AdbConnection? = null
    private var wirelessPort: Int = 0

    /**
     * Connect to local ADB daemon (Wireless Debugging).
     *
     * @param port The wireless debugging port (usually 5555 or dynamic port from mDNS)
     */
    suspend fun connect(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to ADB on port $port")

            val conn = AdbConnection(keyManager)
            val result = conn.connect("127.0.0.1", port)

            if (result.isSuccess) {
                connection = conn
                wirelessPort = port
                Log.i(TAG, "Connected to ADB")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ADB", e)
            Result.failure(e)
        }
    }

    /**
     * Disconnect from ADB.
     */
    suspend fun disconnect() {
        connection?.disconnect()
        connection = null
        wirelessPort = 0
        Log.i(TAG, "Disconnected from ADB")
    }

    /**
     * Check if connected to ADB.
     */
    fun isConnected(): Boolean = connection?.isConnected() == true

    /**
     * Execute shell command.
     */
    suspend fun shell(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = connection ?: return@withContext Result.failure(AdbException("Not connected"))
            val output = conn.executeShell(command)
            Result.success(output)
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            Result.failure(e)
        }
    }

    /**
     * Start the Privileged Server.
     *
     * The server runs with shell UID (2000) and has access to /dev/input/eventX.
     * It's launched via app_process which allows running Java code with elevated permissions.
     */
    suspend fun startPrivilegedServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isServerRunning()) {
                Log.i(TAG, "Privileged server already running")
                return@withContext Result.success(Unit)
            }

            val apkPath = context.applicationInfo.sourceDir
            val dataDir = context.applicationInfo.dataDir
            Log.i(TAG, "APK path: $apkPath, Data dir: $dataDir")

            // Command to start privileged server
            // CRITICAL: --data-dir is required for authentication token loading
            val command = buildString {
                append("CLASSPATH=$apkPath ")
                append("app_process / ")
                append("com.autoposter.privileged.PrivilegedServer ")
                append("--socket=$PRIVILEGED_SERVER_SOCKET ")
                append("--data-dir=$dataDir")
            }

            Log.i(TAG, "Starting privileged server: $command")

            // Execute in background (nohup)
            val result = shell("nohup $command > /dev/null 2>&1 &")
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull() ?: AdbException("Failed to start server"))
            }

            // Wait for server to start
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
                delay(SERVER_CHECK_INTERVAL_MS)
                if (isServerRunning()) {
                    Log.i(TAG, "Privileged server started successfully")
                    return@withContext Result.success(Unit)
                }
            }

            Result.failure(AdbException("Server failed to start within timeout"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start privileged server", e)
            Result.failure(e)
        }
    }

    /**
     * Stop the Privileged Server.
     */
    suspend fun stopPrivilegedServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Find and kill the server process
            val result = shell("pkill -f 'com.autoposter.privileged.PrivilegedServer'")
            if (result.isSuccess) {
                Log.i(TAG, "Privileged server stopped")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop privileged server", e)
            Result.failure(e)
        }
    }

    /**
     * Check if Privileged Server is running.
     */
    fun isServerRunning(): Boolean {
        return try {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(PRIVILEGED_SERVER_SOCKET))
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get device info.
     */
    suspend fun getDeviceInfo(): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        try {
            val model = shell("getprop ro.product.model").getOrNull()?.trim() ?: "Unknown"
            val sdk = shell("getprop ro.build.version.sdk").getOrNull()?.trim()?.toIntOrNull() ?: 0
            val serial = shell("getprop ro.serialno").getOrNull()?.trim() ?: "Unknown"

            Result.success(DeviceInfo(model, sdk, serial))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Find touch input device path.
     */
    suspend fun findTouchDevice(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // List input devices and find the touchscreen
            val result = shell("getevent -pl")
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull() ?: AdbException("Failed to list devices"))
            }

            val output = result.getOrThrow()
            val touchDevice = parseTouchDevice(output)

            if (touchDevice != null) {
                Log.i(TAG, "Found touch device: $touchDevice")
                Result.success(touchDevice)
            } else {
                Result.failure(AdbException("Touch device not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse getevent output to find touch device.
     */
    private fun parseTouchDevice(output: String): String? {
        var currentDevice: String? = null

        for (line in output.lines()) {
            if (line.startsWith("add device")) {
                // Format: add device 1: /dev/input/event2
                currentDevice = line.substringAfter(": ").trim()
            } else if (line.contains("ABS_MT_POSITION") && currentDevice != null) {
                // This device has multi-touch support
                return currentDevice
            }
        }

        return null
    }
}

/**
 * Device information.
 */
data class DeviceInfo(
    val model: String,
    val sdkVersion: Int,
    val serial: String
)
