package com.autoposter.privileged

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ServerProtocol defines the IPC protocol between the app and Privileged Server.
 *
 * Message format:
 * [type (1 byte)] [length (4 bytes)] [payload (length bytes)]
 *
 * Commands:
 * - AUTH: Client authentication (must be first command)
 * - PING: Health check
 * - TAP: Single tap with humanized events
 * - SWIPE: Swipe gesture with path
 * - TEXT: Type text using key events
 * - GET_DEVICE_INFO: Get touch device information
 *
 * Security:
 * - Client must authenticate before sending other commands
 * - All payload sizes are validated to prevent OOM attacks
 * - Array counts are bounded to prevent resource exhaustion
 */
object ServerProtocol {

    // Command types
    const val CMD_AUTH: Byte = 0x00             // Authentication (must be first)
    const val CMD_PING: Byte = 0x01
    const val CMD_TAP: Byte = 0x02
    const val CMD_SWIPE: Byte = 0x03
    const val CMD_TEXT: Byte = 0x04
    const val CMD_GET_DEVICE_INFO: Byte = 0x05
    const val CMD_SHUTDOWN: Byte = 0xFF.toByte()

    // Response types
    const val RESP_OK: Byte = 0x00
    const val RESP_ERROR: Byte = 0x01
    const val RESP_AUTH_REQUIRED: Byte = 0x02   // Authentication required

    // Security limits to prevent OOM and resource exhaustion
    const val MAX_COMMAND_SIZE = 1024 * 1024  // 1 MB max payload
    const val MAX_EVENTS = 10000               // Max events per command
    const val MAX_POINTS = 1000                // Max points in swipe path
    const val MAX_TEXT_LENGTH = 10000          // Max text input length
    const val MAX_DEVICE_PATH_LENGTH = 256     // Max device path length

    // Authentication
    const val AUTH_TOKEN_LENGTH = 32           // 256-bit token

    /**
     * Authentication command payload.
     */
    data class AuthCommand(
        val token: ByteArray
    ) {
        companion object {
            fun fromBytes(bytes: ByteArray): AuthCommand {
                require(bytes.size == AUTH_TOKEN_LENGTH) {
                    "Invalid auth token size: ${bytes.size} (expected: $AUTH_TOKEN_LENGTH)"
                }
                return AuthCommand(bytes.copyOf())
            }
        }

        fun toBytes(): ByteArray = token.copyOf()

        /**
         * Constant-time comparison to prevent timing attacks.
         */
        fun verifyToken(expected: ByteArray): Boolean {
            if (token.size != expected.size) return false
            var result = 0
            for (i in token.indices) {
                result = result or (token[i].toInt() xor expected[i].toInt())
            }
            return result == 0
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AuthCommand
            return token.contentEquals(other.token)
        }

        override fun hashCode(): Int = token.contentHashCode()
    }

