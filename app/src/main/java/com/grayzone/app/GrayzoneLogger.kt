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

object GrayzoneLogger {
    private const val TAG = "Grayzone"
    
    /**
     * Debug log with optional structured data.
     * Only logs in DEBUG builds to reduce production overhead.
     */
    fun d(component: LogComponent, message: String, data: Map<String, Any?> = emptyMap()) {
        if (BuildConfig.DEBUG) {
            val dataStr = if (data.isNotEmpty()) " | $data" else ""
            Log.d(TAG, "[${component.name}] $message$dataStr")
        }
    }
    
    /**
     * Info log for important state changes.
     */
    fun i(component: LogComponent, message: String) {
        Log.i(TAG, "[${component.name}] $message")
    }
    
    /**
     * Warning log for recoverable issues.
     */
    fun w(component: LogComponent, message: String, throwable: Throwable? = null) {
        Log.w(TAG, "[${component.name}] $message", throwable)
    }
    
    /**
     * Error log for failures and exceptions.
     * In production builds, this can be extended to send to crash reporting services.
     */
    fun e(component: LogComponent, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[${component.name}] $message", throwable)
        
        // Future: Production crash reporting integration
        // if (!BuildConfig.DEBUG && throwable != null) {
        //     FirebaseCrashlytics.getInstance().recordException(throwable)
        // }
    }
}
