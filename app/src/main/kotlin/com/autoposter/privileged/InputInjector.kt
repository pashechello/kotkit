package com.autoposter.privileged

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * InputInjector writes input events directly to /dev/input/eventX.
 *
 * This requires shell UID (2000) or root, which is why it runs in the
 * Privileged Server started via app_process.
 *
 * The injector:
 * 1. Finds the touchscreen device by examining device capabilities
 * 2. Opens the device for writing
 * 3. Writes struct input_event for touch actions
 *
 * struct input_event {
 *     struct timeval time;  // 16 bytes (tv_sec: 8, tv_usec: 8)
 *     __u16 type;           // 2 bytes
 *     __u16 code;           // 2 bytes
 *     __s32 value;          // 4 bytes
 * };
 * Total: 24 bytes per event
 */
class InputInjector {

    companion object {
        private const val TAG = "InputInjector"

        // Event sizes
        const val INPUT_EVENT_SIZE = 24

        // Event types
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_ABS = 0x03

        // Sync codes
        const val SYN_REPORT = 0x00
        const val SYN_MT_REPORT = 0x02

        // Key codes
        const val BTN_TOUCH = 0x14a
        const val BTN_TOOL_FINGER = 0x145

        // Absolute codes (multitouch)
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_TOUCH_MAJOR = 0x30
        const val ABS_MT_TOUCH_MINOR = 0x31
        const val ABS_MT_WIDTH_MAJOR = 0x32
        const val ABS_MT_WIDTH_MINOR = 0x33
        const val ABS_MT_ORIENTATION = 0x34
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val ABS_MT_PRESSURE = 0x3a
        const val ABS_MT_DISTANCE = 0x3b
    }

    private var devicePath: String? = null
    private var deviceFd: FileDescriptor? = null
    private var deviceFile: RandomAccessFile? = null

    // Device capabilities
    var maxX: Int = 1080
        private set
    var maxY: Int = 2400
        private set
    var maxPressure: Int = 255
        private set
    var maxTouchMajor: Int = 255
        private set

