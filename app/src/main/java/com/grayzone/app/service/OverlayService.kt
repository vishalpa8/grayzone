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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import android.app.PendingIntent
class OverlayService : Service() {

    companion object {
        const val TAG = "GrayzoneOverlay"
        const val CHANNEL_ID = "grayzone_overlay"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.grayzone.app.ACTION_STOP_OVERLAY"
        const val ACTION_START_COUNTDOWN = "com.grayzone.app.ACTION_START_COUNTDOWN"
        const val ACTION_STOP_COUNTDOWN = "com.grayzone.app.ACTION_STOP_COUNTDOWN"
        const val ACTION_SCHEDULE_LOCKOUT_CHECK = "com.grayzone.app.ACTION_SCHEDULE_LOCKOUT_CHECK"
        private const val DEFAULT_WAIT = 8

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

    private val appOpenedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AppAccessibilityService.ACTION_APP_OPENED) return
            when (intent.getIntExtra("overlay_mode", OverlayMode.FRICTION)) {
                OverlayMode.REMOVE_TINT -> { dismissTint(); return }
                OverlayMode.TINT -> { showTint(); return }
                else -> {
                    val pkg = intent.getStringExtra(AppAccessibilityService.EXTRA_PACKAGE_NAME) ?: return
                    val mode = intent.getIntExtra("overlay_mode", OverlayMode.FRICTION)
                    val lockedUntil = intent.getLongExtra("locked_until", 0L)
                    showOverlay(pkg, getAppName(pkg), mode, lockedUntil)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(AppAccessibilityService.ACTION_APP_OPENED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appOpenedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(appOpenedReceiver, filter)
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
        } else if (intent?.action == ACTION_SCHEDULE_LOCKOUT_CHECK) {
            val pkg = intent.getStringExtra("package_name") ?: ""
            val delayMs = intent.getLongExtra("delay_ms", 0L)
            if (pkg.isNotEmpty() && delayMs > 0) {
                scheduleLockoutCheck(pkg, delayMs)
            }
        }
        return START_STICKY
    }

    private fun startCountdownNotification(packageName: String, activeUntil: Long) {
        countdownJob?.cancel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appName = getAppName(packageName)

        countdownJob = serviceScope.launch(Dispatchers.Main) {
            while (true) {
                val remaining = ((activeUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt()
                if (remaining <= 0) {
                    cancelCountdownNotification()
                    break
                }
                val timeStr = String.format("%02d:%02d", remaining / 60, remaining % 60)
                nm.notify(NOTIF_ID,
                    NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("$appName Active")
                        .setContentText("Time remaining: $timeStr")
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build()
                )
                kotlinx.coroutines.delay(1000)
            }
        }
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
        try { unregisterReceiver(appOpenedReceiver) } catch (_: Exception) {}
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
        if (GrayscaleManager.enable(this)) {
            daltonizerActive = true
            return
        }

        if (tintView != null) return
        val view = View(this).apply { setBackgroundColor(0x80808080.toInt()) }
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    private fun showOverlay(packageName: String, appName: String, mode: Int, lockedUntil: Long) {
        dismissOverlay()
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        val isLocked = mode == OverlayMode.LOCK
        val isBudgetLock = mode == OverlayMode.BUDGET_LOCK
        val isScheduleLock = mode == OverlayMode.SCHEDULE_LOCK
        val isAnyLock = isLocked || isBudgetLock || isScheduleLock
        
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
        val lockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 30) else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 30)

        val activeUntil = now + (sessionMins * 60 * 1000L)
        val lockedUntil = activeUntil + (lockoutMins * 60 * 1000L)

        prefs.edit()
            .putLong(PrefsKeys.ACTIVE_UNTIL + packageName, activeUntil)
            .putLong(PrefsKeys.LOCKED_UNTIL + packageName, lockedUntil)
            .apply()

        startCountdownNotification(packageName, activeUntil)
        scheduleLockoutCheck(packageName, sessionMins * 60 * 1000L)
        showTint()
        sendBroadcast(Intent(AppAccessibilityService.ACTION_SESSION_STARTED).apply {
            setPackage(applicationContext.packageName)
            putExtra(AppAccessibilityService.EXTRA_PACKAGE_NAME, packageName)
        })
    }

    private fun recordFrictionSkip(packageName: String, waitedMs: Long) {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val lockoutMins = if (hasCustom) {
            prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 30)
        } else {
            prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 30)
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
    
    private fun scheduleLockoutCheck(packageName: String, delayMs: Long) {
        lockoutJobs[packageName]?.cancel()
        lockoutJobs[packageName] = serviceScope.launch {
            delay(delayMs)
            val intent = Intent(AppAccessibilityService.ACTION_CHECK_LOCKOUT).apply {
                setPackage(applicationContext.packageName)
                putExtra(AppAccessibilityService.EXTRA_PACKAGE_NAME, packageName)
            }
            sendBroadcast(intent)
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
}
