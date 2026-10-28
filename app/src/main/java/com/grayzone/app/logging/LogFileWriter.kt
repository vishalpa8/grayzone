package com.grayzone.app.logging

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Appends log lines to [file] off the calling thread, rotating it to `<file>.1` once it would
 * exceed [maxBytes] (so at most two files of [maxBytes] each exist). One instance per process:
 * each process owns its own file, so no cross-process locking.
 * Never throws — logging must not be able to break the app.
 */
class LogFileWriter(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GrayzoneLogWriter").apply { isDaemon = true }
    },
) {
    private val lock = Any()

    val backupFile: File get() = File(file.path + ".1")

    /** Queues [line] for writing; returns immediately. */
    fun append(line: String) {
        try {
            executor.execute { writeNow(line) }
        } catch (_: Exception) {
            // Executor shut down (process dying): nothing left to do.
        }
    }

    /** Writes [line] on the calling thread. Used for crashes, where the process is about to die. */
    fun appendSync(line: String) = writeNow(line)

    /** Blocks until everything queued so far is on disk, or [timeoutMs] passes. */
    fun flush(timeoutMs: Long = 2_000L) {
        try {
            executor.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun writeNow(line: String) {
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                var bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                // A single entry (e.g. a huge stack trace) can never exceed the cap on its own.
                if (bytes.size > maxBytes) bytes = bytes.copyOf(maxBytes.toInt())
                if (file.exists() && file.length() + bytes.size > maxBytes) {
                    val backup = backupFile
                    if (backup.exists()) backup.delete()
                    // If the rename fails, start over rather than let the file grow without bound.
                    if (!file.renameTo(backup)) file.writeBytes(ByteArray(0))
                }
                file.appendBytes(bytes)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024
    }
}