    /**
     * Initialize the injector by finding and opening the touch device.
     */
    fun initialize(): Boolean {
        return try {
            // Find touch device
            devicePath = findTouchDevice()
            if (devicePath == null) {
                println("[$TAG] Touch device not found")
                return false
            }

            println("[$TAG] Found touch device: $devicePath")

            // Parse device capabilities
            parseDeviceCapabilities()

            // Open device for writing
            deviceFile = RandomAccessFile(devicePath, "rw")
            deviceFd = deviceFile!!.fd

            println("[$TAG] Opened $devicePath for writing")
            println("[$TAG] Capabilities: maxX=$maxX, maxY=$maxY, maxPressure=$maxPressure")

            true
        } catch (e: Exception) {
            println("[$TAG] Failed to initialize: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Find touch device by examining /dev/input/event* capabilities.
     */
    private fun findTouchDevice(): String? {
        val inputDir = File("/dev/input")
        val eventDevices = inputDir.listFiles()?.filter {
            it.name.startsWith("event")
        } ?: return null

        for (device in eventDevices) {
            if (isTouchDevice(device.absolutePath)) {
                return device.absolutePath
            }
        }

        return null
    }

    /**
     * Check if device is a touchscreen by running getevent -p.
     */
    private fun isTouchDevice(devicePath: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getevent", "-p", devicePath))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Look for multitouch capabilities
            output.contains("ABS_MT_POSITION_X") && output.contains("ABS_MT_POSITION_Y")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parse device capabilities using getevent -p.
     */
    private fun parseDeviceCapabilities() {
        val path = devicePath ?: return

        try {
            val process = Runtime.getRuntime().exec(arrayOf("getevent", "-p", path))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Parse ABS_MT_POSITION_X max value
            val xMatch = Regex("""ABS_MT_POSITION_X.*max (\d+)""").find(output)
            xMatch?.groupValues?.get(1)?.toIntOrNull()?.let { maxX = it }

            // Parse ABS_MT_POSITION_Y max value
            val yMatch = Regex("""ABS_MT_POSITION_Y.*max (\d+)""").find(output)
            yMatch?.groupValues?.get(1)?.toIntOrNull()?.let { maxY = it }

            // Parse ABS_MT_PRESSURE max value
            val pressureMatch = Regex("""ABS_MT_PRESSURE.*max (\d+)""").find(output)
            pressureMatch?.groupValues?.get(1)?.toIntOrNull()?.let { maxPressure = it }

            // Parse ABS_MT_TOUCH_MAJOR max value
            val touchMajorMatch = Regex("""ABS_MT_TOUCH_MAJOR.*max (\d+)""").find(output)
            touchMajorMatch?.groupValues?.get(1)?.toIntOrNull()?.let { maxTouchMajor = it }
        } catch (e: Exception) {
            println("[$TAG] Failed to parse capabilities: ${e.message}")
        }
    }

    /**
     * Inject a list of input events.
     */
    fun injectEvents(events: List<ServerProtocol.InputEvent>) {
        val file = deviceFile ?: throw IllegalStateException("Not initialized")

        for (event in events) {
            writeEvent(file, event.type, event.code, event.value)
        }
    }

    /**
     * Inject a single tap at coordinates.
     */
    fun tap(x: Int, y: Int, pressure: Int = 150, touchMajor: Int = 50, trackingId: Int = 1) {
        val file = deviceFile ?: throw IllegalStateException("Not initialized")

        // Touch down
        writeEvent(file, EV_ABS, ABS_MT_TRACKING_ID, trackingId)
        writeEvent(file, EV_ABS, ABS_MT_POSITION_X, x)
        writeEvent(file, EV_ABS, ABS_MT_POSITION_Y, y)
        writeEvent(file, EV_ABS, ABS_MT_PRESSURE, pressure)
        writeEvent(file, EV_ABS, ABS_MT_TOUCH_MAJOR, touchMajor)
        writeEvent(file, EV_KEY, BTN_TOUCH, 1)
        writeEvent(file, EV_SYN, SYN_REPORT, 0)

        // Touch up
        writeEvent(file, EV_ABS, ABS_MT_TRACKING_ID, -1)
        writeEvent(file, EV_KEY, BTN_TOUCH, 0)
        writeEvent(file, EV_SYN, SYN_REPORT, 0)
    }

    /**
     * Write a single input event.
     *
     * struct input_event {
     *     struct timeval time;  // 16 bytes
     *     __u16 type;
     *     __u16 code;
     *     __s32 value;
     * };
     */
    private fun writeEvent(file: RandomAccessFile, type: Int, code: Int, value: Int) {
        val buffer = ByteBuffer.allocate(INPUT_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // timeval (16 bytes)
        val nowMs = System.currentTimeMillis()
        val sec = nowMs / 1000
        val usec = (nowMs % 1000) * 1000

        buffer.putLong(sec)      // tv_sec
        buffer.putLong(usec)     // tv_usec

        // Event data
        buffer.putShort(type.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(value)

        file.write(buffer.array())
    }

    /**
     * Get device info for client.
     */
    fun getDeviceInfo(): ServerProtocol.DeviceInfo {
        return ServerProtocol.DeviceInfo(
            devicePath = devicePath ?: "",
            screenWidth = maxX,
            screenHeight = maxY,
            maxX = maxX,
            maxY = maxY,
            maxPressure = maxPressure,
            maxTouchMajor = maxTouchMajor
        )
    }

    /**
     * Close the device.
     */
    fun close() {
        try {
            deviceFile?.close()
        } catch (e: Exception) {
            println("[$TAG] Error closing device: ${e.message}")
        }
        deviceFile = null
        deviceFd = null
    }
}
