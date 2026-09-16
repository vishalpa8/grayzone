package com.grayzone.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        /**
         * Asks the accessibility service to send the user home. Background `startActivity` is
         * unreliable on API 29+ and on OEM skins, so eviction of a locked app goes through the
         * global HOME action instead, which always works while the service is connected.
         */
        const val ACTION_PERFORM_HOME = "com.grayzone.app.PERFORM_HOME"
        /** Asks for an immediate, freshly read window list (see [broadcastWindowSnapshot]). */
        const val ACTION_REQUEST_WINDOW_SNAPSHOT = "com.grayzone.app.REQUEST_WINDOW_SNAPSHOT"
        /** Avoid hammering startForegroundService after OEM kills / FGS denials (can trigger a11y disable). */
        private const val WATCHDOG_MIN_INTERVAL_MS = 5_000L
    }

    @Volatile
    private var lastWatchdogElapsedMs: Long = 0L

    private var homeActionReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerHomeActionReceiver()
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                // Limits event processing rate. Note: a 100ms throttle may drop intermediate sub-100ms
                // rapid app switches (e.g. A->B->A in 50ms) to save battery, which is an acceptable tradeoff
                // since human users cannot meaningfully consume content in sub-100ms windows.
                notificationTimeout = 100L
            }
            serviceInfo = info
            Log.d(TAG, "Accessibility service connected - monitoring foreground app changes")
            requestProtectionStatusRefresh()
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(com.grayzone.app.LogComponent.ACCESSIBILITY, "onServiceConnected failed", e)
        }
    }

    private fun registerHomeActionReceiver() {
        if (homeActionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PERFORM_HOME -> try {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    } catch (e: Exception) {
                        com.grayzone.app.GrayzoneLogger.w(
                            com.grayzone.app.LogComponent.ACCESSIBILITY,
                            "GLOBAL_ACTION_HOME failed: ${e.message}"
                        )
                    }
                    ACTION_REQUEST_WINDOW_SNAPSHOT -> broadcastWindowSnapshot()
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_PERFORM_HOME)
                addAction(ACTION_REQUEST_WINDOW_SNAPSHOT)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            homeActionReceiver = receiver
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.ACCESSIBILITY,
                "Failed to register accessibility command receiver",
                e
            )
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

            // 1. Force Stop / Tamper Protection (Only check on major window state changes to save CPU)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val root = rootInActiveWindow
                if (root != null) {
                    val className = event.className?.toString() ?: ""
                    val isAppDetailsActivity = className.contains("AppDetails", ignoreCase = true) || 
                                             className.contains("ApplicationDetails", ignoreCase = true)
                    
                    var hasTamperControls = isAppDetailsActivity
                    if (!hasTamperControls) {
                        hasTamperControls = hasTamperControlsNode(root)
                    }

                    val isGrayzone = root.findAccessibilityNodeInfosByText(getString(com.grayzone.app.R.string.app_name)).isNotEmpty()
                    
                    if (hasTamperControls && isGrayzone) {
                        Log.w(TAG, "User attempting to force stop/uninstall Grayzone. Injecting HOME action.")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return
                    }
                }
            }

            // 2. Active Windows Tracking (Split Screen / Overlays)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val activePackages = onScreenApplicationPackages()
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
                
                // Piggyback real-time active window state so OverlayService isn't vulnerable to 
                // broadcast race conditions when making backgrounding decisions.
                putStringArrayListExtra(EXTRA_ACTIVE_PACKAGES, onScreenApplicationPackages())

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

    override fun onDestroy() {
        homeActionReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
        homeActionReceiver = null
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Accessibility service unbound by the system (force-stop, Settings revoke, or crash)")
        return super.onUnbind(intent)
    }

    /**
     * Packages owning an application window that is on screen right now.
     *
     * Restricted to [AccessibilityWindowInfo.TYPE_APPLICATION] so system UI, the IME and overlay
     * chrome never register as "the user is in this app" — split-screen panes still both count,
     * which is the case this tracking exists for.
     */
    private fun onScreenApplicationPackages(): ArrayList<String> {
        val packages = ArrayList<String>()
        try {
            for (window in windows) {
                if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = window.root?.packageName?.toString() ?: continue
                if (!packages.contains(pkg)) packages.add(pkg)
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.ACCESSIBILITY,
                "Failed to read window list: ${e.message}"
            )
        }
        return packages
    }

    /**
     * Answers OverlayService's session heartbeat with a freshly read window list. The cached list
     * from the last transition can be stale (an outgoing app's window lingers through the
     * animation), and a session must only keep draining while the app is provably on screen.
     */
    private fun broadcastWindowSnapshot() {
        sendBroadcast(Intent(ACTION_ACTIVE_WINDOWS_CHANGED).apply {
            putStringArrayListExtra(EXTRA_ACTIVE_PACKAGES, onScreenApplicationPackages())
            setPackage(this@GrayzoneAccessibilityService.packageName)
        })
    }

    private fun hasTamperControlsNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val id = node.viewIdResourceName
        if (id != null && (id.contains("force_stop", ignoreCase = true) || id.contains("uninstall", ignoreCase = true))) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (hasTamperControlsNode(node.getChild(i))) return true
        }
        return false
    }
}
