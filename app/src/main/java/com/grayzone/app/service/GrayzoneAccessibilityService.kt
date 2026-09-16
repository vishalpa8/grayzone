package com.grayzone.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GrayzoneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GZAccessibility"
        const val ACTION_APP_CHANGED = "com.grayzone.app.APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val ACTION_ACTIVE_WINDOWS_CHANGED = "com.grayzone.app.ACTIVE_WINDOWS_CHANGED"
        const val EXTRA_ACTIVE_PACKAGES = "active_packages"
        /** Avoid hammering startForegroundService after OEM kills / FGS denials (can trigger a11y disable). */
        private const val WATCHDOG_MIN_INTERVAL_MS = 5_000L
    }

    @Volatile
    private var lastWatchdogElapsedMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 100L
            }
            serviceInfo = info
            Log.d(TAG, "Accessibility service connected - monitoring foreground app changes")
            requestProtectionStatusRefresh()
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(com.grayzone.app.LogComponent.ACCESSIBILITY, "onServiceConnected failed", e)
        }
    }

    private fun requestProtectionStatusRefresh() {
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this, Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_REFRESH_PROTECTION_STATUS }
            )
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Failed to refresh protection status: ")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val packageName = event.packageName?.toString()

            // 1. Force Stop / Tamper Protection
            if (packageName == "com.android.settings") {
                val root = rootInActiveWindow
                if (root != null) {
                    val isAppInfo = root.findAccessibilityNodeInfosByText("Force stop").isNotEmpty() ||
                                    root.findAccessibilityNodeInfosByText("Uninstall").isNotEmpty()
                    val isGrayzone = root.findAccessibilityNodeInfosByText(getString(com.grayzone.app.R.string.app_name)).isNotEmpty()
                    
                    if (isAppInfo && isGrayzone) {
                        Log.w(TAG, "User attempting to force stop/uninstall Grayzone. Injecting HOME action.")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return
                    }
                }
            }

            // 2. Active Windows Tracking (Split Screen / Overlays)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val activePackages = ArrayList<String>()
                val activeWindows = windows
                for (window in activeWindows) {
                    val rootNode = window.root
                    if (rootNode != null) {
                        val pkg = rootNode.packageName?.toString()
                        if (pkg != null && !activePackages.contains(pkg)) {
                            activePackages.add(pkg)
                        }
                    }
                }
                
                if (activePackages.isNotEmpty()) {
                    sendBroadcast(Intent(ACTION_ACTIVE_WINDOWS_CHANGED).apply {
                        putStringArrayListExtra(EXTRA_ACTIVE_PACKAGES, activePackages)
                        setPackage(this@GrayzoneAccessibilityService.packageName)
                    })
                }
            }

            // 3. Legacy Foreground Transition Tracking
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            if (packageName.isNullOrEmpty()) return

            com.grayzone.app.GrayzoneLogger.d(com.grayzone.app.LogComponent.ACCESSIBILITY, "App changed", mapOf("package" to packageName))

            sendBroadcast(Intent(ACTION_APP_CHANGED).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                setPackage(this@GrayzoneAccessibilityService.packageName)
            })

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
                    com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Failed to watchdog OverlayService: ")
                }
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(com.grayzone.app.LogComponent.ACCESSIBILITY, "onAccessibilityEvent failed", e)
        }
    }

    override fun onInterrupt() { Log.d(TAG, "Accessibility service interrupted") }
    override fun onDestroy() { super.onDestroy(); Log.d(TAG, "Accessibility service destroyed") }
    override fun onUnbind(intent: Intent?): Boolean {
        com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Accessibility service unbound by the system (force-stop, Settings revoke, or crash)")
        return super.onUnbind(intent)
    }
}
