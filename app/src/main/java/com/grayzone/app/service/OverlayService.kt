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
import com.grayzone.app.data.StreakManager
import com.grayzone.app.data.UsageDatabase
import com.grayzone.app.data.UsageEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
    private var countdownJob: Job? = null
    private var secondsRemaining = DEFAULT_WAIT
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lockoutJobs = java.util.HashMap<String, Job>()
    
    // Usage stats state
    private var lastForegroundPackage: String? = null
    private var sessionStartTime: Long = 0L
    private var cachedHomeLauncherPkg: String? = null
    private var monitorJob: Job? = null
    private lateinit var scheduleManager: ScheduleManager

    override fun onCreate() {
        super.onCreate()
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

        startUsageMonitoring()
    }

    private fun startUsageMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            while (true) {
                checkForegroundApp(usm)
                delay(1000) // Poll every 1 second
            }
        }
    }

    private fun checkForegroundApp(usm: UsageStatsManager) {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10000, now) // Look back 10 seconds to catch recent resumes
        var currentPkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentPkg = event.packageName
            }
        }
        
        // If we didn't get a recent resumed event, we assume the last known package is still foreground
        if (currentPkg == null) {
            currentPkg = lastForegroundPackage
        }

        if (currentPkg == null) return
        if (currentPkg == packageName || !hasLauncherIntent(currentPkg)) return

        if (currentPkg == lastForegroundPackage) return

        val oldPackage = lastForegroundPackage
        lastForegroundPackage = currentPkg
        Log.d(TAG, "Foreground app changed to: $currentPkg")

        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        // Handle backgrounding the old app
        if (oldPackage != null) {
            cancelCountdownNotification()
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
                }
            }
        }

        if (!prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, true)) {
            serviceScope.launch(Dispatchers.Main) { dismissTint() }
            return
        }

        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
        if (!monitoredApps.contains(currentPkg)) {
            serviceScope.launch(Dispatchers.Main) { dismissTint() }
            return
        }

        val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + currentPkg, 0L)
        val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + currentPkg, 0L)
        val remainingMillis = prefs.getLong(PrefsKeys.REMAINING_MILLIS + currentPkg, 0L)

        // 1. Enforce Schedule Lock
        if (scheduleManager.isCurrentlyScheduled()) {
            serviceScope.launch(Dispatchers.Main) { showOverlay(currentPkg, getAppName(currentPkg), OverlayMode.SCHEDULE_LOCK, 0L) }
            return
        }

        // 2. Enforce Daily Budget Lock
        val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + currentPkg, 0)
        if (budgetMins > 0) {
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val lastReset = prefs.getString(PrefsKeys.DAILY_RESET_DATE + currentPkg, "")
            val usedMs = if (lastReset == dateKey) prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + currentPkg, 0L) else 0L
            if (usedMs >= budgetMins * 60 * 1000L) {
                serviceScope.launch(Dispatchers.Main) { showOverlay(currentPkg, getAppName(currentPkg), OverlayMode.BUDGET_LOCK, 0L) }
                return
            }
        }

        // 3. Handle Timed Sessions
        if (now < activeUntil) {
            serviceScope.launch(Dispatchers.Main) { showTint() }
            startCountdownNotification(currentPkg, activeUntil)
            sessionStartTime = now
            scheduleLockoutCheck(currentPkg, activeUntil - now)
        } else if (now < lockedUntil) {
            serviceScope.launch(Dispatchers.Main) { showOverlay(currentPkg, getAppName(currentPkg), OverlayMode.LOCK, lockedUntil) }
        } else if (remainingMillis > 0) {
            val newActiveUntil = now + remainingMillis
            val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + currentPkg, false)
            val lockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + currentPkg, 60) else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
            val newLockedUntil = newActiveUntil + (lockoutMins * 60 * 1000L)
            prefs.edit()
                .putLong(PrefsKeys.ACTIVE_UNTIL + currentPkg, newActiveUntil)
                .putLong(PrefsKeys.LOCKED_UNTIL + currentPkg, newLockedUntil)
                .remove(PrefsKeys.REMAINING_MILLIS + currentPkg)
                .apply()
            serviceScope.launch(Dispatchers.Main) { showTint() }
            startCountdownNotification(currentPkg, newActiveUntil)
            sessionStartTime = now
            scheduleLockoutCheck(currentPkg, remainingMillis)
        } else {
            serviceScope.launch(Dispatchers.Main) { showOverlay(currentPkg, getAppName(currentPkg), OverlayMode.FRICTION, 0L) }
        }
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
                val dao = UsageDatabase.getInstance(this@OverlayService).usageDao()
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
                        
                    if (newUsedMs >= budgetMins * 60 * 1000L && currentFgPkg == pkg) {
                        launch(Dispatchers.Main) { showOverlay(pkg, appName, OverlayMode.BUDGET_LOCK, 0L) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log usage: ${e.message}")
            }
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
        countdownJob?.cancel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appName = getAppName(packageName)

        val remainingMs = activeUntil - System.currentTimeMillis()
        if (remainingMs <= 0) {
            cancelCountdownNotification()
            return
        }

        // Custom layout: title on the left, countdown timer pinned to the right.
        // Using RemoteViews + Chronometer gives a smooth live countdown without
        // the timer bleeding into the header row that setUsesChronometer produces.
        val rv = RemoteViews(this.packageName, R.layout.notification_countdown)
        rv.setTextViewText(R.id.notif_title, "$appName — session active")
        // Chronometer.base is relative to SystemClock.elapsedRealtime(), not wall-clock.
        // Compute how many ms remain, then set base = elapsedRealtime + remainingMs
        // so the countdown reads the correct value from the start.
        val base = android.os.SystemClock.elapsedRealtime() + remainingMs
        rv.setChronometer(R.id.notif_timer, base, null, true)
        rv.setChronometerCountDown(R.id.notif_timer, true)

        nm.notify(NOTIF_ID,
            NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setCustomContentView(rv)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }
    
    private fun cancelCountdownNotification() {
        countdownJob?.cancel()
        countdownJob = null
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        dismissOverlay()
        dismissTint()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay UI ──────────────────────────────────────────────────────────

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
        // The matrix maps each pixel's (R,G,B) → luminance Y using:
        //   Y = 0.299R + 0.587G + 0.114B
        // and outputs (Y,Y,Y,A) — visually identical to true monochrome.
        if (tintView != null) return

        val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val paint  = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }

        val view = object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                // Cover the entire canvas with a transparent rect drawn through the
                // desaturating paint — this forces every pixel through the ColorMatrix.
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
        try { windowManager?.addView(view, wlp) } catch (e: Exception) {}
    }

    private fun dismissOverlayTintFallback() {
        tintView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
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
            text = if (isLocked) formatDuration(secondsRemaining) else if (isBudgetLock || isScheduleLock) "—" else "$secondsRemaining"
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
                else -> "Opening in $secondsRemaining seconds…"
            }
            textSize = 12f
            setTextColor(GREY)
            gravity = Gravity.CENTER
        }
        val subParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(24) }

        val skipBtn = Button(ctx).apply {
            text = if (isAnyLock) "← Leave App" else "← I'll Skip This"
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
                if (remaining <= 0) {
                    if (!isAnyLock) markAppUnlocked(packageName)
                    dismissOverlay()
                } else {
                    countdownView.text = if (isLocked) formatDuration(remaining) else if (isBudgetLock || isScheduleLock) "—" else "$remaining"
                    if (!isAnyLock) {
                        subView.text = "Opening in $remaining seconds…"
                        progress.progress = remaining
                    }
                }
            }
        }
    }

    private fun dismissOverlay() {
        countdownJobUI?.cancel(); countdownJobUI = null
        overlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    private fun markAppUnlocked(packageName: String) {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val sessionMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + packageName, 10) else prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
        val lockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 60) else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)

        val activeUntil = now + (sessionMins * 60 * 1000L)
        val lockedUntil = activeUntil + (lockoutMins * 60 * 1000L)

        prefs.edit()
            .putLong(PrefsKeys.ACTIVE_UNTIL + packageName, activeUntil)
            .putLong(PrefsKeys.LOCKED_UNTIL + packageName, lockedUntil)
            .apply()

        startCountdownNotification(packageName, activeUntil)
        scheduleLockoutCheck(packageName, sessionMins * 60 * 1000L)
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
                val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
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

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grayzone is active")
            .setContentText("Monitoring for distracting apps")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

}
