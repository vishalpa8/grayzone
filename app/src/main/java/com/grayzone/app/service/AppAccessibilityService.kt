package com.grayzone.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "GrayzoneService"
        const val ACTION_APP_OPENED = "com.grayzone.app.ACTION_APP_OPENED"
        const val ACTION_CHECK_LOCKOUT = "com.grayzone.app.ACTION_CHECK_LOCKOUT"
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val PREFS_NAME = "GrayzonePrefs"
        private const val KEY_MONITORED = "monitored_apps"
    }

    private var lastForegroundPackage: String? = null

    private val lockoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CHECK_LOCKOUT) {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
                if (pkg == lastForegroundPackage) {
                    // They are still in the app when session expired! Kick them out.
                    Log.d(TAG, "Session expired for $pkg while foregrounded, enforcing lock")
                    
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    
                    val pm = packageManager
                    val appName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        pkg
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(this@AppAccessibilityService, "$appName session expired!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "Grayzone AccessibilityService connected")

        val filter = IntentFilter().apply {
            addAction(ACTION_CHECK_LOCKOUT)
        }
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
        val intent = Intent(this, OverlayService::class.java).apply {
            action = "ACTION_START_COUNTDOWN"
            putExtra("package_name", packageName)
            putExtra("active_until", activeUntil)
        }
        startService(intent)
    }

    private fun cancelCountdownNotification() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = "ACTION_STOP_COUNTDOWN"
        }
        startService(intent)
    }

    private val packageLauncherCache = mutableMapOf<String, Boolean>()

    private fun isHomeLauncher(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == pkg
    }

    private fun hasLauncherIntent(pkg: String): Boolean {
        return packageLauncherCache.getOrPut(pkg) {
            packageManager.getLaunchIntentForPackage(pkg) != null || isHomeLauncher(pkg)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app (overlay) and apps without a UI (keyboards, system popups)
        if (packageName == "com.grayzone.app" || !hasLauncherIntent(packageName)) return

        val oldPackage = lastForegroundPackage

        // Only react if it's a new foreground app (avoid repeated events)
        if (packageName == lastForegroundPackage) return
        lastForegroundPackage = packageName

        Log.d(TAG, "Foreground app changed to: $packageName")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Clear active session for old app if they left before it expired
        if (oldPackage != null && oldPackage != packageName) {
            cancelCountdownNotification()
            val oldActiveUntil = prefs.getLong("active_until_$oldPackage", 0L)
            val now = System.currentTimeMillis()
            if (now > 0 && now < oldActiveUntil) {
                // They left before the session expired! Clear the timers so it restarts next time
                prefs.edit()
                    .remove("active_until_$oldPackage")
                    .remove("locked_until_$oldPackage")
                    .apply()
                Log.d(TAG, "User left $oldPackage before session expired. Cleared timers.")
            }
        }
        val monitoredApps = prefs.getStringSet(KEY_MONITORED, emptySet()) ?: emptySet()
        val isMonitored = monitoredApps.contains(packageName)

        // Toggle grayscale based on monitored state
        toggleGrayscale(isMonitored)

        if (isMonitored) {
            val now = System.currentTimeMillis()
            val activeUntil = prefs.getLong("active_until_$packageName", 0L)
            val lockedUntil = prefs.getLong("locked_until_$packageName", 0L)
            
            if (now < activeUntil) {
                // App is in an ACTIVE session, just apply grayscale, no overlay
                Log.d(TAG, "App $packageName is in active session, allowing access")
                startCountdownNotification(packageName, activeUntil)
            } else if (now < lockedUntil) {
                // App is HARD LOCKED
                Log.d(TAG, "App $packageName is LOCKED OUT — kicking to home")
                performGlobalAction(GLOBAL_ACTION_HOME)
                
                val pm = packageManager
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (e: Exception) {
                    packageName
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this@AppAccessibilityService, "$appName is currently locked", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                // First time or session expired
                Log.d(TAG, "App $packageName detected: session expired, triggering friction overlay")
                val broadcastIntent = Intent(ACTION_APP_OPENED).apply {
                    setPackage(applicationContext.packageName)
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra("overlay_mode", 1) // Friction mode
                }
                sendBroadcast(broadcastIntent)
            }
        }
    }

    private fun toggleGrayscale(enable: Boolean) {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cr = contentResolver
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", if (enable) 1 else 0)
                if (enable) {
                    Settings.Secure.putInt(cr, "accessibility_display_daltonizer", 0) // 0 = Grayscale
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle grayscale: \${e.message}")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Grayzone AccessibilityService interrupted")
    }
}
