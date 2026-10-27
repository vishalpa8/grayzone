package com.grayzone.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GrayzoneAccessibilityService : AccessibilityService() {

    companion object {
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
        /**
         * Sent by OverlayService with [EXTRA_MONITORING_ENABLED]. This process must not read prefs,
         * so it learns the Monitoring switch this way and stops restarting the service while off.
         */
        const val ACTION_MONITORING_STATE = "com.grayzone.app.MONITORING_STATE"
        const val EXTRA_MONITORING_ENABLED = "monitoring_enabled"
        /** Asks for the current foreground app to be sent again (e.g. after the screen unlocks). */
        const val ACTION_RESEND_FOREGROUND = "com.grayzone.app.RESEND_FOREGROUND"
        /** Asks this process to write queued log lines to disk (before a log export). */
        const val ACTION_FLUSH_LOGS = "com.grayzone.app.FLUSH_LOGS"
        private const val LAUNCHABLE_CACHE_TTL_MS = 10 * 60_000L
        /** Avoid hammering startForegroundService after OEM kills / FGS denials (can trigger a11y disable). */
        private const val WATCHDOG_MIN_INTERVAL_MS = 5_000L
    }

    @Volatile
    private var lastWatchdogElapsedMs: Long = 0L

    /** False only after OverlayService reported Monitoring off; a fresh process starts permissive. */
    @Volatile
    private var monitoringEnabled = true

    /** Last foreground app sent to OverlayService; kept as the foreground while focus is elsewhere. */
    private var lastReportedForeground: String? = null

    private data class WindowSnapshot(val packages: ArrayList<String>, val foreground: String?)

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
            com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.ACCESSIBILITY, "Accessibility service connected")
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
                            "GLOBAL_ACTION_HOME failed", e
                        )
                    }
                    ACTION_REQUEST_WINDOW_SNAPSHOT -> broadcastWindowSnapshot()
                    ACTION_RESEND_FOREGROUND -> {
                        val snapshot = readApplicationWindows()
                        val fg = snapshot.foreground
                        // Nothing to report yet (keyguard still animating away): forget the last
                        // report so the next window event sends the app even if it is unchanged.
                        if (fg == null) lastReportedForeground = null else sendForeground(fg, snapshot.packages)
                    }
                    ACTION_FLUSH_LOGS -> {
                        // goAsync keeps this process alive while the writer drains, off the main thread.
                        val pending = goAsync()
                        Thread {
                            try {
                                com.grayzone.app.GrayzoneLogger.flush(200L)
                            } finally {
                                pending.finish()
                            }
                        }.start()
                    }
                    ACTION_MONITORING_STATE ->
                        monitoringEnabled = intent.getBooleanExtra(EXTRA_MONITORING_ENABLED, true)
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_PERFORM_HOME)
                addAction(ACTION_REQUEST_WINDOW_SNAPSHOT)
                addAction(ACTION_MONITORING_STATE)
                addAction(ACTION_RESEND_FOREGROUND)
                addAction(ACTION_FLUSH_LOGS)
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
            com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Failed to ask the monitoring service to refresh protection status", e)
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
                    
                    // Name check first: the recursive walk costs one IPC per node, and would
                    // otherwise run over every screen of every app the user opens.
                    val isGrayzone = root.findAccessibilityNodeInfosByText(getString(com.grayzone.app.R.string.app_name)).isNotEmpty()
                    val hasTamperControls = isGrayzone &&
                        (isAppDetailsActivity || hasTamperControlsNode(root))
                    
                    if (hasTamperControls) {
                        com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.ACCESSIBILITY, "Force-stop/uninstall screen for Grayzone opened; sending HOME")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return
                    }
                }
            }

            if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) return

            // 2. Active Windows Tracking (Split Screen / Overlays)
            val snapshot = readApplicationWindows()
            val activePackages = snapshot.packages

            // 3. Foreground app = the application window the user is in. System UI, the keyboard,
            // toasts, the shade and Grayzone's own overlays are not application windows, so they
            // never count as "the user left the app" (they used to end sessions and stop the
            // friction screen from granting one). Fall back to the event's package only when the
            // window list is unavailable and the event itself came from an application window.
            val foreground = snapshot.foreground
                ?: packageName?.takeIf {
                    activePackages.isEmpty() &&
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        it != this.packageName && isUserApp(it)
                }

            // Sent on every change, and re-sent on each window-state event so OverlayService can
            // resync after it paused an app or restarted (it ignores repeats itself). The window
            // list rides along; it is broadcast alone only when there is no foreground to send.
            if (foreground != null && (foreground != lastReportedForeground ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            ) {
                sendForeground(foreground, activePackages)
            } else if (activePackages.isNotEmpty()) {
                sendBroadcast(Intent(ACTION_ACTIVE_WINDOWS_CHANGED).apply {
                    putStringArrayListExtra(EXTRA_ACTIVE_PACKAGES, activePackages)
                    setPackage(this@GrayzoneAccessibilityService.packageName)
                })
            }
            if (foreground == null) return

            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val nowElapsed = SystemClock.elapsedRealtime()
            if (monitoringEnabled && nowElapsed - lastWatchdogElapsedMs >= WATCHDOG_MIN_INTERVAL_MS) {
                lastWatchdogElapsedMs = nowElapsed
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@GrayzoneAccessibilityService,
                        Intent(this@GrayzoneAccessibilityService, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_APP_CHANGED_WATCHDOG
                            putExtra(EXTRA_PACKAGE_NAME, foreground)
                        }
                    )
                } catch (e: Exception) {
                    com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Watchdog could not (re)start the monitoring service", e)
                }
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(com.grayzone.app.LogComponent.ACCESSIBILITY, "onAccessibilityEvent failed", e)
        }
    }

    override fun onInterrupt() { com.grayzone.app.GrayzoneLogger.d(com.grayzone.app.LogComponent.ACCESSIBILITY, "Accessibility service interrupted") }

    override fun onDestroy() {
        homeActionReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Failed to unregister command receiver", e)
            }
        }
        homeActionReceiver = null
        super.onDestroy()
        com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.ACCESSIBILITY, "Accessibility service destroyed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Accessibility service unbound by the system (force-stop, Settings revoke, or crash)")
        return super.onUnbind(intent)
    }

    /**
     * Grayzone's own pause / lock screens. Android reports these overlays as full-screen
     * application windows, so without this the pause screen itself looked like "the user opened
     * Grayzone": the monitored app was treated as left and the wait could never grant a session.
     */
    private fun isGrayzoneOverlay(
        window: android.view.accessibility.AccessibilityWindowInfo,
        root: AccessibilityNodeInfo
    ): Boolean {
        if (root.packageName?.toString() != packageName) return false
        if (window.title?.toString() == OverlayService.OVERLAY_WINDOW_TITLE) return true
        return root.paneTitle?.toString() == OverlayService.OVERLAY_PANE_TITLE
    }

    private val launchableCache = HashMap<String, Boolean>()
    private var launchableCacheResetElapsed = 0L
    private var homePackage: String? = null
    private var homeResolvedAtElapsed = 0L

    /**
     * True for apps a user opens (launcher icon) and for the home screen. System panels that OEMs
     * draw as application windows (control centre, fingerprint UI, upslide gestures, system UI)
     * have no launcher entry and must not count as switching away from the current app.
     */
    private fun isUserApp(pkg: String): Boolean {
        if (pkg == packageName) return true
        val now = SystemClock.elapsedRealtime()
        if (homePackage == null || now - homeResolvedAtElapsed > 60_000L) {
            homePackage = try {
                packageManager.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo?.packageName
            } catch (_: Exception) { null }
            homeResolvedAtElapsed = now
        }
        if (pkg == homePackage) return true
        // Entries expire so apps that toggle their launcher alias at runtime are re-checked.
        if (now - launchableCacheResetElapsed > LAUNCHABLE_CACHE_TTL_MS) {
            launchableCache.clear()
            launchableCacheResetElapsed = now
        }
        launchableCache[pkg]?.let { return it }
        return try {
            (packageManager.getLaunchIntentForPackage(pkg) != null).also { launchableCache[pkg] = it }
        } catch (e: Exception) {
            // Unknown: treat as a user app this time, but don't remember the guess.
            true
        }
    }

    /**
     * Application windows on screen plus the one the user is in; the decision itself is
     * [ForegroundResolver] (overlays, floaters, PiP, calls and kept focus are covered there).
     */
    private fun readApplicationWindows(): WindowSnapshot {
        val collected = ArrayList<ForegroundResolver.AppWindow>()
        val metrics = resources.displayMetrics
        val minArea = (metrics.widthPixels.toLong() * metrics.heightPixels) / 5
        try {
            val bounds = android.graphics.Rect()
            for (window in windows) {
                if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                window.getBoundsInScreen(bounds)
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                collected += ForegroundResolver.AppWindow(
                    pkg = pkg,
                    area = bounds.width().toLong() * bounds.height(),
                    isActive = window.isActive || window.isFocused,
                    isPip = window.isInPictureInPictureMode,
                    isUserApp = isUserApp(pkg),
                    isOwnOverlay = isGrayzoneOverlay(window, root),
                )
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.ACCESSIBILITY, "Failed to read window list", e)
        }
        val result = ForegroundResolver.resolve(collected, minArea, lastReportedForeground)
        return WindowSnapshot(ArrayList(result.packages), result.foreground)
    }

    private fun sendForeground(foreground: String, activePackages: ArrayList<String>) {
        if (foreground != lastReportedForeground) {
            com.grayzone.app.GrayzoneLogger.d(com.grayzone.app.LogComponent.ACCESSIBILITY, "App changed", mapOf("package" to foreground))
        }
        lastReportedForeground = foreground
        sendBroadcast(Intent(ACTION_APP_CHANGED).apply {
            putExtra(EXTRA_PACKAGE_NAME, foreground)
            // Piggyback the window list read for this same event, so OverlayService's
            // backgrounding decision never races a separate broadcast.
            putStringArrayListExtra(EXTRA_ACTIVE_PACKAGES, activePackages)
            setPackage(this@GrayzoneAccessibilityService.packageName)
        })
    }
    /**
     * Answers OverlayService's session heartbeat with a freshly read window list. The cached list
     * from the last transition can be stale (an outgoing app's window lingers through the
     * animation), and a session must only keep draining while the app is provably on screen.
     */
    private fun broadcastWindowSnapshot() {
        sendBroadcast(Intent(ACTION_ACTIVE_WINDOWS_CHANGED).apply {
            putStringArrayListExtra(EXTRA_ACTIVE_PACKAGES, readApplicationWindows().packages)
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
