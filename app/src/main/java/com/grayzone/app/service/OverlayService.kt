package com.grayzone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import com.grayzone.app.OverlayMode
import com.grayzone.app.PrefsKeys
import com.grayzone.app.GrayscaleManager
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.R
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.grayzone.app.MainActivity
import com.grayzone.app.Prompts
import com.grayzone.app.formatDuration
import com.grayzone.app.getAppName
import com.grayzone.app.getNormalizedRemainingMillis
import com.grayzone.app.data.StreakManager
import com.grayzone.app.data.UsageDatabase
import com.grayzone.app.data.UsageEvent
import com.grayzone.app.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.pm.PackageManager
import com.grayzone.app.data.ScheduleManager

class OverlayService : Service() {

    companion object {
        const val TAG = "GrayzoneOverlay"
        const val CHANNEL_ID = "grayzone_overlay"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.grayzone.app.ACTION_STOP_OVERLAY"
        const val ACTION_START_COUNTDOWN = "com.grayzone.app.ACTION_START_COUNTDOWN"
        const val ACTION_STOP_COUNTDOWN = "com.grayzone.app.ACTION_STOP_COUNTDOWN"
        private const val DEFAULT_WAIT = 5
        private const val DEFAULT_LOCKOUT = 60

        // Hex color int helpers
        private const val BG          = 0xFF07070F.toInt() // 100% opaque
        private const val SURFACE     = 0xFF0F0F1C.toInt()
        private const val PURPLE      = 0xFF7C4DFF.toInt()
        private const val PURPLE_DIM  = 0xFF1E1040.toInt()
        private const val WHITE       = 0xFFFFFFFF.toInt()
        private const val GREY        = 0xFF7878A0.toInt()
        private const val GREY_DIM    = 0xFF3D3D5C.toInt()
        private const val RED         = 0xFFFF5252.toInt()
        private const val BORDER      = 0xFF26263D.toInt()
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tintView: View? = null
    private var countdownJobUI: Job? = null
    private var secondsRemaining = DEFAULT_WAIT
    
    // Exception handler for coroutines to prevent silent failures
    private val coroutineExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        com.grayzone.app.GrayzoneLogger.e(
            com.grayzone.app.LogComponent.OVERLAY,
            "Uncaught exception in OverlayService coroutine",
            throwable
        )
    }
    
    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler
    )
    // Written from both the transition coroutine (IO) and the main thread.
    private val lockoutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    
    // Usage stats state — read/written across the transition coroutine (IO),
    // the main-thread countdown job, and lockout jobs, hence @Volatile.
    @Volatile private var lastForegroundPackage: String? = null
    @Volatile private var sessionStartTime: Long = 0L
    private var cachedHomeLauncherPkg: String? = null
    private var homeLauncherCacheTime: Long = 0L
    private lateinit var scheduleManager: ScheduleManager
    
    private val transitionLock = Any()
    private var queuedForegroundPackage: String? = null
    private var transitionJob: Job? = null
    
    // Broadcast receiver for AccessibilityService events (replaces polling)
    private var appChangeReceiver: BroadcastReceiver? = null
    
    private val policyEngine = com.grayzone.app.policy.SessionPolicyEngine()

    override fun onCreate() {
        super.onCreate()
        com.grayzone.app.data.ProtectionHealthRepository.updateOverlayStatus(true)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scheduleManager = ScheduleManager(this)

        // Sev1 fix: if the process was killed while Daltonizer was on, it stays on
        // device-wide with no obvious recovery. Reconcile the system setting to reality
        // before any session logic runs.
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val hasActiveSession = monitoredApps.any { pkg ->
            now < prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
        }
        GrayscaleManager.reconcileOnStart(this, hasActiveSession)

        // Register broadcast receiver for AccessibilityService events
        // This replaces battery-intensive UsageStatsManager polling with event-driven detection
        appChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.getStringExtra(
                    GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME
                ) ?: return
                handleForegroundAppChanged(packageName)
            }
        }
        
        val filter = IntentFilter(GrayzoneAccessibilityService.ACTION_APP_CHANGED)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            appChangeReceiver, 
            filter, 
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        com.grayzone.app.GrayzoneLogger.i(
            com.grayzone.app.LogComponent.OVERLAY, 
            "OverlayService started - listening for AccessibilityService events"
        )

        scheduleMidnightReset()
    }

    /**
     * At midnight everything resets for the new day: active sessions, lockouts
     * and paused remainders are cleared (budgets reset via their own date keys).
     * Runs in a loop so the service handles any number of day rollovers.
     */
    private fun scheduleMidnightReset() {
        serviceScope.launch {
            while (true) {
                // Small buffer past midnight to avoid firing on the old day.
                delay(com.grayzone.app.DateUtils.millisUntilMidnight() + 1_000L)
                performMidnightReset()
            }
        }
    }

    private suspend fun performMidnightReset() {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!com.grayzone.app.data.RuntimeSessionReset.resetIfNeeded(prefs)) return

        GrayzoneLogger.i(LogComponent.OVERLAY, "Midnight reset: session locks cleared for the new day")

        lockoutJobs.values.forEach { it.cancel() }
        lockoutJobs.clear()

        val currentPkg = lastForegroundPackage
        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()

        withContext(Dispatchers.Main) {
            // Any session/lock UI from yesterday is now stale.
            cancelCountdownNotification()
            dismissTint()
            dismissOverlay()
            // If the user is sitting in a monitored app at midnight, re-enter
            // through the friction wait screen instead of granting silent access
            // (unless Grayzone is disabled or a break is running).
            if (currentPkg != null && monitoredApps.contains(currentPkg) &&
                prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, true) &&
                !scheduleManager.isBreakActive()
            ) {
                sessionStartTime = 0L
                showOverlay(currentPkg, getAppName(currentPkg), OverlayMode.FRICTION, 0L)
            }
        }
    }

    /**
     * Handles foreground app changes from AccessibilityService.
     * Serializes app transitions and keeps the latest foreground package while processing.
     * 
     * Benefits over polling:
     * - ~95% reduction in battery usage (event-driven vs 1-second polling)
     * - Instant detection (0ms vs 1000ms lag)
     * - More reliable across all Android OEMs
     */
    private fun handleForegroundAppChanged(currentPkg: String) {
        synchronized(transitionLock) {
            queuedForegroundPackage = currentPkg
            if (transitionJob?.isActive == true) return
            transitionJob = serviceScope.launch { drainForegroundTransitions() }
        }
    }

    private suspend fun drainForegroundTransitions() {
        while (true) {
            val currentPkg = synchronized(transitionLock) {
                val next = queuedForegroundPackage
                if (next == null) {
                    transitionJob = null
                    return
                }
                queuedForegroundPackage = null
                next
            }
            processForegroundAppChanged(currentPkg)
        }
    }

    private suspend fun processForegroundAppChanged(currentPkg: String) {
        try {
            if (currentPkg == packageName || !hasLauncherIntent(currentPkg)) return
            if (currentPkg == lastForegroundPackage) return

            val oldPackage = lastForegroundPackage
            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            // New-day safety net: cheap date-key comparison on every transition
            // so stale locks never survive midnight even if the timer job died.
            com.grayzone.app.data.RuntimeSessionReset.resetIfNeeded(prefs)

            com.grayzone.app.GrayzoneLogger.d(
                com.grayzone.app.LogComponent.OVERLAY,
                "Foreground app changed",
                mapOf("from" to (oldPackage ?: "null"), "to" to currentPkg)
            )

            // 1. Process Background Event for old package
            if (oldPackage != null) {
                val oldState = buildSessionState(oldPackage, prefs, now)
                val bgDuration = if (sessionStartTime > 0) now - sessionStartTime else 0L
                val bgCommands = policyEngine.evaluate(
                    com.grayzone.app.policy.AppEvent.AppBackgrounded(oldPackage, bgDuration), 
                    oldState, 
                    now, 
                    getAppName(oldPackage)
                )
                bgCommands.forEach { executeCommand(it, prefs) }
                sessionStartTime = 0L
            }

            lastForegroundPackage = currentPkg
            
            // 2. Process Foreground Event for new package
            val newState = buildSessionState(currentPkg, prefs, now)
            val fgCommands = policyEngine.evaluate(
                com.grayzone.app.policy.AppEvent.AppForegrounded(currentPkg),
                newState,
                now,
                getAppName(currentPkg)
            )
            
            var didShowWaitScreen = false
            // A paused session resumed via UpdateState is also an active session,
            // even though the pre-command state snapshot says otherwise.
            var hasActiveSession = newState.hasActiveSession(now)
            fgCommands.forEach { cmd ->
                if (cmd is com.grayzone.app.policy.SessionCommand.ShowWaitScreen) didShowWaitScreen = true
                if (cmd is com.grayzone.app.policy.SessionCommand.UpdateState &&
                    (cmd.newActiveUntil ?: 0L) > now
                ) {
                    hasActiveSession = true
                }
                executeCommand(cmd, prefs)
            }
            
            // If wait screen wasn't shown (meaning session active or dismissed), we start timing.
            // During a break, usage is timed too so daily budgets stay accurate.
            if (!didShowWaitScreen && (hasActiveSession || newState.isOnBreak) && newState.isMonitored) {
                sessionStartTime = now
            }

        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.OVERLAY,
                "Failed to process foreground app change",
                e
            )
        }
    }
    
    private fun buildSessionState(pkg: String, prefs: android.content.SharedPreferences, now: Long): com.grayzone.app.policy.SessionState {
        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
        val isMonitored = monitoredApps.contains(pkg)
        val isGrayzoneEnabled = prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, true)
        val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
        val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
        // Normalize so stale or oversized paused values (>24h) are treated as expired,
        // matching isAnyAppLocked() and the UI screens.
        val remainingMillis = getNormalizedRemainingMillis(prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L))
        
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, false)
        val defaultSessionMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, 10) else prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
        val defaultLockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, 60) else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)

        // Calculate budget
        val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
        var budgetRemainingMs = Long.MAX_VALUE
        if (budgetMins > 0) {
            val dateKey = DateUtils.getCurrentDateKey()
            val lastReset = prefs.getString(PrefsKeys.DAILY_RESET_DATE + pkg, "")
            val usedMs = if (lastReset == dateKey) prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L) else 0L
            budgetRemainingMs = (budgetMins * 60 * 1000L) - usedMs
        }

        return com.grayzone.app.policy.SessionState(
            packageName = pkg,
            isMonitored = isMonitored,
            isGrayzoneEnabled = isGrayzoneEnabled,
            activeUntil = activeUntil,
            lockedUntil = lockedUntil,
            budgetRemainingMs = budgetRemainingMs,
            sessionRemainingMs = remainingMillis,
            defaultSessionMins = defaultSessionMins,
            defaultLockoutMins = defaultLockoutMins,
            isScheduleLocked = scheduleManager.isCurrentlyScheduled(),
            isOnBreak = scheduleManager.isBreakActive()
        )
    }
    
    private suspend fun executeCommand(cmd: com.grayzone.app.policy.SessionCommand, prefs: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        when (cmd) {
            is com.grayzone.app.policy.SessionCommand.DismissOverlay -> {
                serviceScope.launch(Dispatchers.Main) { dismissTint() }
            }
            is com.grayzone.app.policy.SessionCommand.ShowWaitScreen -> {
                serviceScope.launch(Dispatchers.Main) {
                    showOverlay(cmd.packageName, cmd.appName, com.grayzone.app.OverlayMode.FRICTION, 0L)
                }
            }
            is com.grayzone.app.policy.SessionCommand.ShowLockoutScreen -> {
                serviceScope.launch(Dispatchers.Main) {
                    showOverlay(cmd.packageName, cmd.appName, com.grayzone.app.OverlayMode.LOCK, cmd.lockedUntil)
                }
            }
            is com.grayzone.app.policy.SessionCommand.ShowScheduleLockScreen -> {
                serviceScope.launch(Dispatchers.Main) {
                    showOverlay(cmd.packageName, cmd.appName, com.grayzone.app.OverlayMode.SCHEDULE_LOCK, 0L)
                }
            }
            is com.grayzone.app.policy.SessionCommand.ShowBudgetLockScreen -> {
                serviceScope.launch(Dispatchers.Main) {
                    showOverlay(cmd.packageName, cmd.appName, com.grayzone.app.OverlayMode.BUDGET_LOCK, 0L)
                }
            }
            is com.grayzone.app.policy.SessionCommand.RecordUsage -> {
                logUsageAndCheckBudget(cmd.packageName, now - cmd.durationMs, cmd.wasBlocked)
            }
            is com.grayzone.app.policy.SessionCommand.UpdateState -> {
                val editor = prefs.edit()
                cmd.newActiveUntil?.let { editor.putLong(PrefsKeys.ACTIVE_UNTIL + cmd.packageName, it) }
                cmd.newLockedUntil?.let { editor.putLong(PrefsKeys.LOCKED_UNTIL + cmd.packageName, it) }
                cmd.newSessionRemainingMs?.let { editor.putLong(PrefsKeys.REMAINING_MILLIS + cmd.packageName, it) }
                if (cmd.clearActiveSession) {
                    editor.remove(PrefsKeys.ACTIVE_UNTIL + cmd.packageName)
                    editor.remove(PrefsKeys.LOCKED_UNTIL + cmd.packageName)
                }
                
                if (cmd.newActiveUntil != null) {
                    val activeUntil = cmd.newActiveUntil
                    serviceScope.launch(Dispatchers.Main) { showTint() }
                    startCountdownNotification(cmd.packageName, activeUntil)
                    scheduleLockoutCheck(cmd.packageName, activeUntil - now)
                }
                editor.commit()
            }
            is com.grayzone.app.policy.SessionCommand.CancelCountdownNotification -> {
                cancelCountdownNotification()
            }
        }
    }
    /**
     * Check if package is the home launcher.
     * Cache refreshes every 30 seconds to detect launcher changes.
     */
    private fun isHomeLauncher(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        
        // Refresh cache if null or older than 30 seconds
        if (cachedHomeLauncherPkg == null || (now - homeLauncherCacheTime) > 30_000) {
            cachedHomeLauncherPkg = packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            homeLauncherCacheTime = now
        }
        return cachedHomeLauncherPkg == pkg
    }

    private fun hasLauncherIntent(pkg: String): Boolean =
        packageManager.getLaunchIntentForPackage(pkg) != null || isHomeLauncher(pkg)

    private suspend fun logUsageAndCheckBudget(
        pkg: String,
        startTime: Long,
        wasBlocked: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val durationMs = now - startTime
        if (durationMs <= 0) return@withContext
        val currentFgPkg = lastForegroundPackage

        try {
            val dao = UsageDatabase.getInstance(this@OverlayService).usageDao()
            val appName = getAppName(pkg)

            val dateKey = DateUtils.getCurrentDateKey()
            dao.insertEvent(
                UsageEvent(
                    packageName = pkg,
                    appName = appName,
                    startTime = startTime,
                    endTime = now,
                    durationMillis = durationMs,
                    wasBlocked = wasBlocked,
                    dateKey = dateKey
                )
            )

            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
            if (budgetMins > 0) {
                val lastReset = prefs.getString(PrefsKeys.DAILY_RESET_DATE + pkg, "")

                val newUsedMs = if (lastReset == dateKey) {
                    prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L) + durationMs
                } else {
                    durationMs
                }

                prefs.edit()
                    .putLong(PrefsKeys.DAILY_USED_MILLIS + pkg, newUsedMs)
                    .putString(PrefsKeys.DAILY_RESET_DATE + pkg, dateKey)
                    .commit()

                // Don't slam the budget lock down mid-break; it will be enforced
                // by the policy engine on the next foreground once the break ends.
                if (newUsedMs >= budgetMins * 60 * 1000L && currentFgPkg == pkg &&
                    !scheduleManager.isBreakActive()
                ) {
                    withContext(Dispatchers.Main) {
                        showOverlay(pkg, appName, OverlayMode.BUDGET_LOCK, 0L)
                    }
                }
            }
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.DATABASE,
                "Failed to log usage",
                e
            )
        }
    }
    private fun scheduleLockoutCheck(packageName: String, delayMs: Long) {
        lockoutJobs[packageName]?.cancel()
        lockoutJobs[packageName] = serviceScope.launch {
            delay(delayMs)
            val currentPkg = lastForegroundPackage
            if (currentPkg == packageName) {
                val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + packageName, 0L)
                launch(Dispatchers.Main) { showOverlay(packageName, getAppName(packageName), OverlayMode.LOCK, lockedUntil) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            dismissOverlay(); stopSelf()
        } else if (intent?.action == ACTION_START_COUNTDOWN) {
            val pkg = intent.getStringExtra("package_name") ?: ""
            val activeUntil = intent.getLongExtra("active_until", 0L)
            if (pkg.isNotEmpty() && activeUntil > 0) {
                startCountdownNotification(pkg, activeUntil)
            }
        } else if (intent?.action == ACTION_STOP_COUNTDOWN) {
            cancelCountdownNotification()
        }
        return START_STICKY
    }

    private fun startCountdownNotification(packageName: String, activeUntil: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appName = getAppName(packageName)

        val remainingMs = activeUntil - System.currentTimeMillis()
        if (remainingMs <= 0) {
            cancelCountdownNotification()
            return
        }

        val rv = RemoteViews(this.packageName, R.layout.notification_countdown)
        rv.setTextViewText(R.id.notif_title, appName)
        rv.setTextViewText(R.id.notif_subtitle, "Session active")

        val base = android.os.SystemClock.elapsedRealtime() + remainingMs
        rv.setChronometer(R.id.notif_timer, base, null, true)
        rv.setChronometerCountDown(R.id.notif_timer, true)

        nm.notify(NOTIF_ID,
            NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
                // Small icons must be monochrome alpha masks; the vector asset
                // guarantees the Grayzone glyph renders instead of a grey square.
                .setSmallIcon(R.drawable.ic_notification)
                // Full-colour launcher logo shown as the large icon so the app is
                // recognisable in the notification (small icon can't carry colour).
                .setLargeIcon(appLogoBitmap())
                .setContentTitle(appName)
                .setContentText("Session active")
                .setSubText("Grayzone")
                .setShowWhen(false)
                .setCustomContentView(rv)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(PURPLE)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
        )
    }
    
    private fun cancelCountdownNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        com.grayzone.app.data.ProtectionHealthRepository.updateOverlayStatus(false)
        appChangeReceiver?.let { unregisterReceiver(it) }
        serviceScope.cancel()
        dismissOverlay()
        dismissTint()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // â”€â”€â”€ Overlay UI â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private var daltonizerActive = false

    private fun showTint() {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefsKeys.GRAYSCALE_ENABLED, true)) {
            dismissTint()
            return
        }

        dismissOverlayTintFallback()

        // Try true hardware Daltonizer first (requires WRITE_SECURE_SETTINGS via ADB).
        if (GrayscaleManager.enable(this)) {
            daltonizerActive = true
            return
        }

        // Fallback: draw a full-screen hardware-accelerated View whose Paint uses
        // the ITU-R BT.601 luminosity ColorMatrix. This actually desaturates every
        // pixel underneath rather than just washing the screen grey.
        //
        // The matrix maps each pixel's (R,G,B) â†’ luminance Y using:
        //   Y = 0.299R + 0.587G + 0.114B
        // and outputs (Y,Y,Y,A) â€” visually identical to true monochrome.
        if (tintView != null) return

        // Check if we still have overlay permission before attempting to add the tint view
        if (!android.provider.Settings.canDrawOverlays(this)) {
            GrayzoneLogger.w(
                LogComponent.OVERLAY,
                "Cannot show grayscale tint overlay: SYSTEM_ALERT_WINDOW permission revoked"
            )
            return
        }

        val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val paint  = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }

        val view = object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                // Cover the entire canvas with a transparent rect drawn through the
                // desaturating paint â€” this forces every pixel through the ColorMatrix.
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        tintView = view
        try {
            windowManager?.addView(view, wlp)
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "Failed to add grayscale tint overlay: ${e.message}")
        }
    }

    private fun dismissOverlayTintFallback() {
        tintView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.OVERLAY, "Failed to remove grayscale tint overlay: ${e.message}")
            }
        }
        tintView = null
    }

    private fun dismissTint() {
        if (daltonizerActive) {
            GrayscaleManager.disable(this)
            daltonizerActive = false
        }
        dismissOverlayTintFallback()
    }

    @SuppressLint("SetTextI18n")
    private fun showOverlay(packageName: String, appName: String, mode: Int, lockedUntil: Long) {
        // Check if overlay permission is still granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                com.grayzone.app.GrayzoneLogger.w(
                    com.grayzone.app.LogComponent.OVERLAY,
                    "Overlay permission revoked, cannot show overlay"
                )
                
                // Show user-friendly Toast message
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this,
                        "Grayzone needs Display Over Other Apps permission. Please grant it in Settings.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                
                // Try to open settings for user to re-grant
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    com.grayzone.app.GrayzoneLogger.e(
                        com.grayzone.app.LogComponent.OVERLAY,
                        "Failed to open overlay settings",
                        e
                    )
                }
                return
            }
        }
        
        dismissOverlay()
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        val isLocked = mode == OverlayMode.LOCK
        val isBudgetLock = mode == OverlayMode.BUDGET_LOCK
        val isScheduleLock = mode == OverlayMode.SCHEDULE_LOCK
        val isAnyLock = isLocked || isBudgetLock || isScheduleLock
        
        if (isAnyLock) {
            cancelCountdownNotification()
        }
        
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val customWait = prefs.getInt(PrefsKeys.PER_APP_WAIT_SECONDS + packageName, DEFAULT_WAIT)
        val globalWait = prefs.getInt(PrefsKeys.WAIT_SECONDS, DEFAULT_WAIT)

        secondsRemaining = if (isLocked) {
            ((lockedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt()
        } else if (isBudgetLock || isScheduleLock) {
            0
        } else {
            if (hasCustom) customWait else globalWait
        }
        val totalSeconds = secondsRemaining
        
        val customPromptsJson = prefs.getString(PrefsKeys.CUSTOM_PROMPTS_JSON, null)
        val useCustomOnly = prefs.getBoolean(PrefsKeys.USE_CUSTOM_PROMPTS_ONLY, false)
        val promptsList = mutableListOf<String>()
        if (!useCustomOnly) {
            promptsList.addAll(Prompts.DEFAULT)
        }
        if (customPromptsJson != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                val customList: List<String> = Gson().fromJson(customPromptsJson, type)
                promptsList.addAll(customList)
            } catch (e: Exception) {}
        }
        if (promptsList.isEmpty()) promptsList.addAll(Prompts.DEFAULT)
        val prompt = promptsList.random()

        val ctx = this
        val root = FrameLayout(ctx).apply { setBackgroundColor(BG) }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        val appNameView = TextView(ctx).apply {
            text = appName.uppercase()
            textSize = 11f
            setTextColor(GREY_DIM)
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT
        }

        val titleView = TextView(ctx).apply {
            text = if (isAnyLock) "Locked." else "Pause."
            textSize = 34f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val divider = View(ctx).apply { setBackgroundColor(BORDER) }
        val divParams = LinearLayout.LayoutParams(dp(48), dp(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16); bottomMargin = dp(16)
        }

        val promptView = TextView(ctx).apply {
            text = when {
                isBudgetLock -> "Daily usage limit reached."
                isScheduleLock -> "Focus mode / schedule is active."
                isLocked -> "You have reached your session limit."
                else -> "\u201C$prompt\u201D"
            }
            textSize = 17f
            setTextColor(0xFFDDDDEE.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }

        val countdownView = TextView(ctx).apply {
            text = if (isLocked) formatDuration(secondsRemaining) else if (isBudgetLock || isScheduleLock) "â€”" else "$secondsRemaining"
            textSize = if (isLocked) 42f else 72f
            setTextColor(PURPLE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = totalSeconds
            this.progress = totalSeconds
            progressTintList = android.content.res.ColorStateList.valueOf(PURPLE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(BORDER)
        }
        val progressParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) }
        
        if (isAnyLock) {
            progress.visibility = View.GONE
        }

        val subView = TextView(ctx).apply {
            text = when {
                isLocked -> "Remaining lockout time"
                isBudgetLock -> "Resets tomorrow"
                isScheduleLock -> "Try again later"
                else -> "Opening in $secondsRemaining secondsâ€¦"
            }
            textSize = 12f
            setTextColor(GREY)
            gravity = Gravity.CENTER
        }
        val subParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(24) }

        val skipBtn = Button(ctx).apply {
            text = if (isAnyLock) "â† Leave App" else "â† I'll Skip This"
            setTextColor(GREY)
            background = makeOutlineDrawable()
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val skipParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
        skipBtn.setOnClickListener {
            if (mode == OverlayMode.FRICTION) {
                val waitedMs = (totalSeconds - secondsRemaining).coerceAtLeast(0) * 1000L
                recordFrictionSkip(packageName, waitedMs)
            }
            dismissOverlay()
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }

        card.addView(appNameView, wrapCentered())
        card.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        card.addView(divider, divParams)
        card.addView(promptView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(countdownView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        card.addView(progress, progressParams)
        card.addView(subView, subParams)
        card.addView(skipBtn, skipParams)

        val cardParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) }
        card.background = makeCardBackground()
        root.addView(card, cardParams)

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        overlayView = root
        try { windowManager?.addView(root, wlp) } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}"); return
        }

        // Smooth fade-in animation
        val alphaAnim = android.view.animation.AlphaAnimation(0f, 1f).apply {
            duration = 300
            fillAfter = true
        }
        root.startAnimation(alphaAnim)

        countdownJobUI?.cancel()
        countdownJobUI = serviceScope.launch(Dispatchers.Main) {
            while (secondsRemaining > 0) {
                delay(1000)
                secondsRemaining--
                val remaining = secondsRemaining
                if (remaining > 0) {
                    countdownView.text = if (isLocked) formatDuration(remaining) else if (isBudgetLock || isScheduleLock) "â€”" else "$remaining"
                    if (!isAnyLock) {
                        subView.text = "Opening in $remaining secondsâ€¦"
                        progress.progress = remaining
                    }
                }
            }
            // Countdown finished (also reached immediately if it started at 0).
            // If this job was cancelled while the loop was exiting (e.g. an app
            // change dismissed the overlay), don't grant a session.
            if (!isActive) return@launch
            when {
                !isAnyLock -> {
                    markAppUnlocked(packageName)
                    dismissOverlay()
                }
                isLocked -> {
                    // Lockout expired while the lock screen was showing. Don't grant
                    // silent, untracked access: fall back to the friction wait screen
                    // if the user is still in this app.
                    dismissOverlay()
                    if (lastForegroundPackage == packageName) {
                        showOverlay(packageName, appName, OverlayMode.FRICTION, 0L)
                    }
                }
                // BUDGET_LOCK / SCHEDULE_LOCK have no countdown; they stay up
                // until the user leaves the app.
            }
        }
    }

    private fun dismissOverlay() {
        countdownJobUI?.cancel(); countdownJobUI = null
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.OVERLAY, "Failed to remove overlay: ${e.message}")
            }
        }
        overlayView = null
    }

    private fun markAppUnlocked(packageName: String) {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val sessionMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + packageName, 10) else prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
        val lockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 60) else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)

        // Validate session duration: must be between 1 minute and 24 hours
        val validSessionMins = sessionMins.coerceIn(1, 24 * 60)
        if (validSessionMins != sessionMins) {
            GrayzoneLogger.w(
                LogComponent.OVERLAY,
                "Invalid session duration $sessionMins mins for $packageName, clamped to $validSessionMins"
            )
        }

        // Validate lockout duration: must be between 15 minutes and 24 hours
        val validLockoutMins = lockoutMins.coerceIn(15, 24 * 60)
        if (validLockoutMins != lockoutMins) {
            GrayzoneLogger.w(
                LogComponent.OVERLAY,
                "Invalid lockout duration $lockoutMins mins for $packageName, clamped to $validLockoutMins"
            )
        }

        val activeUntil = now + (validSessionMins * 60 * 1000L)
        val lockedUntil = activeUntil + (validLockoutMins * 60 * 1000L)

        prefs.edit()
            .remove(PrefsKeys.REMAINING_MILLIS + packageName)
            .putLong(PrefsKeys.ACTIVE_UNTIL + packageName, activeUntil)
            .putLong(PrefsKeys.LOCKED_UNTIL + packageName, lockedUntil)
            .apply()

        startCountdownNotification(packageName, activeUntil)
        scheduleLockoutCheck(packageName, validSessionMins * 60 * 1000L)
        showTint()
        sessionStartTime = now
    }

    private fun recordFrictionSkip(packageName: String, waitedMs: Long) {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val lockoutMins = if (hasCustom) {
            prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 60)
        } else {
            prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
        }

        StreakManager(this).recordBlockedSession(lockoutMins)

        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dateKey = DateUtils.getCurrentDateKey()
                UsageDatabase.getInstance(this@OverlayService).usageDao().insertEvent(
                    UsageEvent(
                        packageName = packageName,
                        appName = getAppName(packageName),
                        startTime = now - waitedMs,
                        endTime = now,
                        durationMillis = waitedMs,
                        wasBlocked = true,
                        dateKey = dateKey
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log blocked session: ${e.message}")
            }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    private fun wrapCentered() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun makeCardBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), BORDER)
        }
    }

    private fun makeOutlineDrawable(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), GREY_DIM)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Grayzone Active", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Grayzone is monitoring your app usage"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * The colour launcher logo as a bitmap for use as a notification large icon.
     * Cached because it's read on every notification rebuild. Returns null if the
     * resource can't be decoded, in which case the notification simply omits it.
     */
    private var cachedLogoBitmap: android.graphics.Bitmap? = null
    private fun appLogoBitmap(): android.graphics.Bitmap? {
        cachedLogoBitmap?.let { return it }
        return try {
            (androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher))
                ?.let { d ->
                    val size = resources.getDimensionPixelSize(
                        android.R.dimen.notification_large_icon_width
                    ).coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        size, size, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, size, size)
                    d.draw(canvas)
                    cachedLogoBitmap = bmp
                    bmp
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
            .setContentTitle("Grayzone is active")
            .setContentText("Monitoring app usage to help you stay focused.")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appLogoBitmap())
            .setColor(PURPLE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

}



