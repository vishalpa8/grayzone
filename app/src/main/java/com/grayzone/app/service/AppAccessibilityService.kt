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
        const val ACTION_SESSION_STARTED = "com.grayzone.app.ACTION_SESSION_STARTED"
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val PREFS_NAME = "GrayzonePrefs"
        private const val KEY_MONITORED = "monitored_apps"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "active_session_channel"
    }

    private var lastForegroundPackage: String? = null
    private var countdownJob: kotlinx.coroutines.Job? = null

    private val lockoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CHECK_LOCKOUT) {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
                if (pkg == lastForegroundPackage) {
                    // They are still in the app when session expired! Kick them out.
                    Log.d(TAG, "Session expired for $pkg while foregrounded, enforcing lock")
                    val broadcastIntent = Intent(ACTION_APP_OPENED).apply {
                        setPackage(applicationContext?.packageName)
                        putExtra(EXTRA_PACKAGE_NAME, pkg)
                        putExtra("overlay_mode", 2)
                        
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        putExtra("locked_until", prefs.getLong("locked_until_$pkg", 0L))
                    }
                    sendBroadcast(broadcastIntent)
                }
            } else if (intent?.action == ACTION_SESSION_STARTED) {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
                if (pkg == lastForegroundPackage) {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val activeUntil = prefs.getLong("active_until_$pkg", 0L)
                    if (activeUntil > System.currentTimeMillis()) {
                        startCountdownNotification(pkg, activeUntil)
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
        
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_CHECK_LOCKOUT)
            addAction(ACTION_SESSION_STARTED)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Active Session Timer",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the remaining time for your active session"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startCountdownNotification(packageName: String, activeUntil: Long) {
        countdownJob?.cancel()
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        countdownJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingSeconds = ((activeUntil - now) / 1000).coerceAtLeast(0).toInt()
                
                if (remainingSeconds <= 0) {
                    nm.cancel(NOTIFICATION_ID)
                    break
                }
                
                val mins = remainingSeconds / 60
                val secs = remainingSeconds % 60
                val timeStr = String.format("%02d:%02d", mins, secs)
                
                val notification = androidx.core.app.NotificationCompat.Builder(this@AppAccessibilityService, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("$appName Active")
                    .setContentText("Time remaining: $timeStr")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                    .build()
                
                nm.notify(NOTIFICATION_ID, notification)
                
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun cancelCountdownNotification() {
        countdownJob?.cancel()
        countdownJob = null
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(NOTIFICATION_ID)
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
                Log.d(TAG, "App $packageName is LOCKED OUT — triggering lockout overlay")
                val broadcastIntent = Intent(ACTION_APP_OPENED).apply {
                    setPackage(applicationContext.packageName)
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra("overlay_mode", 2) // Locked mode
                    putExtra("locked_until", lockedUntil)
                }
                sendBroadcast(broadcastIntent)
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
