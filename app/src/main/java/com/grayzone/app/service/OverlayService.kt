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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask

class OverlayService : Service() {

    companion object {
        const val TAG = "GrayzoneOverlay"
        const val CHANNEL_ID = "grayzone_overlay"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.grayzone.app.ACTION_STOP_OVERLAY"
        private const val DEFAULT_WAIT = 8
        private const val PREFS_NAME = "GrayzonePrefs"

        private val REFLECTIONS = listOf(
            "What were you hoping to feel by opening this?",
            "Is this impulse — or intention?",
            "What's the one thing you should be doing right now?",
            "Will this matter in an hour?",
            "Are you bored, anxious, or genuinely curious?",
            "What triggered this urge — stress, habit, or boredom?",
            "Could you do something more meaningful right now?",
            "Who needs your attention more than this app does?",
            "Is this a want or a need right now?",
            "What would your most focused self do instead?",
            "Are you running away from something?",
            "Can this wait 10 more minutes?"
        )

        // Hex color int helpers
        private const val BG          = 0xE607070F.toInt() // 90% opaque
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
    private var countdownTimer: Timer? = null
    private var secondsRemaining = DEFAULT_WAIT
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val appOpenedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppAccessibilityService.ACTION_APP_OPENED) {
                val pkg = intent.getStringExtra(AppAccessibilityService.EXTRA_PACKAGE_NAME) ?: return
                val mode = intent.getIntExtra("overlay_mode", 1)
                val lockedUntil = intent.getLongExtra("locked_until", 0L)
                val name = getAppName(pkg)
                showOverlay(pkg, name, mode, lockedUntil)
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
        if (intent?.action == ACTION_STOP) { dismissOverlay(); stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        dismissOverlay()
        try { unregisterReceiver(appOpenedReceiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay UI ──────────────────────────────────────────────────────────

    private fun showOverlay(packageName: String, appName: String, mode: Int, lockedUntil: Long) {
        dismissOverlay()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        val isLocked = mode == 2
        secondsRemaining = if (isLocked) {
            ((lockedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt()
        } else {
            prefs.getInt("wait_seconds", DEFAULT_WAIT)
        }
        val totalSeconds = secondsRemaining
        val prompt = REFLECTIONS.random()

        val ctx = this
        val root = FrameLayout(ctx).apply { setBackgroundColor(BG) }

        // Content card (centered column)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        // ── App name ─────────────────────────────────────────────────────
        val appNameView = TextView(ctx).apply {
            text = appName.uppercase()
            textSize = 11f
            setTextColor(GREY_DIM)
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT
        }

        // ── Title ────────────────────────────────────────────────────────
        val titleView = TextView(ctx).apply {
            text = if (isLocked) "Locked." else "Pause."
            textSize = 34f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        // ── Divider ───────────────────────────────────────────────────────
        val divider = View(ctx).apply {
            setBackgroundColor(BORDER)
        }
        val divParams = LinearLayout.LayoutParams(dp(48), dp(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16); bottomMargin = dp(16)
        }

        // ── Prompt ────────────────────────────────────────────────────────
        val promptView = TextView(ctx).apply {
            text = if (isLocked) "You have reached your session limit." else "\u201C$prompt\u201D"
            textSize = 17f
            setTextColor(0xFFDDDDEE.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }

        // ── Countdown ─────────────────────────────────────────────────────
        val countdownView = TextView(ctx).apply {
            text = if (isLocked) formatTime(secondsRemaining) else "$secondsRemaining"
            textSize = if (isLocked) 42f else 72f
            setTextColor(PURPLE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        // ── Progress bar ──────────────────────────────────────────────────
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = totalSeconds
            this.progress = totalSeconds
            progressTintList = android.content.res.ColorStateList.valueOf(PURPLE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(BORDER)
        }
        val progressParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { topMargin = dp(8) }
        
        if (isLocked) {
            progress.visibility = View.GONE
        }

        // ── Subtext ───────────────────────────────────────────────────────
        val subView = TextView(ctx).apply {
            text = if (isLocked) "Remaining lockout time" else "Opening in $secondsRemaining seconds…"
            textSize = 12f
            setTextColor(GREY)
            gravity = Gravity.CENTER
        }
        val subParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(24) }

        // ── Skip/Leave button ───────────────────────────────────────────────────
        val skipBtn = Button(ctx).apply {
            text = if (isLocked) "← Leave App" else "← I'll Skip This"
            setTextColor(GREY)
            background = makeOutlineDrawable()
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val skipParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        skipBtn.setOnClickListener {
            // Do not unlock if we are locked, or if we skip
            dismissOverlay()
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }

        // Assemble card
        card.addView(appNameView, wrapCentered())
        card.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        card.addView(divider, divParams)
        card.addView(promptView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(countdownView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        card.addView(progress, progressParams)
        card.addView(subView, subParams)
        card.addView(skipBtn, skipParams)

        // Center card in root
        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply { leftMargin = dp(24); rightMargin = dp(24) }
        // Rounded corners via background
        card.background = makeCardBackground()
        root.addView(card, cardParams)

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        overlayView = root
        try { windowManager?.addView(root, wlp) } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}"); return
        }

        // Countdown ticker
        countdownTimer = Timer()
        countdownTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                secondsRemaining--
                mainHandler.post {
                    if (secondsRemaining <= 0) {
                        if (!isLocked) {
                            markAppUnlocked(packageName)
                        }
                        dismissOverlay()
                    } else {
                        countdownView.text = if (isLocked) formatTime(secondsRemaining) else "$secondsRemaining"
                        if (!isLocked) {
                            subView.text = "Opening in $secondsRemaining seconds…"
                            progress.progress = secondsRemaining
                        }
                    }
                }
            }
        }, 1000, 1000)
    }
    
    private fun formatTime(seconds: Int): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) String.format("%dh %dm %ds", hrs, mins, secs)
        else String.format("%dm %ds", mins, secs)
    }

    private fun dismissOverlay() {
        countdownTimer?.cancel(); countdownTimer = null
        overlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    private fun markAppUnlocked(packageName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val sessionMins = prefs.getInt("session_minutes", 10)
        val lockoutMins = prefs.getInt("lockout_minutes", 30)
        
        val activeUntil = now + (sessionMins * 60 * 1000L)
        val lockedUntil = activeUntil + (lockoutMins * 60 * 1000L)
        
        prefs.edit()
            .putLong("active_until_$packageName", activeUntil)
            .putLong("locked_until_$packageName", lockedUntil)
            .apply()
            
        val intent = Intent("com.grayzone.app.ACTION_SESSION_STARTED").apply {
            putExtra("package_name", packageName)
        }
        sendBroadcast(intent)
            
        // Schedule lockout check if user is still in the app when session expires
        scheduleLockoutCheck(packageName, sessionMins * 60 * 1000L)
    }
    
    private fun scheduleLockoutCheck(packageName: String, delayMs: Long) {
        serviceScope.launch {
            kotlinx.coroutines.delay(delayMs)
            val intent = Intent(AppAccessibilityService.ACTION_CHECK_LOCKOUT).apply {
                setPackage(applicationContext.packageName)
                putExtra(AppAccessibilityService.EXTRA_PACKAGE_NAME, packageName)
            }
            sendBroadcast(intent)
        }
    }

    private fun getAppName(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            resources.displayMetrics).toInt()

    private fun wrapCentered() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

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
        val channel = NotificationChannel(CHANNEL_ID, "Grayzone Active",
            NotificationManager.IMPORTANCE_LOW).apply {
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
            .build()
}
