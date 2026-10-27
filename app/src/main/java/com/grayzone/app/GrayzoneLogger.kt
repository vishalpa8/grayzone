package com.grayzone.app

import android.content.Context
import android.util.Log
import com.grayzone.app.logging.LogFileWriter
import com.grayzone.app.logging.LogFormat
import java.io.File

/**
 * The single logger for Grayzone. Every line goes to logcat (tag `Grayzone`, prefixed with its
 * component) and, from INFO up in debug builds / WARN up in release, to a small rotating file per
 * process that can be exported from Settings.
 *
 * - DEBUG: high-volume detail; logcat only, debug builds only. May name apps.
 * - INFO: one line per meaningful state change (service start/stop, session granted, VPN up).
 * - WARN: a recoverable problem; always include the cause.
 * - ERROR: a failure the user will notice; always include the throwable.
 */
enum class LogComponent {
    OVERLAY,
    SESSION,
    VPN,
    DNS,
    DATABASE,
    SCHEDULE,
    BOOT,
    ACCESSIBILITY,
    PERMISSIONS,
    UI,
    CLEANUP,
    PREFS
}

enum class LogLevel(val letter: Char) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E')
}

object GrayzoneLogger {
    private const val TAG = "Grayzone"
    private const val LOG_DIR = "logs"

    // The debug build type is deliberately non-debuggable (it mirrors release), so DEBUG is false
    // there too; key it off the build type so debug installs stay diagnosable and release quiet.
    private val verbose = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "debug"

    @Volatile private var writer: LogFileWriter? = null
    @Volatile private var processLabel = "main"

    /** Logcat gating. */
    fun shouldLog(level: LogLevel): Boolean = shouldLog(level, verbose)

    /** File gating: DEBUG never reaches the file. */
    fun shouldPersist(level: LogLevel): Boolean = shouldPersist(level, verbose)

    internal fun shouldLog(level: LogLevel, verbose: Boolean): Boolean =
        verbose || level >= LogLevel.WARN

    internal fun shouldPersist(level: LogLevel, verbose: Boolean): Boolean =
        if (verbose) level >= LogLevel.INFO else level >= LogLevel.WARN

    /** Directory holding every process's log files. */
    fun logDirectory(context: Context): File = File(context.filesDir, LOG_DIR)

    /**
     * Call first in Application.onCreate of every process. Sets up this process's file and a crash
     * handler that records the cause before the default handler kills the process.
     */
    fun init(context: Context, processName: String?) {
        if (writer != null) return
        processLabel = if (processName?.endsWith(":a11y") == true) "a11y" else "main"
        val fileWriter = LogFileWriter(File(logDirectory(context), "$processLabel.log"))
        writer = fileWriter

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "[BOOT] Uncaught crash on ${thread.name}", throwable)
                // Lines still queued are the context leading up to the crash; get them on disk first.
                fileWriter.flush(500L)
                fileWriter.appendSync(format(LogLevel.ERROR, LogComponent.BOOT, "Uncaught crash on ${thread.name}", throwable))
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No platform handler to kill us: don't leave the process running half-broken.
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** Waits for queued file writes (before exporting). */
    fun flush(timeoutMs: Long = 2_000L) {
        writer?.flush(timeoutMs)
    }

    fun d(component: LogComponent, message: String, data: Map<String, Any?> = emptyMap()) {
        if (!shouldLog(LogLevel.DEBUG)) return
        val dataStr = if (data.isNotEmpty()) " | $data" else ""
        Log.d(TAG, "[${component.name}] $message$dataStr")
    }

    fun i(component: LogComponent, message: String) {
        if (shouldLog(LogLevel.INFO)) Log.i(TAG, "[${component.name}] $message")
        persist(LogLevel.INFO, component, message, null)
    }

    fun w(component: LogComponent, message: String, throwable: Throwable? = null) {
        if (shouldLog(LogLevel.WARN)) Log.w(TAG, "[${component.name}] $message", throwable)
        persist(LogLevel.WARN, component, message, throwable)
    }

    fun e(component: LogComponent, message: String, throwable: Throwable? = null) {
        if (shouldLog(LogLevel.ERROR)) Log.e(TAG, "[${component.name}] $message", throwable)
        persist(LogLevel.ERROR, component, message, throwable)
    }

    private fun persist(level: LogLevel, component: LogComponent, message: String, throwable: Throwable?) {
        if (!shouldPersist(level)) return
        val w = writer ?: return
        w.append(format(level, component, message, throwable))
    }

    private fun format(level: LogLevel, component: LogComponent, message: String, throwable: Throwable?): String =
        LogFormat.line(
            timeMs = System.currentTimeMillis(),
            level = level.letter,
            process = processLabel,
            thread = Thread.currentThread().name,
            component = component.name,
            message = message,
            throwable = throwable
        )
}
