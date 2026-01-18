package com.autoposter.executor.advanced

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.autoposter.data.remote.api.models.ActionType
import com.autoposter.data.remote.api.models.AnalyzeResponse
import com.autoposter.executor.accessibility.ExecutionResult
import com.autoposter.privileged.AuthTokenManager
import com.autoposter.privileged.PrivilegedServer
import com.autoposter.privileged.ServerProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdvancedExecutor - Uses Privileged Server for direct input injection.
 *
 * Unlike BasicExecutor (Accessibility API), AdvancedExecutor can:
 * - Control touch pressure curve
 * - Control touch size/major
 * - Add micro-movements during touch
 * - More precise timing control
 *
 * This makes touches much more human-like and harder to detect.
 *
 * Requires:
 * - Wireless Debugging pairing
 * - Privileged Server running
 */
@Singleton
class AdvancedExecutor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AdvancedExecutor"
        private const val SOCKET_NAME = PrivilegedServer.DEFAULT_SOCKET_NAME
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private val humanizer = AdvancedHumanizer()
    private val mutex = Mutex()

    private var socket: LocalSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    // Device info from server
    private var deviceInfo: ServerProtocol.DeviceInfo? = null

    /**
     * Check if connected to server.
     */
    fun isConnected(): Boolean = socket?.isConnected == true

    /**
     * Check if server is available.
     *
     * Note: This only checks if the server socket is accepting connections.
     * It does NOT send any commands (PING requires authentication).
     * For a full connectivity test, use connect() which handles auth.
     */
    fun isServerAvailable(): Boolean {
        return try {
            val testSocket = LocalSocket()
            testSocket.connect(LocalSocketAddress(SOCKET_NAME))
            // Socket connected successfully - server is running
            testSocket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Connect to Privileged Server.
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (isConnected()) {
                    return@withContext Result.success(Unit)
                }

                Log.i(TAG, "Connecting to Privileged Server")

                socket = LocalSocket()
                socket!!.connect(LocalSocketAddress(SOCKET_NAME))

                input = DataInputStream(socket!!.inputStream)
                output = DataOutputStream(socket!!.outputStream)

                // Authenticate first
                val authToken = AuthTokenManager.getOrCreateToken(context)
                val authCmd = ServerProtocol.AuthCommand(authToken)
                ServerProtocol.writeCommand(output!!, ServerProtocol.CMD_AUTH, authCmd.toBytes())
                val (authSuccess, _) = ServerProtocol.readResponse(input!!)

                if (!authSuccess) {
                    Log.e(TAG, "Authentication failed")
                    disconnect()
                    return@withContext Result.failure(SecurityException("Server authentication failed"))
                }

                Log.d(TAG, "Authenticated successfully")

                // Get device info
                ServerProtocol.writeCommand(output!!, ServerProtocol.CMD_GET_DEVICE_INFO)
                val (success, payload) = ServerProtocol.readResponse(input!!)

                if (success && payload.isNotEmpty()) {
                    deviceInfo = ServerProtocol.DeviceInfo.fromBytes(payload)
                    Log.i(TAG, "Connected. Device: ${deviceInfo?.devicePath}, " +
                            "max: ${deviceInfo?.maxX}x${deviceInfo?.maxY}")
                } else {
                    Log.w(TAG, "Connected but failed to get device info")
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                disconnect()
                Result.failure(e)
            }
        }
    }

    /**
     * Disconnect from server.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                input?.close()
                output?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error", e)
            } finally {
                input = null
                output = null
                socket = null
            }
        }
    }

    /**
     * Execute action using Privileged Server.
     */
    suspend fun execute(action: AnalyzeResponse): ExecutionResult {
        if (!isConnected()) {
            val connectResult = connect()
            if (connectResult.isFailure) {
                return ExecutionResult.Error("Server not connected", recoverable = true)
            }
        }

        // Add human-like pre-action delay
        val preDelay = humanizer.generatePreActionDelay()
        delay(preDelay)

        val result = when (action.action) {
            ActionType.TAP -> executeTap(action)
            ActionType.SWIPE -> executeSwipe(action)
            ActionType.TYPE_TEXT, ActionType.TYPE -> executeType(action)
            ActionType.WAIT -> executeWait(action)
            ActionType.BACK -> executeBack()
            ActionType.LAUNCH_TIKTOK, ActionType.OPEN_APP -> executeLaunchTikTok(action)
            ActionType.DISMISS_POPUP -> executeDismissPopup(action)
            ActionType.FINISH, ActionType.DONE -> ExecutionResult.Done(action.message)
            ActionType.ERROR -> ExecutionResult.Error(action.message, action.recoverable ?: false)
            else -> ExecutionResult.Error("Unknown action: ${action.action}", recoverable = false)
        }

        // Add human-like post-action delay
        if (result !is ExecutionResult.Done && result !is ExecutionResult.Error) {
            val postDelay = action.waitAfter?.toLong() ?: humanizer.generatePostActionDelay()
            delay(postDelay)
        }

        return result
    }

    /**
     * Execute tap with full humanization.
     */
    private suspend fun executeTap(action: AnalyzeResponse): ExecutionResult {
        val x = action.x ?: return ExecutionResult.Failed("Missing x coordinate")
        val y = action.y ?: return ExecutionResult.Failed("Missing y coordinate")

        // Generate fully humanized tap events
        val events = humanizer.generateHumanizedTap(
            targetX = x,
            targetY = y,
            elementWidth = action.elementWidth ?: 100,
            elementHeight = action.elementHeight ?: 50,
            maxPressure = deviceInfo?.maxPressure ?: 255,
            maxTouchMajor = deviceInfo?.maxTouchMajor ?: 255
        )

        Log.d(TAG, "Tap at ($x, $y) with ${events.size} humanized events")

        return sendTap(x, y, events)
    }

    /**
     * Execute swipe with humanization.
     */
    private suspend fun executeSwipe(action: AnalyzeResponse): ExecutionResult {
        val startX = action.startX ?: return ExecutionResult.Failed("Missing startX")
        val startY = action.startY ?: return ExecutionResult.Failed("Missing startY")
        val endX = action.endX ?: return ExecutionResult.Failed("Missing endX")
        val endY = action.endY ?: return ExecutionResult.Failed("Missing endY")
        val duration = action.duration?.toLong() ?: 300L

        // Generate humanized swipe events
        val events = humanizer.generateHumanizedSwipe(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = duration,
            maxPressure = deviceInfo?.maxPressure ?: 255,
            maxTouchMajor = deviceInfo?.maxTouchMajor ?: 255
        )

        Log.d(TAG, "Swipe ($startX,$startY) -> ($endX,$endY) with ${events.size} events")

        return sendSwipe(listOf(Pair(startX, startY), Pair(endX, endY)), duration, events)
    }

    /**
     * Execute text typing.
     */
    private suspend fun executeType(action: AnalyzeResponse): ExecutionResult {
        val text = action.text ?: return ExecutionResult.Failed("Missing text")

        Log.d(TAG, "Type: '$text'")

        return sendText(text)
    }

    /**
     * Execute wait.
     */
    private suspend fun executeWait(action: AnalyzeResponse): ExecutionResult {
        val duration = action.duration?.toLong() ?: action.waitAfter?.toLong() ?: 1000L
        Log.d(TAG, "Wait: ${duration}ms")
        delay(duration)
        return ExecutionResult.Success
    }

    /**
     * Execute back button.
     */
    private suspend fun executeBack(): ExecutionResult {
        Log.d(TAG, "Back pressed")
        // For back button, we still use accessibility service
        // as sendevent for keys is different
        return ExecutionResult.Success // TODO: Implement via key event
    }

    /**
     * Launch TikTok app.
     */
    private suspend fun executeLaunchTikTok(action: AnalyzeResponse): ExecutionResult {
        val packageName = action.packageName ?: "com.zhiliaoapp.musically"

        Log.d(TAG, "Launch TikTok: $packageName")

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            val liteIntent = context.packageManager.getLaunchIntentForPackage("com.ss.android.ugc.trill")
            if (liteIntent == null) {
                return ExecutionResult.Failed("TikTok not found")
            }
            liteIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(liteIntent)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        delay(2000L)
        return ExecutionResult.Success
    }

    /**
     * Dismiss popup.
     */
    private suspend fun executeDismissPopup(action: AnalyzeResponse): ExecutionResult {
        Log.d(TAG, "Dismiss popup: ${action.reason}")
        // Tap on close button if coordinates provided
        if (action.x != null && action.y != null) {
            return executeTap(action)
        }
        return executeBack()
    }

    /**
     * Send TAP command to server.
     */
    private suspend fun sendTap(x: Int, y: Int, events: List<ServerProtocol.InputEvent>): ExecutionResult {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val out = output ?: return@withContext ExecutionResult.Failed("Not connected")
                    val inp = input ?: return@withContext ExecutionResult.Failed("Not connected")

                    val cmd = ServerProtocol.TapCommand(x, y, events)
                    ServerProtocol.writeCommand(out, ServerProtocol.CMD_TAP, cmd.toBytes())

                    val (success, _) = ServerProtocol.readResponse(inp)

                    if (success) {
                        ExecutionResult.Success
                    } else {
                        ExecutionResult.Failed("Tap failed on server")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Tap error", e)
                    disconnect()
                    ExecutionResult.Failed("Tap error: ${e.message}")
                }
            }
        }
    }

    /**
     * Send SWIPE command to server.
     */
    private suspend fun sendSwipe(
        points: List<Pair<Int, Int>>,
        durationMs: Long,
        events: List<ServerProtocol.InputEvent>
    ): ExecutionResult {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val out = output ?: return@withContext ExecutionResult.Failed("Not connected")
                    val inp = input ?: return@withContext ExecutionResult.Failed("Not connected")

                    val cmd = ServerProtocol.SwipeCommand(points, durationMs, events)
                    ServerProtocol.writeCommand(out, ServerProtocol.CMD_SWIPE, cmd.toBytes())

                    val (success, _) = ServerProtocol.readResponse(inp)

                    if (success) {
                        ExecutionResult.Success
                    } else {
                        ExecutionResult.Failed("Swipe failed on server")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Swipe error", e)
                    disconnect()
                    ExecutionResult.Failed("Swipe error: ${e.message}")
                }
            }
        }
    }

    /**
     * Send TEXT command to server.
     */
    private suspend fun sendText(text: String): ExecutionResult {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val out = output ?: return@withContext ExecutionResult.Failed("Not connected")
                    val inp = input ?: return@withContext ExecutionResult.Failed("Not connected")

                    val cmd = ServerProtocol.TextCommand(text)
                    ServerProtocol.writeCommand(out, ServerProtocol.CMD_TEXT, cmd.toBytes())

                    val (success, _) = ServerProtocol.readResponse(inp)

                    if (success) {
                        ExecutionResult.Success
                    } else {
                        ExecutionResult.Failed("Text input failed on server")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Text error", e)
                    disconnect()
                    ExecutionResult.Failed("Text error: ${e.message}")
                }
            }
        }
    }
}
