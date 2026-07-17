package com.grayzone.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.grayzone.app.OverlayMode
import com.grayzone.app.PrefsKeys

class AppAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "GrayzoneService"
        const val ACTION_APP_OPENED   = "com.grayzone.app.ACTION_APP_OPENED"
        const val ACTION_CHECK_LOCKOUT = "com.grayzone.app.ACTION_CHECK_LOCKOUT"
        const val EXTRA_PACKAGE_NAME  = "package_name"
    }

    private var lastForegroundPackage: String? = null

    // BUG 1 FIX: Only enforce lock if user is still in the app when session expires.
    private val lockoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CHECK_LOCKOUT) return
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
            if (pkg != lastForegroundPackage) return // User already left the app

            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
            Log.d(TAG, "Session expired for $pkg while foregrounded, enforcing lock")

            val broadcastIntent = Intent(ACTION_APP_OPENED).apply {
                setPackage(applicationContext?.packageName)
                putExtra(EXTRA_PACKAGE_NAME, pkg)
                putExtra("overlay_mode", OverlayMode.LOCK)
                putExtra("locked_until", lockedUntil)
            }
            sendBroadcast(broadcastIntent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        Log.d(TAG, "Grayzone AccessibilityService connected")

        val filter = IntentFilter(ACTION_CHECK_LOCKOUT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(lockoutReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCountdownNotification()
        try { unregisterReceiver(lockoutReceiver) } catch (_: Exception) {}
    }

    private fun startCountdownNotification(packageName: String, activeUntil: Long) {
        startService(Intent(this, OverlayService::class.java).apply {
            action = "ACTION_START_COUNTDOWN"
            putExtra("package_name", packageName)
            putExtra("active_until", activeUntil)
        })
    }

    private fun cancelCountdownNotification() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = "ACTION_STOP_COUNTDOWN"
        })
    }

    // BUG 9 FIX: Removed unbounded packageLauncherCache — PackageManager handles its own caching.
    private fun isHomeLauncher(pkg: String): Boolean {
        val resolveInfo = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolveInfo?.activityInfo?.packageName == pkg
    }

    private fun hasLauncherIntent(pkg: String): Boolean =
        packageManager.getLaunchIntentForPackage(pkg) != null || isHomeLauncher(pkg)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app and apps without a launcher UI (keyboards, system popups)
        if (packageName == "com.grayzone.app" || !hasLauncherIntent(packageName)) return

        // Only react if it's a new foreground app (avoid repeated events)
        if (packageName == lastForegroundPackage) return

        val oldPackage = lastForegroundPackage
        lastForegroundPackage = packageName
        Log.d(TAG, "Foreground app changed to: $packageName")

        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // BUG 1 FIX: Clear session timers for the old app ONLY if:
        //   - They left while the session was still active (now < activeUntil)
        //   - They were NOT already in a lockout (now >= lockedUntil)
        // This prevents a bypass where a user switches away to reset an active lockout.
        if (oldPackage != null) {
            cancelCountdownNotification()
            val oldActiveUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + oldPackage, 0L)
            val oldLockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + oldPackage, 0L)
            if (now < oldActiveUntil && now >= oldLockedUntil) {
                prefs.edit()
                    .remove(PrefsKeys.ACTIVE_UNTIL + oldPackage)
                    .remove(PrefsKeys.LOCKED_UNTIL + oldPackage)
                    .apply()
                Log.d(TAG, "User left $oldPackage before session expired. Cleared timers.")
            }
        }

        // If Grayzone is globally disabled, remove tint and skip monitoring
        if (!prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, true)) {
            sendOverlayBroadcast { putExtra("overlay_mode", OverlayMode.REMOVE_TINT) }
            return
        }

        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()

        if (!monitoredApps.contains(packageName)) {
            sendOverlayBroadcast { putExtra("overlay_mode", OverlayMode.REMOVE_TINT) }
            return
        }

        // App is monitored — determine its state
        val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + packageName, 0L)
        val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + packageName, 0L)

        when {
            now < activeUntil -> {
                // Active session — apply tint, no friction
                Log.d(TAG, "App $packageName is in active session, allowing access")
                startCountdownNotification(packageName, activeUntil)
                sendOverlayBroadcast {
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra("overlay_mode", OverlayMode.TINT)
                }
            }
            now < lockedUntil -> {
                // Hard locked — show lock overlay
                Log.d(TAG, "App $packageName is LOCKED OUT - showing lock overlay")
                sendOverlayBroadcast {
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra("overlay_mode", OverlayMode.LOCK)
                    putExtra("locked_until", lockedUntil)
                }
            }
            else -> {
                // First open or session expired — show friction overlay
                Log.d(TAG, "App $packageName: triggering friction overlay")
                sendOverlayBroadcast {
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra("overlay_mode", OverlayMode.FRICTION)
                }
            }
        }
    }

    /** Helper to reduce broadcast boilerplate. */
    private inline fun sendOverlayBroadcast(block: Intent.() -> Unit) {
        val intent = Intent(ACTION_APP_OPENED).apply {
            setPackage(applicationContext.packageName)
            block()
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Grayzone AccessibilityService interrupted")
    }
}
