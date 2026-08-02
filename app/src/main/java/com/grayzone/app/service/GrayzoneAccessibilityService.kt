package com.grayzone.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that monitors foreground window changes ONLY.
 * 
 * Minimal, transparent, and trustworthy by design:
 * - Only listens to TYPE_WINDOW_STATE_CHANGED (app switches)
 * - Does NOT retrieve window content (canRetrieveWindowContent=false)
 * - Does NOT access notifications, text, or UI content
 * - Does NOT collect, store, or transmit data
 * - Does NOT interact with other apps' UIs
 * - Broadcasts only the package name to internal OverlayService
 * 
 * Purpose: Replaces battery-intensive UsageStatsManager polling with event-driven 
 * app detection for instant, zero-overhead monitoring.
 * 
 * Battery benefit: ~95% reduction vs polling (event-driven vs 1-second intervals)
 * 
 * Security model: Explicit package-targeting on broadcasts prevents other apps from 
 * intercepting app-change events.
 */
class GrayzoneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GZAccessibility"
        const val ACTION_APP_CHANGED = "com.grayzone.app.APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "package_name"
        /** Avoid hammering startForegroundService after OEM kills / FGS denials (can trigger a11y disable). */
        private const val WATCHDOG_MIN_INTERVAL_MS = 5_000L
    }

    @Volatile
    private var lastWatchdogElapsedMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Guard every step: an uncaught exception here can crash the service, which is one of the
        // triggers that causes the OS to disable (revoke) an accessibility service.
        try {
            val info = AccessibilityServiceInfo().apply {
                // Only listen for window state changes (foreground app switches)
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

                // Generic feedback type (no audio/haptic feedback needed)
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

                // 100ms notification timeout (balance between responsiveness and overhead).
                // Note: we intentionally do NOT request interactive-window retrieval — only the
                // event's package name is used — keeping the service lightweight.
                notificationTimeout = 100L
            }

            serviceInfo = info
            Log.d(TAG, "Accessibility service connected - monitoring foreground app changes")
            // Cross-process: health + notification live in the default process (F-080).
            requestProtectionStatusRefresh()
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.ACCESSIBILITY,
                "onServiceConnected failed",
                e
            )
        }
    }

    private fun requestProtectionStatusRefresh() {
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_REFRESH_PROTECTION_STATUS
                }
            )
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.ACCESSIBILITY,
                "Failed to refresh protection status: ${e.message}"
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Guard the whole handler so a malformed event can never crash (and thereby revoke) the
        // service.
        try {
            // Only process window state changes (app switches)
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

            val packageName = event.packageName?.toString() ?: return
            if (packageName.isEmpty()) return

            // Audit log: what event we're responding to (transparent for user verification)
            // This shows we only detect app changes, not content or UI interactions
            com.grayzone.app.GrayzoneLogger.d(
                com.grayzone.app.LogComponent.ACCESSIBILITY,
                "App changed",
                mapOf("package" to packageName)
            )

            // Send broadcast to OverlayService
            // Using explicit package targeting for security (prevents other apps from intercepting)
            sendBroadcast(Intent(ACTION_APP_CHANGED).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                setPackage(this@GrayzoneAccessibilityService.packageName)
            })

            // Watchdog: Ensure OverlayService is running (restarts it if swipe-away/crash killed it).
            // Rate-limit: after force-stop / FGS denial, unbounded retries can crash-loop the default
            // process and push the OS to disable this accessibility service.
            val nowElapsed = SystemClock.elapsedRealtime()
            if (nowElapsed - lastWatchdogElapsedMs >= WATCHDOG_MIN_INTERVAL_MS) {
                lastWatchdogElapsedMs = nowElapsed
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@GrayzoneAccessibilityService,
                        Intent(this@GrayzoneAccessibilityService, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_APP_CHANGED_WATCHDOG
                            putExtra(EXTRA_PACKAGE_NAME, packageName)
                        }
                    )
                } catch (e: Exception) {
                    com.grayzone.app.GrayzoneLogger.w(
                        com.grayzone.app.LogComponent.ACCESSIBILITY,
                        "Failed to watchdog OverlayService: ${e.message}"
                    )
                }
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.ACCESSIBILITY,
                "onAccessibilityEvent failed",
                e
            )
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        com.grayzone.app.GrayzoneLogger.w(
            com.grayzone.app.LogComponent.ACCESSIBILITY,
            "Accessibility service unbound by the system (force-stop, Settings revoke, or crash)"
        )
        // Do NOT startForegroundService here. Force-stop / unbind races with process death; starting
        // an FGS from a dying :a11y process commonly throws and can worsen OS accessibility disable.
        // Main process refreshes health on next user-visible launch (Application / MainScreen).
        return super.onUnbind(intent)
    }
}
