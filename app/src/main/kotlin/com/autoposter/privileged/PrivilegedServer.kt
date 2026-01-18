package com.autoposter.privileged

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PrivilegedServer - Background server running with shell UID (2000).
 *
 * This server is launched via app_process command through ADB:
 * CLASSPATH=/path/to/apk app_process / com.autoposter.privileged.PrivilegedServer --socket=autoposter_privileged
 *
 * Running with shell UID gives us access to:
 * - /dev/input/eventX (for direct input injection)
 * - Various system commands
 *
 * The server listens on a LocalServerSocket and accepts commands from the main app.
 *
 * Commands:
 * - PING: Health check
 * - TAP: Inject tap with humanized events
 * - SWIPE: Inject swipe gesture
 * - TEXT: Type text using key events
 * - GET_DEVICE_INFO: Get touch device capabilities
 * - SHUTDOWN: Stop the server
 */
class PrivilegedServer(
    private val socketName: String,
    private val authToken: ByteArray  // Required - server won't start without valid token
) {

    companion object {
        private const val TAG = "PrivilegedServer"
        const val DEFAULT_SOCKET_NAME = "autoposter_privileged"

        // Thread pool limits to prevent resource exhaustion
        private const val MAX_THREADS = 4
        private const val THREAD_KEEP_ALIVE_SECONDS = 60L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        // Socket timeouts
        private const val SOCKET_READ_TIMEOUT_MS = 30_000

        // Authentication
        private const val MAX_AUTH_ATTEMPTS = 3

        /**
         * Entry point for app_process.
         * Usage: CLASSPATH=/path/to/apk app_process / com.autoposter.privileged.PrivilegedServer [--socket=name] [--data-dir=path]
         *
         * SECURITY: The server requires an auth token from the app's data directory.
         * This prevents unauthorized processes from controlling input injection.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            println("[$TAG] Starting Privileged Server")
            println("[$TAG] UID: ${android.os.Process.myUid()}")
            println("[$TAG] PID: ${android.os.Process.myPid()}")

            // Parse arguments
            var socketName = DEFAULT_SOCKET_NAME
            var dataDir: String? = null

            for (arg in args) {
                when {
                    arg.startsWith("--socket=") -> {
                        socketName = arg.removePrefix("--socket=")
                    }
                    arg.startsWith("--data-dir=") -> {
                        dataDir = arg.removePrefix("--data-dir=")
                    }
                }
            }

            println("[$TAG] Socket name: $socketName")
            println("[$TAG] Data dir: $dataDir")

            // SECURITY: Fail-closed - require data-dir for authentication
            if (dataDir == null) {
                println("[$TAG] FATAL: --data-dir is required for authentication")
                println("[$TAG] Server cannot start without authentication token")
                System.exit(1)
                return
            }

            // Load auth token - fail if not found
            val authToken = AuthTokenManager.loadToken(dataDir)
            if (authToken == null) {
                println("[$TAG] FATAL: Auth token not found in $dataDir")
                println("[$TAG] Ensure the app has generated a token before starting server")
                System.exit(1)
                return
            }

            println("[$TAG] Authentication enabled (token loaded)")

            // Create and run server
            val server = PrivilegedServer(socketName, authToken)

            // Handle shutdown signal
            Runtime.getRuntime().addShutdownHook(Thread {
                println("[$TAG] Shutdown signal received")
                server.stop()
            })

            // Start server (blocking)
            server.start()
        }
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null

    // Bounded thread pool to prevent resource exhaustion attacks
    private val executor = ThreadPoolExecutor(
        1,                              // corePoolSize
        MAX_THREADS,                    // maxPoolSize
        THREAD_KEEP_ALIVE_SECONDS,      // keepAliveTime
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_THREADS * 2)  // Bounded queue
    ).apply {
        // Reject new tasks when both pool and queue are full
        rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy()
    }

    private val inputInjector = InputInjector()

    /**
     * Start the server (blocking call).
     */
    fun start() {
        if (!inputInjector.initialize()) {
            println("[$TAG] Failed to initialize input injector")
            return
        }

        try {
            serverSocket = LocalServerSocket(socketName)
            isRunning.set(true)

            println("[$TAG] Server started, listening on: $socketName")

            while (isRunning.get()) {
                try {
                    val client = serverSocket!!.accept()
                    println("[$TAG] Client connected")
                    executor.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        println("[$TAG] Accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("[$TAG] Server error: ${e.message}")
            e.printStackTrace()
        } finally {
            cleanup()
        }
    }

    /**
     * Stop the server.
     */
    fun stop() {
        println("[$TAG] Stopping server")
        isRunning.set(false)

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Handle client connection.
     *
     * SECURITY: All clients must authenticate before executing commands.
     * Authentication uses constant-time token comparison to prevent timing attacks.
     */
    private fun handleClient(socket: LocalSocket) {
        var isAuthenticated = false  // Fail-closed: require explicit authentication
        var authAttempts = 0

        try {
            // Set socket timeout to prevent hanging on slow/malicious clients
            socket.soTimeout = SOCKET_READ_TIMEOUT_MS

            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)

            while (isRunning.get() && socket.isConnected) {
                try {
                    val (cmdType, payload) = ServerProtocol.readCommand(input)

                    // Handle authentication
                    if (cmdType == ServerProtocol.CMD_AUTH) {
                        authAttempts++
                        if (authAttempts > MAX_AUTH_ATTEMPTS) {
                            println("[$TAG] Too many auth attempts, disconnecting")
                            ServerProtocol.writeResponse(output, false, "Too many attempts".toByteArray())
                            break
                        }

                        val authCmd = ServerProtocol.AuthCommand.fromBytes(payload)
                        if (authCmd.verifyToken(authToken)) {
                            isAuthenticated = true
                            println("[$TAG] AUTH successful")
                            ServerProtocol.writeResponse(output, true)
                        } else {
                            println("[$TAG] AUTH failed (invalid token)")
                            ServerProtocol.writeResponse(output, false, "Invalid token".toByteArray())
                        }
                        continue
                    }

                    // Require authentication for all other commands
                    if (!isAuthenticated) {
                        println("[$TAG] Command rejected: not authenticated")
                        ServerProtocol.writeCommand(
                            output,
                            ServerProtocol.RESP_AUTH_REQUIRED,
                            "Authentication required".toByteArray()
                        )
                        continue
                    }

                    // Handle authenticated command
                    handleCommand(cmdType, payload, output)

                    // SHUTDOWN command stops the server
                    if (cmdType == ServerProtocol.CMD_SHUTDOWN) {
                        stop()
                        break
                    }
                } catch (e: Exception) {
                    if (socket.isConnected) {
                        println("[$TAG] Command error: ${e.message}")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            println("[$TAG] Client error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
            println("[$TAG] Client disconnected")
        }
    }

    /**
     * Handle a single command.
     */
    private fun handleCommand(cmdType: Byte, payload: ByteArray, output: DataOutputStream) {
        try {
            when (cmdType) {
                ServerProtocol.CMD_PING -> {
                    println("[$TAG] PING")
                    ServerProtocol.writeResponse(output, true)
                }

                ServerProtocol.CMD_TAP -> {
                    val cmd = ServerProtocol.TapCommand.fromBytes(payload)
                    println("[$TAG] TAP at (${cmd.x}, ${cmd.y}) with ${cmd.events.size} events")

                    if (cmd.events.isNotEmpty()) {
                        // Use provided events (humanized)
                        inputInjector.injectEvents(cmd.events)
                    } else {
                        // Simple tap
                        inputInjector.tap(cmd.x, cmd.y)
                    }

                    ServerProtocol.writeResponse(output, true)
                }

                ServerProtocol.CMD_SWIPE -> {
                    val cmd = ServerProtocol.SwipeCommand.fromBytes(payload)
                    println("[$TAG] SWIPE ${cmd.points.size} points, ${cmd.durationMs}ms")

                    if (cmd.events.isNotEmpty()) {
                        // Use provided events (humanized)
                        inputInjector.injectEvents(cmd.events)
                    } else {
                        // TODO: Generate swipe events
                        println("[$TAG] Raw swipe not implemented, use events")
                    }

                    ServerProtocol.writeResponse(output, true)
                }

                ServerProtocol.CMD_TEXT -> {
                    val cmd = ServerProtocol.TextCommand.fromBytes(payload)
                    println("[$TAG] TEXT: ${cmd.text.take(20)}...")
                    // TODO: Implement key event generation for text
                    ServerProtocol.writeResponse(output, true)
                }

                ServerProtocol.CMD_GET_DEVICE_INFO -> {
                    println("[$TAG] GET_DEVICE_INFO")
                    val info = inputInjector.getDeviceInfo()
                    ServerProtocol.writeResponse(output, true, info.toBytes())
                }

                ServerProtocol.CMD_SHUTDOWN -> {
                    println("[$TAG] SHUTDOWN")
                    ServerProtocol.writeResponse(output, true)
                }

                else -> {
                    println("[$TAG] Unknown command: $cmdType")
                    ServerProtocol.writeResponse(output, false, "Unknown command".toByteArray())
                }
            }
        } catch (e: Exception) {
            println("[$TAG] Command execution error: ${e.message}")
            e.printStackTrace()
            ServerProtocol.writeResponse(output, false, (e.message ?: "Error").toByteArray())
        }
    }

    /**
     * Cleanup resources.
     */
    private fun cleanup() {
        println("[$TAG] Cleaning up")
        inputInjector.close()

        // Graceful executor shutdown
        executor.shutdown()
        try {
            // Wait for tasks to complete
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                println("[$TAG] Executor didn't terminate gracefully, forcing shutdown")
                executor.shutdownNow()

                // Wait a bit more for forced shutdown
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    println("[$TAG] Executor failed to terminate")
                }
            }
        } catch (e: InterruptedException) {
            // Re-cancel if current thread was interrupted
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }

        println("[$TAG] Server stopped")
    }
}
