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
import com.grayzone.app.data.ScheduleManager
import com.grayzone.app.data.UsageDatabase
import com.grayzone.app.data.UsageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.grayzone.app.getAppName

class AppAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "GrayzoneService"
        const val ACTION_APP_OPENED   = "com.grayzone.app.ACTION_APP_OPENED"
        const val ACTION_CHECK_LOCKOUT = "com.grayzone.app.ACTION_CHECK_LOCKOUT"
        const val ACTION_SESSION_STARTED = "com.grayzone.app.ACTION_SESSION_STARTED"
        const val EXTRA_PACKAGE_NAME  = "package_name"
    }

    private var lastForegroundPackage: String? = null
    private var sessionStartTime: Long = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var scheduleManager: ScheduleManager
    private var cachedHomeLauncherPkg: String? = null

    private val lockoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CHECK_LOCKOUT -> handleLockoutCheck(intent)
                ACTION_SESSION_STARTED -> {
                    val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
                    if (pkg == lastForegroundPackage) {
                        sessionStartTime = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun handleLockoutCheck(intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
            if (pkg != lastForegroundPackage) return // User already left the app

            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
            val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
            val now = System.currentTimeMillis()

            if (now < activeUntil) {
                // Session was paused and resumed, so this check fired early. Reschedule.
                val delayMs = activeUntil - now
                startService(Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SCHEDULE_LOCKOUT_CHECK
                    putExtra("package_name", pkg)
                    putExtra("delay_ms", delayMs)
                })
                return
            }

            Log.d(TAG, "Session expired for $pkg while foregrounded, enforcing lock")

            val broadcastIntent = Intent(ACTION_APP_OPENED).apply {
                setPackage(applicationContext?.packageName)
                putExtra(EXTRA_PACKAGE_NAME, pkg)
                putExtra("overlay_mode", OverlayMode.LOCK)
                putExtra("locked_until", lockedUntil)
            }
            sendBroadcast(broadcastIntent)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduleManager = ScheduleManager(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        Log.d(TAG, "Grayzone AccessibilityService connected")

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

    private fun startCountdownNotification(packageName: String, activeUntil: Long) {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_COUNTDOWN
            putExtra("package_name", packageName)
            putExtra("active_until", activeUntil)
        })
    }

    private fun cancelCountdownNotification() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_COUNTDOWN
        })
    }

    private fun isHomeLauncher(pkg: String): Boolean {
        if (cachedHomeLauncherPkg == null) {
            cachedHomeLauncherPkg = packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }
        return cachedHomeLauncherPkg == pkg
    }

    private fun hasLauncherIntent(pkg: String): Boolean =
        packageManager.getLaunchIntentForPackage(pkg) != null || isHomeLauncher(pkg)

    private fun logUsageAndCheckBudget(pkg: String, startTime: Long) {
        val now = System.currentTimeMillis()
        val durationMs = now - startTime
        if (durationMs <= 0) return
        val currentFgPkg = lastForegroundPackage
        
        serviceScope.launch {
            try {
                // Log the event
                val dao = UsageDatabase.getInstance(this@AppAccessibilityService).usageDao()
                val appName = getAppName(pkg)
                
                val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                dao.insertEvent(
                    UsageEvent(
                        packageName = pkg,
                        appName = appName,
                        startTime = startTime,
                        endTime = now,
                        durationMillis = durationMs,
                        wasBlocked = false,
                        dateKey = dateKey
                    )
                )
                
                // Update budget
                val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
                if (budgetMins > 0) {
                    val lastReset = prefs.getString(PrefsKeys.DAILY_RESET_DATE + pkg, "")
                    val usedMs = if (lastReset == dateKey) prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L) else 0L
                    
                    val newUsedMs = usedMs + durationMs
                    prefs.edit()
                        .putLong(PrefsKeys.DAILY_USED_MILLIS + pkg, newUsedMs)
                        .putString(PrefsKeys.DAILY_RESET_DATE + pkg, dateKey)
                        .apply()
                        
                    // If budget exceeded, and we are currently in this app, force a budget lock
                    if (newUsedMs >= budgetMins * 60 * 1000L && currentFgPkg == pkg) {
                        sendOverlayBroadcast {
                            putExtra(EXTRA_PACKAGE_NAME, pkg)
                            putExtra("overlay_mode", OverlayMode.BUDGET_LOCK)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log usage: ${e.message}")
            }
        }
    }

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

        // 1. Handle backgrounding the old app (PAUSE)
        if (oldPackage != null) {
            cancelCountdownNotification()
            // Log usage
            if (sessionStartTime > 0) {
                logUsageAndCheckBudget(oldPackage, sessionStartTime)
                sessionStartTime = 0L
            }
            
            val oldActiveUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + oldPackage, 0L)
            if (now < oldActiveUntil) {
                val remaining = oldActiveUntil - now
                if (remaining > 0) {
                    prefs.edit()
                        .putLong(PrefsKeys.REMAINING_MILLIS + oldPackage, remaining)
                        .remove(PrefsKeys.ACTIVE_UNTIL + oldPackage)
                        .remove(PrefsKeys.LOCKED_UNTIL + oldPackage)
                        .apply()
                    Log.d(TAG, "User left $oldPackage before session expired. Paused with ${remaining}ms left.")
                }
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

        // Check if schedule/focus mode is active globally
        if (scheduleManager.isCurrentlyScheduled()) {
            sendOverlayBroadcast {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra("overlay_mode", OverlayMode.SCHEDULE_LOCK)
            }
            return
        }

        // Check Daily Budget
        val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + packageName, 0)
        if (budgetMins > 0) {
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val lastReset = prefs.getString(PrefsKeys.DAILY_RESET_DATE + packageName, "")
            val usedMs = if (lastReset == dateKey) prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + packageName, 0L) else 0L
            if (usedMs >= budgetMins * 60 * 1000L) {
                sendOverlayBroadcast {
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra("overlay_mode", OverlayMode.BUDGET_LOCK)
                }
                return
            }
        }

        // App is monitored — determine its state
        var activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + packageName, 0L)
        var lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + packageName, 0L)
        val remainingPaused = prefs.getLong(PrefsKeys.REMAINING_MILLIS + packageName, 0L)

        // 2. Handle foregrounding the new app (RESUME)
        if (remainingPaused > 0) {
            val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
            val lockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 60)
                              else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
            
            activeUntil = now + remainingPaused
            lockedUntil = activeUntil + (lockoutMins * 60 * 1000L)
            
            prefs.edit()
                .putLong(PrefsKeys.ACTIVE_UNTIL + packageName, activeUntil)
                .putLong(PrefsKeys.LOCKED_UNTIL + packageName, lockedUntil)
                .remove(PrefsKeys.REMAINING_MILLIS + packageName)
                .apply()
            
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SCHEDULE_LOCKOUT_CHECK
                putExtra("package_name", packageName)
                putExtra("delay_ms", remainingPaused)
            })
            Log.d(TAG, "Resumed session for $packageName with ${remainingPaused}ms left.")
        }

        when {
            now < activeUntil -> {
                // Active session — apply tint, no friction
                Log.d(TAG, "App $packageName is in active session, allowing access")
                sessionStartTime = now // Start tracking time
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
