package com.grayzone.app.logging

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure formatting + redaction for the on-device log file (unit-tested). */
object LogFormat {

    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Names that may stay readable in the exported file: Grayzone, platform/library code, and the
     * handful of system apps that show up in transitions. Apps users commonly limit (even ones
     * shipped by Google/Android, like Chrome or the Play Store) are masked.
     */
    private val readablePrefixes = listOf(
        "com.grayzone.", "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.",
        "dalvik.", "libcore.", "sun.", "com.google.android.material.",
        "com.android.internal.", "com.android.server.", "com.android.systemui", "com.android.settings",
        "com.android.launcher", "com.android.incallui", "com.android.providers.",
    )

    // Package names / long host names: three or more dot-separated labels starting lowercase;
    // two labels when the first is a usual package root (com.whatsapp) or the last is a usual
    // package suffix (kik.android).
    private val packageLike = Regex(
        """\b(?:[a-z][a-z0-9_]*(?:\.[a-zA-Z0-9_]+){2,}""" +
            """|(?:com|org|net|io|in|co|me|app|tv|de|uk|us|cn|jp|ru|br|fr|it)\.[a-z][a-z0-9_]*""" +
            """|[a-z][a-z0-9_]*\.(?:android|app|mobile)""" +
            """)\b"""
    )

    /** Masks third-party package names / host names. */
    fun redact(message: String): String =
        packageLike.replace(message) { match ->
            val value = match.value
            if (value == "com.grayzone.app" || readablePrefixes.any { value.startsWith(it) }) value
            else "<app>"
        }

    fun line(
        timeMs: Long,
        level: Char,
        process: String,
        thread: String,
        component: String,
        message: String,
        throwable: Throwable? = null,
    ): String {
        val base = "${timeFormat.get()!!.format(Date(timeMs))} $level/$process [$thread] [$component] ${redact(message)}"
        if (throwable == null) return base
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val safeTrace = trace.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, it -> redactTraceLine(it, isFirst = index == 0) }
            .joinToString("\n")
        return "$base\n$safeTrace"
    }

    /**
     * Frames ("\tat ...") are code locations and stay intact. Exception header lines (the first
     * line, "Caused by:", "Suppressed:") keep their class name, needed to diagnose library errors,
     * and only have the message after it masked, since messages can name apps ("Unable to start
     * ... com.whatsapp"). Any other line is a continuation of a multi-line message: fully masked.
     */
    private fun redactTraceLine(line: String, isFirst: Boolean): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("at ") || trimmed.startsWith("...")) return line
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val prefix = listOf("Caused by: ", "Suppressed: ").firstOrNull { trimmed.startsWith(it) }
        if (prefix == null && !isFirst) return indent + redact(trimmed)
        val body = trimmed.removePrefix(prefix ?: "")
        val colon = body.indexOf(": ")
        val head = if (colon < 0) body else body.substring(0, colon)
        if (head.any { it.isWhitespace() }) return indent + (prefix ?: "") + redact(body)
        val rebuilt = if (colon < 0) body else head + ": " + redact(body.substring(colon + 2))
        return indent + (prefix ?: "") + rebuilt
    }
}
