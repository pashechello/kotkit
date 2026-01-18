package com.autoposter.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB Protocol implementation.
 *
 * ADB uses a simple message-based protocol over TCP. Each message has:
 * - 24-byte header
 * - Optional payload (up to maxPayload bytes)
 *
 * Header format (little-endian):
 * - command: 4 bytes (e.g., "CNXN", "AUTH", "OPEN", "WRTE", "CLSE", "OKAY")
 * - arg0: 4 bytes
 * - arg1: 4 bytes
 * - data_length: 4 bytes
 * - data_crc32: 4 bytes
 * - magic: 4 bytes (command XOR 0xFFFFFFFF)
 */
object AdbProtocol {

    // Protocol version
    const val VERSION = 0x01000001

    // Max payload size (older devices: 4096, newer: 256KB)
    const val MAX_PAYLOAD = 256 * 1024

    // Commands (4-byte ASCII encoded as little-endian int)
    val CMD_CNXN = commandToInt("CNXN")  // Connect
    val CMD_AUTH = commandToInt("AUTH")  // Authentication
    val CMD_OPEN = commandToInt("OPEN")  // Open stream
    val CMD_OKAY = commandToInt("OKAY")  // Ready/ACK
    val CMD_CLSE = commandToInt("CLSE")  // Close stream
    val CMD_WRTE = commandToInt("WRTE")  // Write data

    // AUTH types
    const val AUTH_TYPE_TOKEN = 1       // Request token from device
    const val AUTH_TYPE_SIGNATURE = 2   // Send signed token
    const val AUTH_TYPE_RSAPUBLICKEY = 3 // Send public key

    // Header size in bytes
    const val HEADER_SIZE = 24

    /**
     * ADB Message structure
     */
    data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray = ByteArray(0)
    ) {
        /**
         * Serialize message to bytes for sending
         */
        fun toBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            val dataLength = payload.size
            val dataCrc = crc32(payload)
            val magic = command xor 0xFFFFFFFF.toInt()

            buffer.putInt(command)
            buffer.putInt(arg0)
            buffer.putInt(arg1)
            buffer.putInt(dataLength)
            buffer.putInt(dataCrc)
            buffer.putInt(magic)
            buffer.put(payload)

            return buffer.array()
        }

        /**
         * Get command as string (for logging)
         */
        fun commandString(): String = intToCommand(command)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AdbMessage
            if (command != other.command) return false
            if (arg0 != other.arg0) return false
            if (arg1 != other.arg1) return false
            if (!payload.contentEquals(other.payload)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + arg0
            result = 31 * result + arg1
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Create CNXN (connect) message
     */
    fun createConnectMessage(maxPayload: Int = MAX_PAYLOAD): AdbMessage {
        val systemIdentity = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send,openscreen_mdns"
        val payload = systemIdentity.toByteArray(Charsets.UTF_8) + 0.toByte()

        return AdbMessage(
            command = CMD_CNXN,
            arg0 = VERSION,
            arg1 = maxPayload,
            payload = payload
        )
    }

    /**
     * Create AUTH message with signature
     */
    fun createAuthSignatureMessage(signature: ByteArray): AdbMessage {
        return AdbMessage(
            command = CMD_AUTH,
            arg0 = AUTH_TYPE_SIGNATURE,
            arg1 = 0,
            payload = signature
        )
    }

    /**
     * Create AUTH message with public key
     */
    fun createAuthPublicKeyMessage(publicKey: ByteArray): AdbMessage {
        return AdbMessage(
            command = CMD_AUTH,
            arg0 = AUTH_TYPE_RSAPUBLICKEY,
            arg1 = 0,
            payload = publicKey
        )
    }

    /**
     * Create OPEN message to start a stream (e.g., shell)
     */
    fun createOpenMessage(localId: Int, destination: String): AdbMessage {
        val payload = destination.toByteArray(Charsets.UTF_8) + 0.toByte()
        return AdbMessage(
            command = CMD_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = payload
        )
    }

    /**
     * Create WRTE (write) message
     */
    fun createWriteMessage(localId: Int, remoteId: Int, data: ByteArray): AdbMessage {
        return AdbMessage(
            command = CMD_WRTE,
            arg0 = localId,
            arg1 = remoteId,
            payload = data
        )
    }

    /**
     * Create OKAY message
     */
    fun createOkayMessage(localId: Int, remoteId: Int): AdbMessage {
        return AdbMessage(
            command = CMD_OKAY,
            arg0 = localId,
            arg1 = remoteId
        )
    }

    /**
     * Create CLSE (close) message
     */
    fun createCloseMessage(localId: Int, remoteId: Int): AdbMessage {
        return AdbMessage(
            command = CMD_CLSE,
            arg0 = localId,
            arg1 = remoteId
        )
    }

    /**
     * Parse header bytes into partial message (without payload)
     */
    fun parseHeader(headerBytes: ByteArray): HeaderInfo {
        require(headerBytes.size == HEADER_SIZE) { "Header must be $HEADER_SIZE bytes" }

        val buffer = ByteBuffer.wrap(headerBytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val dataCrc = buffer.int
        val magic = buffer.int

        // Verify magic
        val expectedMagic = command xor 0xFFFFFFFF.toInt()
        if (magic != expectedMagic) {
            throw AdbProtocolException("Invalid magic: expected $expectedMagic, got $magic")
        }

        return HeaderInfo(command, arg0, arg1, dataLength, dataCrc)
    }

    /**
     * Parse complete message from header + payload
     */
    fun parseMessage(headerInfo: HeaderInfo, payload: ByteArray): AdbMessage {
        // Verify CRC
        val actualCrc = crc32(payload)
        if (actualCrc != headerInfo.dataCrc) {
            throw AdbProtocolException("CRC mismatch: expected ${headerInfo.dataCrc}, got $actualCrc")
        }

        return AdbMessage(
            command = headerInfo.command,
            arg0 = headerInfo.arg0,
            arg1 = headerInfo.arg1,
            payload = payload
        )
    }

    /**
     * Header info parsed from first 24 bytes
     */
    data class HeaderInfo(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCrc: Int
    )

    /**
     * Convert 4-char command string to little-endian int
     */
    private fun commandToInt(cmd: String): Int {
        require(cmd.length == 4) { "Command must be 4 characters" }
        return (cmd[0].code) or
                (cmd[1].code shl 8) or
                (cmd[2].code shl 16) or
                (cmd[3].code shl 24)
    }

    /**
     * Convert int command back to string
     */
    private fun intToCommand(cmd: Int): String {
        return String(
            charArrayOf(
                (cmd and 0xFF).toChar(),
                ((cmd shr 8) and 0xFF).toChar(),
                ((cmd shr 16) and 0xFF).toChar(),
                ((cmd shr 24) and 0xFF).toChar()
            )
        )
    }

    /**
     * Calculate CRC32 of data (ADB uses non-standard CRC)
     */
    private fun crc32(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc += byte.toInt() and 0xFF
        }
        return crc
    }
}

class AdbProtocolException(message: String) : Exception(message)
