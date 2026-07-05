package com.eve.agent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReporter appends structured error entries to a rolling crash.log file
 * in internal storage.
 *
 * Log lines are prefixed with ISO-8601 timestamps so they are easy to parse.
 * The log file is capped at [MAX_BYTES]; when the cap is exceeded the file
 * is rotated (previous content dropped).
 */
class CrashReporter(context: Context) {

    private val logFile = File(context.filesDir, "crash.log")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    companion object {
        private const val MAX_BYTES = 512 * 1024L   // 512 KB
    }

    /**
     * Append an error entry to the crash log.
     * Thread-safe via [synchronized].
     */
    @Synchronized
    fun logError(error: String, tag: String = "EVE") {
        if (logFile.length() > MAX_BYTES) rotate()
        val timestamp = dateFormat.format(Date())
        logFile.appendText("[$timestamp][$tag] $error\n")
    }

    /**
     * Append a stack-trace to the log.
     */
    fun logException(throwable: Throwable, tag: String = "EVE") {
        logError("${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                 throwable.stackTraceToString(), tag)
    }

    /**
     * Read the current log file contents.
     */
    fun readLog(): String = if (logFile.exists()) logFile.readText() else ""

    /**
     * Delete the log file.
     */
    @Synchronized
    fun clearLog() {
        logFile.delete()
    }

    private fun rotate() {
        logFile.delete()
    }
}
