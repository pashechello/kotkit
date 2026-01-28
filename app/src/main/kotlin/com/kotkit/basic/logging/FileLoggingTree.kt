package com.kotkit.basic.logging

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Timber Tree that writes logs to a file on the device.
 *
 * Logs are saved to: /sdcard/Android/data/com.kotkit.basic/files/logs/
 *
 * Features:
 * - Writes all log levels (DEBUG, INFO, WARN, ERROR)
 * - Rotates logs daily (keeps last 7 days)
 * - Thread-safe async writing
 * - Includes timestamps and tags
 *
 * To retrieve logs:
 * adb pull /sdcard/Android/data/com.kotkit.basic/files/logs/
 */
class FileLoggingTree(context: Context) : Timber.Tree() {

    private val logDir: File = File(context.getExternalFilesDir(null), "logs").also {
        it.mkdirs()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // Single thread executor for async file writing
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FileLogger").apply { isDaemon = true }
    }

    // Keep last 7 days of logs
    private val maxLogDays = 7

    init {
        cleanOldLogs()
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = dateFormat.format(Date())
        val priorityChar = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            Log.ASSERT -> 'A'
            else -> '?'
        }

        val logLine = buildString {
            append(timestamp)
            append(" ")
            append(priorityChar)
            append("/")
            append(tag ?: "NoTag")
            append(": ")
            append(message)
            if (t != null) {
                append("\n")
                append(Log.getStackTraceString(t))
            }
        }

        // Write async to not block main thread
        executor.execute {
            writeToFile(logLine)
        }
    }

    private fun writeToFile(logLine: String) {
        try {
            val fileName = "kotkit_${fileDateFormat.format(Date())}.log"
            val logFile = File(logDir, fileName)

            FileWriter(logFile, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println(logLine)
                }
            }
        } catch (e: Exception) {
            // Can't log to file, at least try logcat
            Log.e("FileLoggingTree", "Failed to write log: ${e.message}")
        }
    }

    private fun cleanOldLogs() {
        executor.execute {
            try {
                val cutoffTime = System.currentTimeMillis() - (maxLogDays * 24 * 60 * 60 * 1000L)
                logDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoffTime) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e("FileLoggingTree", "Failed to clean old logs: ${e.message}")
            }
        }
    }

    /**
     * Get path to logs directory for display in UI
     */
    fun getLogsPath(): String = logDir.absolutePath

    /**
     * Get list of log files
     */
    fun getLogFiles(): List<File> = logDir.listFiles()?.toList() ?: emptyList()

    /**
     * Read content of today's log file
     */
    fun getTodayLogs(): String {
        val fileName = "kotkit_${fileDateFormat.format(Date())}.log"
        val logFile = File(logDir, fileName)
        return if (logFile.exists()) {
            logFile.readText()
        } else {
            "No logs for today"
        }
    }
}