    /**
     * Input event structure (matches Linux input_event).
     */
    data class InputEvent(
        val type: Int,      // Event type (EV_KEY, EV_ABS, etc.)
        val code: Int,      // Event code
        val value: Int      // Event value
    ) {
        companion object {
            const val SIZE = 8 // type (2) + code (2) + value (4)

            fun fromBytes(bytes: ByteArray, offset: Int = 0): InputEvent {
                val buffer = ByteBuffer.wrap(bytes, offset, SIZE).order(ByteOrder.LITTLE_ENDIAN)
                return InputEvent(
                    type = buffer.short.toInt() and 0xFFFF,
                    code = buffer.short.toInt() and 0xFFFF,
                    value = buffer.int
                )
            }
        }

        fun toBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(type.toShort())
            buffer.putShort(code.toShort())
            buffer.putInt(value)
            return buffer.array()
        }
    }

    /**
     * Tap command payload.
     */
    data class TapCommand(
        val x: Int,
        val y: Int,
        val events: List<InputEvent>
    ) {
        companion object {
            fun fromBytes(bytes: ByteArray): TapCommand {
                require(bytes.size >= 12) { "TapCommand too short: ${bytes.size}" }

                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val x = buffer.int
                val y = buffer.int
                val eventCount = buffer.int

                // Bounds checking to prevent OOM
                require(eventCount in 0..MAX_EVENTS) {
                    "Event count out of bounds: $eventCount (max: $MAX_EVENTS)"
                }

                // Verify we have enough bytes for all events
                val expectedSize = 12 + eventCount * InputEvent.SIZE
                require(bytes.size >= expectedSize) {
                    "TapCommand buffer too small: ${bytes.size} < $expectedSize"
                }

                val events = mutableListOf<InputEvent>()
                repeat(eventCount) {
                    val eventBytes = ByteArray(InputEvent.SIZE)
                    buffer.get(eventBytes)
                    events.add(InputEvent.fromBytes(eventBytes))
                }

                return TapCommand(x, y, events)
            }
        }

        fun toBytes(): ByteArray {
            val eventBytes = events.flatMap { it.toBytes().toList() }.toByteArray()
            val buffer = ByteBuffer.allocate(12 + eventBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(x)
            buffer.putInt(y)
            buffer.putInt(events.size)
            buffer.put(eventBytes)
            return buffer.array()
        }
    }

    /**
     * Swipe command payload.
     */
    data class SwipeCommand(
        val points: List<Pair<Int, Int>>,
        val durationMs: Long,
        val events: List<InputEvent>
    ) {
        companion object {
            fun fromBytes(bytes: ByteArray): SwipeCommand {
                require(bytes.size >= 12) { "SwipeCommand too short: ${bytes.size}" }

                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val pointCount = buffer.int
                val durationMs = buffer.long

                // Bounds checking for points
                require(pointCount in 0..MAX_POINTS) {
                    "Point count out of bounds: $pointCount (max: $MAX_POINTS)"
                }

                // Verify we have enough bytes for points header
                val pointsSize = pointCount * 8 // 4 bytes x + 4 bytes y per point
                require(bytes.size >= 12 + pointsSize + 4) {
                    "SwipeCommand buffer too small for points"
                }

                val points = mutableListOf<Pair<Int, Int>>()
                repeat(pointCount) {
                    val x = buffer.int
                    val y = buffer.int
                    points.add(Pair(x, y))
                }

                val eventCount = buffer.int

                // Bounds checking for events
                require(eventCount in 0..MAX_EVENTS) {
                    "Event count out of bounds: $eventCount (max: $MAX_EVENTS)"
                }

                // Verify we have enough bytes for all events
                val expectedSize = 12 + pointsSize + 4 + eventCount * InputEvent.SIZE
                require(bytes.size >= expectedSize) {
                    "SwipeCommand buffer too small: ${bytes.size} < $expectedSize"
                }

                val events = mutableListOf<InputEvent>()
                repeat(eventCount) {
                    val eventBytes = ByteArray(InputEvent.SIZE)
                    buffer.get(eventBytes)
                    events.add(InputEvent.fromBytes(eventBytes))
                }

                return SwipeCommand(points, durationMs, events)
            }
        }

        fun toBytes(): ByteArray {
            val eventBytes = events.flatMap { it.toBytes().toList() }.toByteArray()
            val size = 4 + 8 + (points.size * 8) + 4 + eventBytes.size
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

            buffer.putInt(points.size)
            buffer.putLong(durationMs)
            points.forEach { (x, y) ->
                buffer.putInt(x)
                buffer.putInt(y)
            }
            buffer.putInt(events.size)
            buffer.put(eventBytes)

            return buffer.array()
        }
    }

    /**
     * Text input command payload.
     */
    data class TextCommand(
        val text: String
    ) {
        companion object {
            fun fromBytes(bytes: ByteArray): TextCommand {
                require(bytes.size <= MAX_TEXT_LENGTH) {
                    "Text too long: ${bytes.size} (max: $MAX_TEXT_LENGTH)"
                }
                return TextCommand(String(bytes, Charsets.UTF_8))
            }
        }

        fun toBytes(): ByteArray {
            return text.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Device info response.
     */
    data class DeviceInfo(
        val devicePath: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val maxX: Int,
        val maxY: Int,
        val maxPressure: Int,
        val maxTouchMajor: Int
    ) {
        companion object {
            fun fromBytes(bytes: ByteArray): DeviceInfo {
                require(bytes.size >= 4) { "DeviceInfo too short: ${bytes.size}" }

                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val pathLength = buffer.int

                // Bounds checking for path length
                require(pathLength in 0..MAX_DEVICE_PATH_LENGTH) {
                    "Device path too long: $pathLength (max: $MAX_DEVICE_PATH_LENGTH)"
                }

                // Verify we have enough bytes
                val expectedSize = 4 + pathLength + 24 // path + 6 ints
                require(bytes.size >= expectedSize) {
                    "DeviceInfo buffer too small: ${bytes.size} < $expectedSize"
                }

                val pathBytes = ByteArray(pathLength)
                buffer.get(pathBytes)
                val devicePath = String(pathBytes, Charsets.UTF_8)

                return DeviceInfo(
                    devicePath = devicePath,
                    screenWidth = buffer.int,
                    screenHeight = buffer.int,
                    maxX = buffer.int,
                    maxY = buffer.int,
                    maxPressure = buffer.int,
                    maxTouchMajor = buffer.int
                )
            }
        }

        fun toBytes(): ByteArray {
            val pathBytes = devicePath.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + pathBytes.size + 24).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(pathBytes.size)
            buffer.put(pathBytes)
            buffer.putInt(screenWidth)
            buffer.putInt(screenHeight)
            buffer.putInt(maxX)
            buffer.putInt(maxY)
            buffer.putInt(maxPressure)
            buffer.putInt(maxTouchMajor)
            return buffer.array()
        }
    }

    /**
     * Read command from stream.
     * @throws IllegalArgumentException if payload size exceeds MAX_COMMAND_SIZE
     */
    fun readCommand(input: DataInputStream): Pair<Byte, ByteArray> {
        val type = input.readByte()
        val length = input.readInt()

        // Validate payload size to prevent OOM attacks
        require(length in 0..MAX_COMMAND_SIZE) {
            "Payload size out of bounds: $length (max: $MAX_COMMAND_SIZE)"
        }

        val payload = if (length > 0) {
            ByteArray(length).also { input.readFully(it) }
        } else {
            ByteArray(0)
        }

        return Pair(type, payload)
    }

    /**
     * Write command to stream.
     */
    fun writeCommand(output: DataOutputStream, type: Byte, payload: ByteArray = ByteArray(0)) {
        output.writeByte(type.toInt())
        output.writeInt(payload.size)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    /**
     * Write response to stream.
     */
    fun writeResponse(output: DataOutputStream, success: Boolean, payload: ByteArray = ByteArray(0)) {
        val type = if (success) RESP_OK else RESP_ERROR
        writeCommand(output, type, payload)
    }

    /**
     * Read response from stream.
     */
    fun readResponse(input: DataInputStream): Pair<Boolean, ByteArray> {
        val (type, payload) = readCommand(input)
        return Pair(type == RESP_OK, payload)
    }
}
