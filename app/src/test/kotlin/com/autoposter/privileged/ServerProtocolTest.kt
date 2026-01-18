package com.autoposter.privileged

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for ServerProtocol.
 *
 * Tests cover:
 * - Command serialization/deserialization
 * - Authentication token verification
 * - Bounds checking and security limits
 */
class ServerProtocolTest {

    // ============= AuthCommand Tests =============

    @Test
    fun `AuthCommand fromBytes creates valid command`() {
        val token = ByteArray(32) { it.toByte() }

        val authCmd = ServerProtocol.AuthCommand.fromBytes(token)

        assertTrue("Token should match", authCmd.token.contentEquals(token))
    }

    @Test
    fun `AuthCommand toBytes returns copy of token`() {
        val token = ByteArray(32) { it.toByte() }
        val authCmd = ServerProtocol.AuthCommand(token)

        val result = authCmd.toBytes()

        assertTrue("toBytes should return token", result.contentEquals(token))
        assertNotSame("toBytes should return a copy", token, result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `AuthCommand fromBytes throws on wrong size`() {
        val wrongSize = ByteArray(16)
        ServerProtocol.AuthCommand.fromBytes(wrongSize)
    }

    @Test
    fun `AuthCommand verifyToken returns true for matching token`() {
        val token = ByteArray(32) { it.toByte() }
        val authCmd = ServerProtocol.AuthCommand(token)

        assertTrue("Should verify matching token", authCmd.verifyToken(token))
    }

    @Test
    fun `AuthCommand verifyToken returns false for non-matching token`() {
        val token = ByteArray(32) { it.toByte() }
        val wrongToken = ByteArray(32) { (it + 1).toByte() }
        val authCmd = ServerProtocol.AuthCommand(token)

        assertFalse("Should reject non-matching token", authCmd.verifyToken(wrongToken))
    }

    @Test
    fun `AuthCommand verifyToken returns false for wrong size`() {
        val token = ByteArray(32) { it.toByte() }
        val wrongSize = ByteArray(16)
        val authCmd = ServerProtocol.AuthCommand(token)

        assertFalse("Should reject wrong size token", authCmd.verifyToken(wrongSize))
    }

    @Test
    fun `AuthCommand verifyToken is constant-time (timing test)`() {
        val token = ByteArray(32) { 0xFF.toByte() }
        val authCmd = ServerProtocol.AuthCommand(token)

        // Token that differs in first byte
        val earlyDiff = ByteArray(32) { 0xFF.toByte() }
        earlyDiff[0] = 0x00

        // Token that differs in last byte
        val lateDiff = ByteArray(32) { 0xFF.toByte() }
        lateDiff[31] = 0x00

        // Both should return false and take similar time
        // (We can't reliably test timing, but ensure logic works)
        assertFalse(authCmd.verifyToken(earlyDiff))
        assertFalse(authCmd.verifyToken(lateDiff))
    }

    // ============= InputEvent Tests =============

    @Test
    fun `InputEvent serialization round-trip`() {
        val event = ServerProtocol.InputEvent(0x03, 0x35, 540)

        val bytes = event.toBytes()
        val restored = ServerProtocol.InputEvent.fromBytes(bytes)

        assertEquals("Type should match", event.type, restored.type)
        assertEquals("Code should match", event.code, restored.code)
        assertEquals("Value should match", event.value, restored.value)
    }

    @Test
    fun `InputEvent SIZE constant is correct`() {
        assertEquals("InputEvent size should be 12 bytes", 12, ServerProtocol.InputEvent.SIZE)
    }

    // ============= TapCommand Tests =============

    @Test
    fun `TapCommand serialization round-trip`() {
        val events = listOf(
            ServerProtocol.InputEvent(0x03, 0x35, 540),
            ServerProtocol.InputEvent(0x03, 0x36, 960)
        )
        val tapCmd = ServerProtocol.TapCommand(540, 960, events)

        val bytes = tapCmd.toBytes()
        val restored = ServerProtocol.TapCommand.fromBytes(bytes)

        assertEquals("X should match", tapCmd.x, restored.x)
        assertEquals("Y should match", tapCmd.y, restored.y)
        assertEquals("Event count should match", tapCmd.events.size, restored.events.size)
    }

    @Test
    fun `TapCommand with empty events`() {
        val tapCmd = ServerProtocol.TapCommand(100, 200, emptyList())

        val bytes = tapCmd.toBytes()
        val restored = ServerProtocol.TapCommand.fromBytes(bytes)

        assertEquals("X should match", 100, restored.x)
        assertEquals("Y should match", 200, restored.y)
        assertTrue("Events should be empty", restored.events.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TapCommand fromBytes throws on too short buffer`() {
        val tooShort = ByteArray(8)
        ServerProtocol.TapCommand.fromBytes(tooShort)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TapCommand fromBytes throws on event count exceeding MAX_EVENTS`() {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0)  // x
        buffer.putInt(0)  // y
        buffer.putInt(ServerProtocol.MAX_EVENTS + 1)  // event count exceeds limit

        ServerProtocol.TapCommand.fromBytes(buffer.array())
    }

    // ============= SwipeCommand Tests =============

    @Test
    fun `SwipeCommand serialization round-trip`() {
        val points = listOf(Pair(100, 200), Pair(300, 400))
        val events = listOf(ServerProtocol.InputEvent(0x03, 0x35, 100))
        val swipeCmd = ServerProtocol.SwipeCommand(points, 500L, events)

        val bytes = swipeCmd.toBytes()
        val restored = ServerProtocol.SwipeCommand.fromBytes(bytes)

        assertEquals("Point count should match", points.size, restored.points.size)
        assertEquals("Duration should match", 500L, restored.durationMs)
        assertEquals("First point should match", points[0], restored.points[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SwipeCommand fromBytes throws on point count exceeding MAX_POINTS`() {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(ServerProtocol.MAX_POINTS + 1)  // point count exceeds limit
        buffer.putLong(100L)  // duration

        ServerProtocol.SwipeCommand.fromBytes(buffer.array())
    }

    // ============= TextCommand Tests =============

    @Test
    fun `TextCommand serialization round-trip`() {
        val text = "Hello World"
        val textCmd = ServerProtocol.TextCommand(text)

        val bytes = textCmd.toBytes()
        val restored = ServerProtocol.TextCommand.fromBytes(bytes)

        assertEquals("Text should match", text, restored.text)
    }

    @Test
    fun `TextCommand handles UTF-8`() {
        val text = "Привет мир 你好世界"
        val textCmd = ServerProtocol.TextCommand(text)

        val bytes = textCmd.toBytes()
        val restored = ServerProtocol.TextCommand.fromBytes(bytes)

        assertEquals("UTF-8 text should match", text, restored.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TextCommand fromBytes throws on exceeding MAX_TEXT_LENGTH`() {
        val tooLong = ByteArray(ServerProtocol.MAX_TEXT_LENGTH + 1)
        ServerProtocol.TextCommand.fromBytes(tooLong)
    }

    // ============= Protocol Read/Write Tests =============

    @Test
    fun `writeCommand and readCommand round-trip`() {
        val baos = ByteArrayOutputStream()
        val output = DataOutputStream(baos)

        val payload = "test payload".toByteArray()
        ServerProtocol.writeCommand(output, ServerProtocol.CMD_TEXT, payload)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = DataInputStream(bais)

        val (type, readPayload) = ServerProtocol.readCommand(input)

        assertEquals("Type should match", ServerProtocol.CMD_TEXT, type)
        assertTrue("Payload should match", payload.contentEquals(readPayload))
    }

    @Test
    fun `writeCommand with empty payload`() {
        val baos = ByteArrayOutputStream()
        val output = DataOutputStream(baos)

        ServerProtocol.writeCommand(output, ServerProtocol.CMD_PING)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = DataInputStream(bais)

        val (type, payload) = ServerProtocol.readCommand(input)

        assertEquals("Type should be PING", ServerProtocol.CMD_PING, type)
        assertEquals("Payload should be empty", 0, payload.size)
    }

    @Test
    fun `writeResponse and readResponse round-trip`() {
        val baos = ByteArrayOutputStream()
        val output = DataOutputStream(baos)

        ServerProtocol.writeResponse(output, true, "OK".toByteArray())

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = DataInputStream(bais)

        val (success, payload) = ServerProtocol.readResponse(input)

        assertTrue("Response should indicate success", success)
        assertEquals("Payload should match", "OK", String(payload))
    }

    @Test
    fun `readResponse returns false for error response`() {
        val baos = ByteArrayOutputStream()
        val output = DataOutputStream(baos)

        ServerProtocol.writeResponse(output, false, "Error".toByteArray())

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = DataInputStream(bais)

        val (success, _) = ServerProtocol.readResponse(input)

        assertFalse("Response should indicate failure", success)
    }

    // ============= Security Limits Tests =============

    @Test
    fun `MAX_COMMAND_SIZE is 1MB`() {
        assertEquals("Max command size should be 1MB", 1024 * 1024, ServerProtocol.MAX_COMMAND_SIZE)
    }

    @Test
    fun `AUTH_TOKEN_LENGTH is 32 bytes`() {
        assertEquals("Auth token should be 32 bytes (256 bits)", 32, ServerProtocol.AUTH_TOKEN_LENGTH)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `readCommand throws on payload exceeding MAX_COMMAND_SIZE`() {
        val baos = ByteArrayOutputStream()
        val output = DataOutputStream(baos)

        // Write header with huge size
        output.writeByte(ServerProtocol.CMD_TEXT.toInt())
        output.writeInt(ServerProtocol.MAX_COMMAND_SIZE + 1)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = DataInputStream(bais)

        ServerProtocol.readCommand(input)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `readCommand throws on negative payload size`() {
        val baos = ByteArrayOutputStream()
        val output = DataOutputStream(baos)

        // Write header with negative size
        output.writeByte(ServerProtocol.CMD_TEXT.toInt())
        output.writeInt(-1)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = DataInputStream(bais)

        ServerProtocol.readCommand(input)
    }
}
