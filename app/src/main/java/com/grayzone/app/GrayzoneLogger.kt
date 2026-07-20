package com.grayzone.app

import android.util.Log

/**
 * Centralized logging framework with component-based tagging and structured data.
 * 
 * Benefits:
 * - Easy to filter logs by component (VPN, DNS, SESSION, etc.)
 * - Structured data attached to log messages
 * - Production crash reporting integration point
 * - Consistent formatting across codebase
 */

enum class LogComponent {
    OVERLAY,
    VPN,
    DNS,
    DATABASE,
    SCHEDULE,
    SESSION,
    GRAYSCALE,
    BOOT,
    ACCESSIBILITY,
    CLEANUP
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

object GrayzoneLogger {
    private const val TAG = "Grayzone"

    fun shouldLog(level: LogLevel): Boolean = when (level) {
        LogLevel.DEBUG, LogLevel.INFO -> BuildConfig.DEBUG
        LogLevel.WARN, LogLevel.ERROR -> true
    }
    
    /**
     * Debug log with optional structured data.
     * Only logs in DEBUG builds to reduce production overhead.
     */
    fun d(component: LogComponent, message: String, data: Map<String, Any?> = emptyMap()) {
        if (!shouldLog(LogLevel.DEBUG)) return
        val dataStr = if (data.isNotEmpty()) " | $data" else ""
        Log.d(TAG, "[${component.name}] $message$dataStr")
    }
    
    /**
     * Info log for important state changes.
     * Kept lightweight in production to avoid log spam.
     */
    fun i(component: LogComponent, message: String) {
        if (!shouldLog(LogLevel.INFO)) return
        Log.i(TAG, "[${component.name}] $message")
    }
    
    /**
     * Warning log for recoverable issues.
     */
    fun w(component: LogComponent, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.WARN)) return
        Log.w(TAG, "[${component.name}] $message", throwable)
    }
    
    /**
     * Error log for failures and exceptions.
     * In production builds, this can be extended to send to crash reporting services.
     */
    fun e(component: LogComponent, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.ERROR)) return
        Log.e(TAG, "[${component.name}] $message", throwable)
        
        // Future: Production crash reporting integration
        // if (!BuildConfig.DEBUG && throwable != null) {
        //     FirebaseCrashlytics.getInstance().recordException(throwable)
        // }
    }
}
