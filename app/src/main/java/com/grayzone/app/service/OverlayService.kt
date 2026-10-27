package com.grayzone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.RemoteViews
import android.util.TypedValue
import com.grayzone.app.OverlayMode
import com.grayzone.app.PrefsKeys
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.R
import com.grayzone.app.policy.SessionExpiryDecision
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.pm.PackageManager
import com.grayzone.app.data.ScheduleManager
import com.grayzone.app.service.overlay.CountdownFinishContext
import com.grayzone.app.service.overlay.OverlayPresentation
import com.grayzone.app.service.overlay.SecondsInputs
import com.grayzone.app.service.overlay.SkipContext

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "grayzone_overlay"
        const val RECOVERY_CHANNEL_ID = "grayzone_recovery"
        /** Session countdown lives on its own channel so it can be tuned/muted independently. */
        const val COUNTDOWN_CHANNEL_ID = "grayzone_session_timer"
        const val NOTIF_ID = 1
        const val RECOVERY_NOTIF_ID = 2
        /**
         * The countdown must NOT reuse [NOTIF_ID]. Sharing the foreground-service slot meant every
         * protection refresh (each app switch, each screen-on) overwrote a running timer, and each
         * countdown post overwrote the protection status — the two clobbered each other endlessly.
         */
        const val COUNTDOWN_NOTIF_ID = 3
        const val ACTION_STOP = "com.grayzone.app.ACTION_STOP_OVERLAY"
        const val ACTION_START_COUNTDOWN = "com.grayzone.app.ACTION_START_COUNTDOWN"
        const val ACTION_STOP_COUNTDOWN = "com.grayzone.app.ACTION_STOP_COUNTDOWN"
        const val ACTION_SESSION_EXPIRED = "com.grayzone.app.ACTION_SESSION_EXPIRED"
        const val ACTION_APP_CHANGED_WATCHDOG = "com.grayzone.app.APP_CHANGED_WATCHDOG"
        /** Window title / pane title the accessibility service uses to ignore Grayzone's overlays. */
        const val OVERLAY_WINDOW_TITLE = "GrayzoneOverlay"
        const val OVERLAY_PANE_TITLE = "Grayzone protection"
        const val ACTION_MIDNIGHT_RESET = "com.grayzone.app.MIDNIGHT_RESET"
        /** Re-read a11y/overlay permissions and refresh the ongoing notification (F-080). */
        const val ACTION_REFRESH_PROTECTION_STATUS = "com.grayzone.app.ACTION_REFRESH_PROTECTION_STATUS"
        private const val DEFAULT_WAIT = 5
        private const val AMBER_WARNING = 0xFFB45309.toInt()
        /** Fixed brand teal for notifications: the shade follows the system theme, not the in-app one. */
        private const val NOTIFICATION_ACCENT = 0xFF0F766E.toInt()
        /** Upper bound on how long a lock screen lingers after its app was evicted. */
        private const val LOCK_OVERLAY_MAX_VISIBLE_MS = 30_000L
        /** How often a running session re-confirms the user is still in the app. */
        private const val SESSION_HEARTBEAT_INTERVAL_MS = 20_000L
        /** How long the heartbeat waits for accessibility to answer with a live window list. */
        private const val WINDOW_SNAPSHOT_REPLY_TIMEOUT_MS = 750L
        private const val WINDOW_SNAPSHOT_POLL_MS = 50L
        /** Longer than a typical app-switch animation, so a lingering window has gone. */
        private const val LINGERING_RECHECK_DELAY_MS = 1_200L
        private const val LINGERING_RECHECK_ATTEMPTS = 4

        // Light / dark chrome mirrors ui.theme palettes (see isNightMode)
        private const val BG_LIGHT          = 0xFFF5F6F8.toInt()
        private const val SURFACE_LIGHT     = 0xFFFFFFFF.toInt()
        private const val ACCENT_LIGHT      = 0xFF0F766E.toInt()
        private const val ACCENT_DIM_LIGHT  = 0xFFE3F3F0.toInt()
        private const val TEXT_LIGHT        = 0xFF111827.toInt()
        private const val GREY_LIGHT        = 0xFF4B5563.toInt()
        private const val GREY_DIM_LIGHT    = 0xFF9CA3AF.toInt()
        private const val RED_LIGHT         = 0xFFC62828.toInt()
        private const val BORDER_LIGHT      = 0xFFE3E6EA.toInt()

        private const val BG_DARK           = 0xFF0E1113.toInt()
        private const val SURFACE_DARK      = 0xFF161A1D.toInt()
        private const val ACCENT_DARK       = 0xFF2DD4BF.toInt()
        private const val ACCENT_DIM_DARK   = 0xFF113330.toInt()
        private const val TEXT_DARK         = 0xFFF1F5F9.toInt()
        private const val GREY_DARK         = 0xFFA6B0BB.toInt()
        private const val GREY_DIM_DARK     = 0xFF6B7682.toInt()
        private const val RED_DARK          = 0xFFF87171.toInt()
        private const val BORDER_DARK       = 0xFF283036.toInt()

        private const val ON_ACCENT_LIGHT   = 0xFFFFFFFF.toInt()
        private val ON_ACCENT_DARK    = 0xFF032B27.toInt()

        private val sharedGson = Gson()
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countdownJobUI: Job? = null
    private var secondsRemaining = DEFAULT_WAIT

    // F-231: retained so onConfigurationChanged can dismiss+reshow with the same params.
    private var lastOverlayPackageName: String? = null
    private var lastOverlayAppName: String? = null
    private var lastOverlayMode: Int = 0
    private var lastOverlayLockedUntil: Long = 0L
    
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
    
    // Usage stats state — read/written across the transition coroutine (IO),
    // the main-thread countdown job, and lockout jobs, hence @Volatile.
    @Volatile private var lastForegroundPackage: String? = null
    /** User-app packages currently on screen, from the accessibility window list (F-096). */
    // Immutable snapshot swapped in one step, so readers on other threads never see it half-built.
    @Volatile private var visibleAppPackages: Set<String> = emptySet()
    /** Per-app foreground usage timing (each stint recorded exactly once). */
    private val usageClock = UsageClock()
    /**
     * Apps the user switched away from while their window was still listed on screen (switch
     * animation, or a real split-screen / PiP). Paused as soon as a window list no longer shows them.
     */
    private val pendingBackground: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var lastSnapshotMissLogElapsedMs = 0L
    private var cachedHomeLauncherPkg: String? = null
    private var homeLauncherCacheTime: Long = 0L
    private lateinit var scheduleManager: ScheduleManager
    
    private val transitionLock = Any()
    private var queuedForegroundPackage: String? = null
    private var transitionJob: Job? = null
    
    // Broadcast receiver for AccessibilityService events (replaces polling)
    private var appChangeReceiver: BroadcastReceiver? = null

    // Screen on/off receiver: the session must only accrue time while the app is actually in
    // use, so a screen-off (device not in use) pauses the countdown just like an app switch.
    private var screenReceiver: BroadcastReceiver? = null

    /** Package whose lock screen is showing; see [armLockOverlayStickiness]. */
    @Volatile private var lockOverlayPackage: String? = null
    private var lockAutoDismissJob: Job? = null

    /** elapsedRealtime of the last window list received from the accessibility service. */
    @Volatile private var visibleSnapshotElapsedMs: Long = 0L
    private var sessionHeartbeatJob: Job? = null
    /** Package the running heartbeat belongs to; another app's expiry must not cancel it. */
    @Volatile private var heartbeatPackage: String? = null
    /** Package the countdown notification is showing; another app's expiry must not clear it. */
    @Volatile private var countdownPackage: String? = null

    /** Serialises session transitions so the heartbeat cannot pause mid-app-switch. */
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()
    
    private val policyEngine = com.grayzone.app.policy.SessionPolicyEngine()

    /** Set once the service is stopping; late coroutines must not re-post ongoing notifications. */
    @Volatile private var isShuttingDown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification())
            refreshProtectionNotification()
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.OVERLAY,
                "startForeground failed — OverlayService cannot run as FGS",
                e
            )
            com.grayzone.app.data.ProtectionHealthRepository.updateOverlayStatus(false)
            com.grayzone.app.data.ProtectionHealthRepository.updateAccessibilityStatus(false)
            isShuttingDown = true
            stopSelf()
            return
        }
        // The accessibility watchdog, expiry alarms and sticky restarts all start this service
        // without knowing the Monitoring switch, so the switch is enforced here.
        if (!com.grayzone.app.isMonitoringEnabled(this)) {
            stopWhileMonitoringOff()
            return
        }
        broadcastMonitoringState(true)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scheduleManager = ScheduleManager(this)

        clearStaleSystemGrayscale()

        // F-006: prefs type drift must not prevent receiver registration / FGS survival.
        try {
            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
            val now = System.currentTimeMillis()

            // Re-arm session-expiry alarms for any in-flight session so the lock still fires (and the
            // countdown stops at 00:00) after a process restart or reboot — the old in-memory schedule
            // was lost on process death.
            monitoredApps.forEach { pkg ->
                val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
                if (activeUntil > now) {
                    SessionExpiryScheduler.arm(this, pkg, activeUntil)
                }
            }
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.OVERLAY,
                "Failed to re-arm session alarms from prefs; continuing service start",
                e
            )
        }

        // Register broadcast receivers. Must never throw out of onCreate: an uncaught crash kills
        // OverlayService (default process). Accessibility runs in :a11y and must not touch prefs/Room.
        registerAppChangeReceiver()
        registerScreenOffReceiver()

        com.grayzone.app.GrayzoneLogger.i(
            com.grayzone.app.LogComponent.OVERLAY, 
            "Monitoring service started"
        )

        MidnightResetScheduler.arm(this)
    }

    private fun registerAppChangeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == GrayzoneAccessibilityService.ACTION_ACTIVE_WINDOWS_CHANGED) {
                    val activePackages = intent.getStringArrayListExtra(
                        GrayzoneAccessibilityService.EXTRA_ACTIVE_PACKAGES
                    ) ?: return
                    applyWindowSnapshot(activePackages)
                    return
                }
                
                if (intent.action == GrayzoneAccessibilityService.ACTION_APP_CHANGED) {
                    val packageName = intent.getStringExtra(
                        GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME
                    )
                    
                    intent.getStringArrayListExtra(
                        GrayzoneAccessibilityService.EXTRA_ACTIVE_PACKAGES
                    )?.let { applyWindowSnapshot(it) }

                    if (packageName != null) {
                        handleForegroundAppChanged(packageName)
                    }
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(GrayzoneAccessibilityService.ACTION_APP_CHANGED)
                addAction(GrayzoneAccessibilityService.ACTION_ACTIVE_WINDOWS_CHANGED)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            appChangeReceiver = receiver
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.OVERLAY,
                "Failed to register app-change receiver",
                e
            )
        }
    }

    /**
     * Replaces the tracked window list and records when it was read (see [snapshotShows]). Any app
     * waiting in [pendingBackground] that this list no longer shows is paused now.
     */
    private fun applyWindowSnapshot(activePackages: List<String>) {
        visibleAppPackages = activePackages.toSet()
        visibleSnapshotElapsedMs = SystemClock.elapsedRealtime()
        if (activePackages.isEmpty()) return
        pendingBackground.filter { it !in activePackages && it != lastForegroundPackage }
            .forEach { pkg -> serviceScope.launch { pauseIfStillPending(pkg) } }
    }

    /** Pauses [pkg] if it is still waiting to be paused and hasn't come back to the front. */
    private suspend fun pauseIfStillPending(pkg: String) {
        sessionMutex.withLock {
            if (lastForegroundPackage == pkg || !pendingBackground.remove(pkg)) return@withLock
            if (heartbeatPackage == pkg) stopSessionHeartbeat()
            backgroundApp(pkg, getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE), System.currentTimeMillis())
        }
    }

    private fun registerScreenOffReceiver() {
        // Pause the session when the screen turns off: the app may still be the top activity,
        // but the user isn't using it, so wall-clock time must not keep draining the session.
        // SCREEN_ON also re-checks protection permissions: after a11y revoke, APP_CHANGED
        // broadcasts stop, so this is the cheap path that keeps the notification honest (F-080).
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON -> refreshProtectionNotification()
                    Intent.ACTION_USER_PRESENT -> handleScreenOnResume()
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiver = receiver
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.OVERLAY,
                "Failed to register screen on/off receiver",
                e
            )
        }
    }

    private fun safeUnregister(receiver: BroadcastReceiver?) {
        if (receiver == null) return
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "unregisterReceiver failed", e)
        }
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
                try {
                    performMidnightReset()
                    // Silent a11y revoke may leave the notification claiming "active" all day;
                    // correct it at the same tick that already runs (F-080).
                    refreshProtectionNotification()
                } catch (e: Exception) {
                    GrayzoneLogger.e(LogComponent.OVERLAY, "Failed to perform midnight reset", e)
                }
            }
        }
    }

    private suspend fun performMidnightReset() {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        // RuntimeSessionReset keeps sessions and lockouts that are still running and only clears
        // expired ones, so there is no session UI to tear down here — doing so would cut a live
        // session short. MidnightResetReceiver (or an app switch) usually resets first; either way
        // the day that just ended must have its streak settled.
        if (com.grayzone.app.data.RuntimeSessionReset.resetIfNeeded(prefs)) {
            GrayzoneLogger.i(LogComponent.OVERLAY, "Midnight reset: expired session state cleared for the new day")
        }
        settleCompletedDayStreak()
    }

    /**
     * The day that just ended is now complete, so settle its streak outcome
     * here rather than waiting for the user to open the Home screen.
     */
    private suspend fun settleCompletedDayStreak() {
        try {
            com.grayzone.app.data.StreakEvaluator.run(this)
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.SESSION, "Midnight streak catch-up failed", e)
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

    // Holds [sessionMutex] so the heartbeat cannot bank a session in the middle of an app switch.
    private suspend fun processForegroundAppChanged(currentPkg: String): Unit = sessionMutex.withLock {
        try {
            if (currentPkg == lastForegroundPackage) return@withLock

            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            // New-day safety net: cheap date-key comparison on every transition
            // so stale locks never survive midnight even if the timer job died.
            com.grayzone.app.data.RuntimeSessionReset.resetIfNeeded(prefs)

            val isOther = currentPkg == ForegroundResolver.OTHER
            // Screen off or still on the lock screen: accessibility keeps reporting the last app,
            // but the user isn't using it. Resuming here would drain the session in the dark;
            // the unlock re-send (handleScreenOnResume) brings the real foreground app.
            if (!isOther && !isDeviceInUse()) return@withLock

            val oldPackage = lastForegroundPackage
            // Coming back before a deferred pause ran: it simply carries on.
            pendingBackground.remove(currentPkg)

            com.grayzone.app.GrayzoneLogger.d(
                com.grayzone.app.LogComponent.OVERLAY,
                "Foreground app changed",
                mapOf("from" to (oldPackage ?: "null"), "to" to currentPkg)
            )

            // A phone call or system panel taking the screen (no launcher entry), or Grayzone's own
            // screens: the user left the monitored app, but there is nothing to evaluate for the
            // new foreground. Any overlay goes too, so it can never sit on top of a call (F-222).
            if (isOther || currentPkg == packageName) {
                if (oldPackage != null && oldPackage != ForegroundResolver.OTHER && oldPackage != packageName) {
                    if (heartbeatPackage == oldPackage) stopSessionHeartbeat()
                    backgroundApp(oldPackage, prefs, now)
                }
                // Apps still waiting for a deferred pause can't be seen behind a call; pause them now.
                pendingBackground.toList().forEach { pending ->
                    if (heartbeatPackage == pending) stopSessionHeartbeat()
                    backgroundApp(pending, prefs, now)
                }
                lastForegroundPackage = currentPkg
                if (isOther) withContext(Dispatchers.Main) { dismissOverlay() }
                return@withLock
            }

            // 1. Process Background Event for old package — skip when window tracking says the
            // previous package is still visible (split-screen / multi-window, F-230).
            if (oldPackage != null && oldPackage != ForegroundResolver.OTHER && oldPackage != packageName) {
                if (!visibleAppPackages.contains(oldPackage)) {
                    if (heartbeatPackage == oldPackage) stopSessionHeartbeat()
                    backgroundApp(oldPackage, prefs, now)
                } else {
                    com.grayzone.app.GrayzoneLogger.d(
                        com.grayzone.app.LogComponent.OVERLAY,
                        "Old app still listed on screen — pausing it once it is gone",
                        mapOf("package" to oldPackage)
                    )
                    // Its window often lingers through the switch animation. Real split-screen /
                    // PiP keeps it listed; otherwise the next window list (or the recheck) pauses
                    // it, so its session never drains in the background.
                    pendingBackground.add(oldPackage)
                    scheduleLingeringRecheck(oldPackage)
                }
            }

            lastForegroundPackage = currentPkg
            refreshProtectionNotification()

            // 2. Process Foreground Event for new package
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val newState = buildSessionState(currentPkg, prefs)
            if (newState.isMonitored) {
                visibleAppPackages = visibleAppPackages + currentPkg
            }
            val fgCommands = policyEngine.evaluate(
                com.grayzone.app.policy.AppEvent.AppForegrounded(currentPkg),
                newState,
                now,
                getAppName(currentPkg),
                nowElapsed
            )
            
            // The launcher only came forward because Grayzone evicted the blocked app, so its
            // "nothing to see here" dismissal must not take the lock screen down with it.
            val keepLockOverlay = lockOverlayPackage != null && isHomeLauncher(currentPkg)

            var didShowWaitScreen = false
            // A paused session resumed via UpdateState is also an active session,
            // even though the pre-command state snapshot says otherwise.
            var hasActiveSession = newState.hasActiveSession(now, nowElapsed)
            var activeUntil = newState.activeUntil
            fgCommands.forEach { cmd ->
                if (cmd is com.grayzone.app.policy.SessionCommand.DismissOverlay && keepLockOverlay) {
                    return@forEach
                }
                if (cmd is com.grayzone.app.policy.SessionCommand.ShowWaitScreen) didShowWaitScreen = true
                if (cmd is com.grayzone.app.policy.SessionCommand.UpdateState &&
                    (cmd.newActiveUntil ?: 0L) > now
                ) {
                    hasActiveSession = true
                    activeUntil = cmd.newActiveUntil!!
                }
                executeCommand(cmd, prefs)
            }
            
            // If wait screen wasn't shown (meaning session active or dismissed), we start timing.
            // During a break, usage is timed too so daily budgets stay accurate.
            if (!didShowWaitScreen && (hasActiveSession || newState.isOnBreak) && newState.isMonitored) {
                usageClock.start(currentPkg, now)
            }

            // Re-entering an app that still has a live session re-posts its countdown: the engine
            // just dismisses the overlay in that case, so nothing else would bring the timer back.
            // The heartbeat starts with it, so the clock can only run while the app is on screen.
            if (hasActiveSession && newState.isMonitored && activeUntil > now) {
                withContext(Dispatchers.Main) { startCountdownNotification(currentPkg, activeUntil) }
                startSessionHeartbeat(currentPkg)
            }

        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.OVERLAY,
                "Failed to process foreground app change",
                e
            )
        }
    }
    
    /**
     * Runs the "app left the foreground" policy for [pkg]: records the foreground usage and pauses
     * the session (saving remaining time, clearing the active/lock deadlines and the expiry alarm)
     * so the countdown only accrues while the app is genuinely in use. Shared by app-switch
     * transitions and screen-off events.
     */
    private suspend fun backgroundApp(pkg: String, prefs: android.content.SharedPreferences, now: Long) {
        visibleAppPackages = visibleAppPackages - pkg
        pendingBackground.remove(pkg)
        val oldState = buildSessionState(pkg, prefs)
        // 0 when this app wasn't being timed (already paused): no usage is recorded twice.
        val bgDuration = usageClock.stop(pkg, now)
        val bgCommands = policyEngine.evaluate(
            com.grayzone.app.policy.AppEvent.AppBackgrounded(pkg, bgDuration),
            oldState,
            now,
            getAppName(pkg),
            android.os.SystemClock.elapsedRealtime()
        )
        bgCommands.forEach { cmd ->
            // Only this app's own timer may be cleared; another app may own it by now.
            if (cmd is com.grayzone.app.policy.SessionCommand.CancelCountdownNotification) {
                withContext(Dispatchers.Main) { cancelCountdownNotificationFor(pkg) }
            } else {
                executeCommand(cmd, prefs)
            }
        }
    }

    /**
     * Backstop for [pendingBackground] when no new window list arrives on its own: asks for fresh
     * lists with growing delays and pauses [pkg] once one no longer shows it. Stops when the app is
     * back in front or was paused another way; genuine split-screen / PiP keeps it running.
     */
    private fun scheduleLingeringRecheck(pkg: String) {
        serviceScope.launch {
            var waitMs = LINGERING_RECHECK_DELAY_MS
            repeat(LINGERING_RECHECK_ATTEMPTS) {
                delay(waitMs)
                if (pkg !in pendingBackground || lastForegroundPackage == pkg) return@launch
                if (snapshotShows(pkg) == false) {
                    pauseIfStillPending(pkg)
                    return@launch
                }
                waitMs *= 2
            }
        }
    }

    /** Screen on and unlocked. */
    private fun isDeviceInUse(): Boolean {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power != null && !power.isInteractive) return false
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        return keyguard == null || !keyguard.isKeyguardLocked
    }

    /** True when [packageName] is still in front of the user (set, or last-fg). */
    private fun isPackageStillVisible(packageName: String): Boolean =
        lastForegroundPackage == packageName ||
            // A call or system panel covering the screen means it's not in use, even if listed.
            (packageName in visibleAppPackages && lastForegroundPackage != ForegroundResolver.OTHER)

    /**
     * Keeps a running session honest: session time may only accrue while the user is genuinely
     * in [pkg].
     *
     * Pausing is otherwise driven entirely by accessibility window-change events. OEM process
     * kills, dropped broadcasts and gesture navigation that never reports a new window all
     * swallow that event, and the wall-clock deadline then keeps draining a session the user
     * isn't using — they put the phone down and come back to a lockout they never spent. This is
     * the backstop: no positive confirmation that the app is on screen, no session time.
     */
    private fun startSessionHeartbeat(pkg: String) {
        sessionHeartbeatJob?.cancel()
        heartbeatPackage = pkg
        sessionHeartbeatJob = serviceScope.launch {
            while (isActive) {
                val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val remainingMs = buildSessionState(pkg, prefs)
                    .activeRemainingMs(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                delay(remainingMs.coerceIn(0L, SESSION_HEARTBEAT_INTERVAL_MS))
                val state = buildSessionState(pkg, prefs)
                if (!state.hasActiveSession(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
                    // Without the exact-alarm grant the expiry alarm can land minutes late (or be
                    // refused an FGS start), leaving the app usable and the timer running negative.
                    // Separate coroutine: the lockout cancels this heartbeat job.
                    serviceScope.launch { handleSessionExpired(pkg) }
                    return@launch
                }
                // Pause only on positive evidence the app is gone. No answer from accessibility
                // (slow or restarting process) is not evidence: pausing then hid the timer while
                // the user was still in the app, and nothing resumed it until the next app switch.
                if (snapshotShows(pkg) != false) continue

                val myJob = coroutineContext[Job]
                // The pause must run to completion once started (a half-done pause loses the
                // stint's usage), and must not run at all if this heartbeat was superseded while
                // waiting for the lock (e.g. the user came straight back and a new one started).
                withContext(NonCancellable) {
                    sessionMutex.withLock {
                        if (sessionHeartbeatJob !== myJob) return@withLock
                        GrayzoneLogger.i(LogComponent.SESSION, "Heartbeat: $pkg is no longer on screen; pausing its session")
                        backgroundApp(pkg, prefs, System.currentTimeMillis())
                        if (lastForegroundPackage == pkg) lastForegroundPackage = null
                        sessionHeartbeatJob = null
                        heartbeatPackage = null
                    }
                }
                return@launch
            }
        }
    }

    private fun stopSessionHeartbeat() {
        sessionHeartbeatJob?.cancel()
        sessionHeartbeatJob = null
        heartbeatPackage = null
    }

    /**
     * Fresh answer to "is [pkg] on screen?": true / false from a newly read window list, or null
     * when it can't be known (no reply, or an empty list because windows couldn't be read).
     * The cached list isn't trusted: an outgoing app's window lingers through the switch
     * animation. Screen off counts as false.
     */
    private suspend fun snapshotShows(pkg: String): Boolean? {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power != null && !power.isInteractive) return false
        val pingedAtElapsed = SystemClock.elapsedRealtime()
        try {
            sendBroadcast(
                Intent(GrayzoneAccessibilityService.ACTION_REQUEST_WINDOW_SNAPSHOT)
                    .setPackage(getPackageName())
            )
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "Window snapshot ping failed", e)
            return null
        }
        var waited = 0L
        while (waited < WINDOW_SNAPSHOT_REPLY_TIMEOUT_MS && visibleSnapshotElapsedMs < pingedAtElapsed) {
            delay(WINDOW_SNAPSHOT_POLL_MS)
            waited += WINDOW_SNAPSHOT_POLL_MS
        }
        val visible = visibleAppPackages
        if (visibleSnapshotElapsedMs < pingedAtElapsed || visible.isEmpty()) {
            // Repeated misses mean the accessibility process is dead or stuck; say so, but rarely.
            if (pingedAtElapsed - lastSnapshotMissLogElapsedMs >= 60_000L) {
                lastSnapshotMissLogElapsedMs = pingedAtElapsed
                GrayzoneLogger.w(LogComponent.ACCESSIBILITY, "Accessibility did not answer a window snapshot request")
            }
            return null
        }
        return pkg in visible
    }

    /**
     * Screen turned off: the device is not in use, so pause the current app (and any app still
     * waiting to be paused) exactly like an app switch. [lastForegroundPackage] is cleared so the
     * app is re-evaluated when the screen comes back on.
     */
    private fun handleScreenOff() {
        serviceScope.launch {
            try {
                val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                // Under the lock, so a switch being processed right now can't start a heartbeat
                // (or leave an app resumed) after this pause.
                val paused = withContext(NonCancellable) {
                    sessionMutex.withLock {
                        val now = System.currentTimeMillis()
                        val targets = buildList {
                            lastForegroundPackage?.takeIf { it != ForegroundResolver.OTHER && it != packageName }?.let { add(it) }
                            addAll(pendingBackground)
                        }.distinct()
                        stopSessionHeartbeat()
                        targets.forEach { backgroundApp(it, prefs, now) }
                        lastForegroundPackage = null
                        targets
                    }
                }
                if (paused.isNotEmpty()) {
                    GrayzoneLogger.i(LogComponent.SESSION, "Screen off; paused ${paused.joinToString()}")
                }
            } catch (e: Exception) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.OVERLAY,
                    "Failed to pause session on screen off",
                    e
                )
            }
        }
    }

    /**
     * F-221: after unlock, ask accessibility which app is actually in front now. Re-entering the
     * app paused at screen-off would be wrong when the user unlocked into a different app (e.g. by
     * tapping a notification), and a stale re-entry could skip that app's wait screen.
     */
    private fun handleScreenOnResume() {
        refreshProtectionNotification()
        // Twice: the keyguard can still be animating away at USER_PRESENT, leaving no foreground
        // app to report on the first ask.
        serviceScope.launch {
            repeat(2) { attempt ->
                if (attempt > 0) delay(600L)
                try {
                    sendBroadcast(
                        Intent(GrayzoneAccessibilityService.ACTION_RESEND_FOREGROUND).setPackage(getPackageName())
                    )
                } catch (e: Exception) {
                    GrayzoneLogger.w(LogComponent.OVERLAY, "Could not ask accessibility for the foreground app after unlock", e)
                }
            }
        }
    }
    private fun buildSessionState(pkg: String, prefs: android.content.SharedPreferences): com.grayzone.app.policy.SessionState {
        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
        val isMonitored = monitoredApps.contains(pkg)
        val isGrayzoneEnabled = prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, false)
        val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
        val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
        val activeStartedElapsedMs = prefs.getLong(PrefsKeys.ACTIVE_STARTED_ELAPSED + pkg, 0L)
        val activeDurationMs = prefs.getLong(PrefsKeys.ACTIVE_DURATION_MS + pkg, 0L)
        val lockedStartedElapsedMs = prefs.getLong(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg, 0L)
        val lockedDurationMs = prefs.getLong(PrefsKeys.LOCKED_DURATION_MS + pkg, 0L)
        // Normalize so stale or oversized paused values (>24h) are treated as expired,
        // matching isAnyAppLocked() and the UI screens.
        val remainingMillis = getNormalizedRemainingMillis(prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L))
        
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, false)
        val defaultSessionMins = com.grayzone.app.data.PerAppLimits.sessionMinutes(
            hasCustom = hasCustom,
            perApp = prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, 10),
            global = prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
        )
        val defaultLockoutMins = com.grayzone.app.data.PerAppLimits.lockoutMinutes(
            hasCustom = hasCustom,
            perApp = prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, 60),
            global = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
        )
        val waitSeconds = com.grayzone.app.data.PerAppLimits.waitSeconds(
            hasCustom = hasCustom,
            perApp = prefs.getInt(PrefsKeys.PER_APP_WAIT_SECONDS + pkg, DEFAULT_WAIT),
            global = prefs.getInt(PrefsKeys.WAIT_SECONDS, DEFAULT_WAIT)
        )

        // Calculate budget
        val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
        val dateKey = DateUtils.getCurrentDateKey()
        val budgetRemainingMs = com.grayzone.app.data.DailyBudget.remainingMs(
            budgetMins = budgetMins,
            dateKey = dateKey,
            lastResetDateKey = prefs.getString(PrefsKeys.DAILY_RESET_DATE + pkg, ""),
            usedMs = prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L)
        )

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
            waitSeconds = waitSeconds,
            isScheduleLocked = scheduleManager.isCurrentlyScheduled(),
            isOnBreak = scheduleManager.isBreakActive(),
            activeStartedElapsedMs = activeStartedElapsedMs,
            activeDurationMs = activeDurationMs,
            lockedStartedElapsedMs = lockedStartedElapsedMs,
            lockedDurationMs = lockedDurationMs
        )
    }
    
    private suspend fun executeCommand(cmd: com.grayzone.app.policy.SessionCommand, prefs: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        when (cmd) {
            is com.grayzone.app.policy.SessionCommand.DismissOverlay -> {
                // F-005: budget/schedule locks have no countdown — must dismiss on app leave.
                withContext(Dispatchers.Main) { dismissOverlay() }
            }
            is com.grayzone.app.policy.SessionCommand.ShowWaitScreen -> {
                GrayzoneLogger.i(LogComponent.SESSION, "Wait screen shown for ${cmd.packageName} (${cmd.waitSeconds}s)")
                serviceScope.launch(Dispatchers.Main) {
                    // F-112: honour the engine's validated waitSeconds instead of re-reading prefs.
                    showOverlay(
                        cmd.packageName,
                        cmd.appName,
                        com.grayzone.app.OverlayMode.FRICTION,
                        0L,
                        waitSeconds = cmd.waitSeconds
                    )
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
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                val nowWall = System.currentTimeMillis()
                cmd.newActiveUntil?.let { until ->
                    val duration = (until - nowWall).coerceAtLeast(0L)
                    editor.putLong(PrefsKeys.ACTIVE_UNTIL + cmd.packageName, until)
                        .putLong(PrefsKeys.ACTIVE_STARTED_ELAPSED + cmd.packageName, nowElapsed)
                        .putLong(PrefsKeys.ACTIVE_DURATION_MS + cmd.packageName, duration)
                }
                cmd.newLockedUntil?.let { until ->
                    val duration = (until - nowWall).coerceAtLeast(0L)
                    editor.putLong(PrefsKeys.LOCKED_UNTIL + cmd.packageName, until)
                        .putLong(PrefsKeys.LOCKED_STARTED_ELAPSED + cmd.packageName, nowElapsed)
                        .putLong(PrefsKeys.LOCKED_DURATION_MS + cmd.packageName, duration)
                        // A lockout retires whatever was banked; nothing survives the penalty box.
                        .remove(PrefsKeys.REMAINING_MILLIS + cmd.packageName)
                }
                cmd.newSessionRemainingMs?.let { editor.putLong(PrefsKeys.REMAINING_MILLIS + cmd.packageName, it) }
                if (cmd.clearActiveSession) {
                    editor.remove(PrefsKeys.ACTIVE_UNTIL + cmd.packageName)
                        .remove(PrefsKeys.ACTIVE_STARTED_ELAPSED + cmd.packageName)
                        .remove(PrefsKeys.ACTIVE_DURATION_MS + cmd.packageName)
                    // Session paused/cleared — a pending expiry alarm must not fire.
                    SessionExpiryScheduler.cancel(this@OverlayService, cmd.packageName)
                }
                
                if (cmd.newActiveUntil != null) {
                    val activeUntil = cmd.newActiveUntil
                    startCountdownNotification(cmd.packageName, activeUntil)
                    SessionExpiryScheduler.arm(this@OverlayService, cmd.packageName, activeUntil)
                }
                if (!editor.commit()) {
                    GrayzoneLogger.e(LogComponent.PREFS, "Failed to save session state for ${cmd.packageName}")
                }
                when {
                    cmd.newActiveUntil != null -> GrayzoneLogger.i(
                        LogComponent.SESSION,
                        "Session resumed for ${cmd.packageName} (${(cmd.newActiveUntil - now) / 1000}s left)"
                    )
                    cmd.newLockedUntil != null -> GrayzoneLogger.i(
                        LogComponent.SESSION,
                        "Lockout started for ${cmd.packageName} (session was already used up)"
                    )
                    (cmd.newSessionRemainingMs ?: 0L) > 0L && cmd.clearActiveSession -> GrayzoneLogger.i(
                        LogComponent.SESSION,
                        "Session paused for ${cmd.packageName} (${cmd.newSessionRemainingMs!! / 1000}s left)"
                    )
                }
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

    private suspend fun logUsageAndCheckBudget(
        pkg: String,
        startTime: Long,
        wasBlocked: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val durationMs = now - startTime
        if (durationMs <= 0) return@withContext

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
                val newUsedMs = com.grayzone.app.data.DailyBudget.accumulateUsedMs(
                    dateKey = dateKey,
                    lastResetDateKey = lastReset,
                    previousUsedMs = prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L),
                    durationMs = durationMs
                )

                prefs.edit()
                    .putLong(PrefsKeys.DAILY_USED_MILLIS + pkg, newUsedMs)
                    .putString(PrefsKeys.DAILY_RESET_DATE + pkg, dateKey)
                    .commit()

                // Don't slam the budget lock down mid-break; it will be enforced
                // by the policy engine on the next foreground once the break ends.
                // Usage is only logged as [pkg] leaves, while lastForegroundPackage still names it,
                // so only the window list can say whether it is genuinely still on screen.
                val stillVisible = pkg in visibleAppPackages && lastForegroundPackage != ForegroundResolver.OTHER
                if (com.grayzone.app.data.DailyBudget.shouldShowBudgetLock(
                        usedMs = newUsedMs,
                        budgetMins = budgetMins,
                        isStillForeground = stillVisible,
                        isOnBreak = scheduleManager.isBreakActive()
                    )
                ) {
                    withContext(Dispatchers.Main) {
                        if (lastForegroundPackage != ForegroundResolver.OTHER) {
                            showOverlay(pkg, appName, OverlayMode.BUDGET_LOCK, 0L)
                        }
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
    /**
     * Starts the penalty box for [pkg] at [now] and retires the spent session, so the lockout
     * window is exactly the configured `lockoutMins` long. No-op when a lockout is already
     * running, so repeated expiry deliveries cannot extend it.
     */
    private fun beginLockout(
        pkg: String,
        prefs: android.content.SharedPreferences,
        now: Long,
        nowElapsed: Long
    ): Long {
        val existingUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
        val alreadyLocked = com.grayzone.app.policy.MonotonicDeadline.isPending(
            existingUntil,
            prefs.getLong(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg, 0L),
            prefs.getLong(PrefsKeys.LOCKED_DURATION_MS + pkg, 0L),
            now,
            nowElapsed
        )
        if (alreadyLocked) return existingUntil
        if (heartbeatPackage == pkg) stopSessionHeartbeat()

        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, false)
        val lockoutMins = com.grayzone.app.data.PerAppLimits.lockoutMinutes(
            hasCustom = hasCustom,
            perApp = prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, 60),
            global = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
        ).coerceIn(15, 24 * 60)
        val lockoutMs = lockoutMins * 60 * 1000L
        val lockedUntil = now + lockoutMs

        prefs.edit()
            .putLong(PrefsKeys.LOCKED_UNTIL + pkg, lockedUntil)
            .putLong(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg, nowElapsed)
            .putLong(PrefsKeys.LOCKED_DURATION_MS + pkg, lockoutMs)
            .remove(PrefsKeys.ACTIVE_UNTIL + pkg)
            .remove(PrefsKeys.ACTIVE_STARTED_ELAPSED + pkg)
            .remove(PrefsKeys.ACTIVE_DURATION_MS + pkg)
            .remove(PrefsKeys.REMAINING_MILLIS + pkg)
            .commit()
        SessionExpiryScheduler.cancel(this, pkg)

        GrayzoneLogger.i(LogComponent.SESSION, "Session used up; lockout started for $pkg ($lockoutMins min)")
        return lockedUntil
    }

    /**
     * Called when the Doze-safe expiry alarm fires (via [SessionExpiryReceiver]). Clamps the
     * countdown notification so it never shows a negative value, and shows the lock when the
     * monitored app is still foreground within its lockout window. Reliable across
     * screen-off/Doze/process death, unlike the old in-memory coroutine delay.
     */
    // Holds [sessionMutex] so a lockout cannot interleave with the app-switch pause, and the
    // alarm and the heartbeat cannot both begin the same lockout.
    private suspend fun handleSessionExpired(packageName: String): Unit = sessionMutex.withLock {
        try {
            val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + packageName, 0L)
            val activeStartedElapsedMs = prefs.getLong(PrefsKeys.ACTIVE_STARTED_ELAPSED + packageName, 0L)
            val activeDurationMs = prefs.getLong(PrefsKeys.ACTIVE_DURATION_MS + packageName, 0L)

            // The session has genuinely run out, so the penalty box is now owed. Starting it here
            // (rather than at grant time) is what makes the lock last exactly `lockoutMins` and
            // keeps a merely-paused session out of the lockout gate entirely.
            val sessionEnded = activeUntil > 0L && !com.grayzone.app.policy.MonotonicDeadline.isPending(
                activeUntil, activeStartedElapsedMs, activeDurationMs, now, nowElapsed
            )
            if (sessionEnded) beginLockout(packageName, prefs, now, nowElapsed)

            val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + packageName, 0L)
            val lockedStartedElapsedMs = prefs.getLong(PrefsKeys.LOCKED_STARTED_ELAPSED + packageName, 0L)
            val lockedDurationMs = prefs.getLong(PrefsKeys.LOCKED_DURATION_MS + packageName, 0L)

            // A service the alarm just restarted has no foreground history, so ask accessibility
            // rather than letting the user keep using an app whose session has run out.
            // Never put a lock over a call or system panel that is in front (F-222).
            val stillVisible = lastForegroundPackage != ForegroundResolver.OTHER &&
                (isPackageStillVisible(packageName) || (sessionEnded && snapshotShows(packageName) == true))
            val outcome = SessionExpiryDecision.decide(
                now = now,
                activeUntil = activeUntil,
                lockedUntil = lockedUntil,
                isForegroundPackage = stillVisible,
                isGrayzoneEnabled = prefs.getBoolean(PrefsKeys.GRAYZONE_ENABLED, false),
                isOnBreak = scheduleManager.isBreakActive(),
                nowElapsed = nowElapsed,
                activeStartedElapsedMs = activeStartedElapsedMs,
                activeDurationMs = activeDurationMs,
                lockedStartedElapsedMs = lockedStartedElapsedMs,
                lockedDurationMs = lockedDurationMs
            )

            when (outcome) {
                com.grayzone.app.policy.SessionExpiryOutcome.NoOp -> Unit
                com.grayzone.app.policy.SessionExpiryOutcome.ClampCountdown ->
                    serviceScope.launch(Dispatchers.Main) { cancelCountdownNotificationFor(packageName) }
                com.grayzone.app.policy.SessionExpiryOutcome.ShowLock ->
                    serviceScope.launch {
                        val appName = getAppName(packageName)
                        withContext(Dispatchers.Main) {
                            // A call may have taken the screen since the decision above.
                            if (lastForegroundPackage != ForegroundResolver.OTHER) {
                                showOverlay(packageName, appName, OverlayMode.LOCK, lockedUntil)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.OVERLAY,
                "handleSessionExpired failed (prefs type drift?); clamping countdown",
                e
            )
            serviceScope.launch(Dispatchers.Main) { cancelCountdownNotificationFor(packageName) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::scheduleManager.isInitialized || !com.grayzone.app.isMonitoringEnabled(this)) {
            stopWhileMonitoringOff()
            return START_NOT_STICKY
        }
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
        } else if (intent?.action == ACTION_SESSION_EXPIRED) {
            val pkg = intent.getStringExtra("package_name") ?: ""
            // Off the main thread: expiry starts the lockout, which writes prefs synchronously.
            if (pkg.isNotEmpty()) serviceScope.launch { handleSessionExpired(pkg) }
        } else if (intent?.action == ACTION_APP_CHANGED_WATCHDOG) {
            val pkg = intent.getStringExtra(GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME) ?: ""
            if (pkg.isNotEmpty()) handleForegroundAppChanged(pkg)
        } else if (intent?.action == ACTION_REFRESH_PROTECTION_STATUS) {
            refreshProtectionNotification()
        } else if (intent?.action == ACTION_MIDNIGHT_RESET) {
            serviceScope.launch {
                try {
                    performMidnightReset()
                    refreshProtectionNotification()
                } catch (e: Exception) {
                    GrayzoneLogger.e(LogComponent.OVERLAY, "Failed to perform midnight reset from broadcast", e)
                }
            }
            MidnightResetScheduler.arm(this)
        }
        return START_STICKY
    }

    /** Takes down every overlay and notification this service owns, then stops it. */
    private fun stopWhileMonitoringOff() {
        // onCreate and onStartCommand both land here for one start; log the decision once.
        if (!isShuttingDown) {
            GrayzoneLogger.i(LogComponent.OVERLAY, "Monitoring is off; stopping the monitoring service")
        }
        isShuttingDown = true
        dismissOverlay()
        cancelCountdownNotification()
        // No explicit stopForeground: a start arriving between it and stopSelf would trip the
        // foreground-service timeout. Destroying the service removes its notification anyway.
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isShuttingDown || !com.grayzone.app.isMonitoringEnabled(this)) return
        
        com.grayzone.app.GrayzoneLogger.w(
            com.grayzone.app.LogComponent.OVERLAY,
            "Service swiped away from Recents, scheduling restart..."
        )
        
        // F-004: foreground-service PI + wakeup alarm so swipe-away restart works on API 26+.
        try {
            val restartIntent = Intent(applicationContext, OverlayService::class.java).apply {
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getForegroundService(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 2000,
                pendingIntent
            )
        } catch (e: Exception) {
            GrayzoneLogger.e(LogComponent.OVERLAY, "Failed to schedule OverlayService restart", e)
        }
    }

    private fun startCountdownNotification(packageName: String, activeUntil: Long) {
        if (isShuttingDown) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appName = getAppName(packageName)

        // F-003/F-113: never feed Chronometer a negative remaining (Doze-deferred rebuilds).
        val remainingMs = SessionExpiryDecision.clampRemainingMs(System.currentTimeMillis(), activeUntil)
        if (remainingMs <= 0) {
            cancelCountdownNotification()
            return
        }

        // Custom body view with a real Chronometer widget pinned to the right.
        // The framework's header chronometer (setUsesChronometer) is dropped by
        // some OEM skins (e.g. OriginOS) on the collapsed/lock-screen view, so a
        // self-rendered Chronometer in a DecoratedCustomView is the reliable way
        // to keep the count-down visible on the right in every state.
        val baseElapsed = SystemClock.elapsedRealtime() + remainingMs
        val body = RemoteViews(applicationContext.packageName, R.layout.notification_countdown).apply {
            setTextViewText(R.id.notif_title, appName)
            setChronometerCountDown(R.id.notif_timer, true)
            setChronometer(R.id.notif_timer, baseElapsed, null, true)
        }

        nm.notify(COUNTDOWN_NOTIF_ID,
            NotificationCompat.Builder(this@OverlayService, COUNTDOWN_CHANNEL_ID)
                // Status-bar glyph only; main shade icon is the adaptive launcher icon.
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appName)
                .setContentText("Session active")
                .setShowWhen(false)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(body)
                // F-177: do not mirror the same custom view as big content — it overflows at large font.
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(NOTIFICATION_ACCENT)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
        )
        countdownPackage = packageName
    }

    /** Clears the countdown only if it belongs to [packageName]. */
    private fun cancelCountdownNotificationFor(packageName: String) {
        if (countdownPackage == null || countdownPackage == packageName) cancelCountdownNotification()
    }
    
    /**
     * Takes the timer down. The protection notification is a separate, independently owned slot,
     * so it stays exactly as it is — there is nothing to restore here.
     */
    private fun cancelCountdownNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(COUNTDOWN_NOTIF_ID)
        countdownPackage = null
    }

    private fun postProtectionNotification() {
        // After stopForeground this would be a plain ongoing notification nothing ever cancels.
        if (isShuttingDown) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        GrayzoneLogger.i(LogComponent.OVERLAY, "Monitoring service stopped")
        isShuttingDown = true
        super.onDestroy()
        com.grayzone.app.data.ProtectionHealthRepository.updateOverlayStatus(false)
        safeUnregister(appChangeReceiver)
        appChangeReceiver = null
        safeUnregister(screenReceiver)
        screenReceiver = null
        // Only the FGS notification is torn down with the service; the timer owns its own slot and
        // would otherwise be stranded in the shade, counting down against a session that is gone.
        cancelCountdownNotification()
        serviceScope.cancel()
        dismissOverlay()
        if (!com.grayzone.app.isMonitoringEnabled(this)) broadcastMonitoringState(false)
    }

    /** Lets the accessibility watchdog stop (or resume) restarting this service. */
    private fun broadcastMonitoringState(enabled: Boolean) {
        try {
            sendBroadcast(
                Intent(GrayzoneAccessibilityService.ACTION_MONITORING_STATE)
                    .putExtra(GrayzoneAccessibilityService.EXTRA_MONITORING_ENABLED, enabled)
                    .setPackage(getPackageName())
            )
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "Monitoring state broadcast failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay UI ──────────────────────────────────────────────────────────

    /**
     * Pushes a blocked app out of the foreground and stops it running behind the lock screen.
     *
     * The overlay alone does not pause the app underneath, so video/audio keeps playing. Sending
     * the user home pauses the activity; killing the background process afterwards stops anything
     * that kept playing from a background service. HOME is routed through the accessibility
     * service because a background `startActivity` is unreliable from API 29 on, with the direct
     * intent kept as a fallback for when accessibility is not connected.
     */
    private fun evictLockedApp(packageName: String) {
        sendBroadcast(
            Intent(GrayzoneAccessibilityService.ACTION_PERFORM_HOME)
                .setPackage(getPackageName())
        )

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(homeIntent)
        } catch (e: Exception) {
            GrayzoneLogger.w(
                LogComponent.OVERLAY,
                "Failed to launch home screen for locked app",
                e
            )
        }

        if (packageName == getPackageName() || isHomeLauncher(packageName)) return
        serviceScope.launch {
            // Let the app finish pausing first: killBackgroundProcesses is a no-op while the
            // target still counts as foreground.
            delay(600)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(packageName)
            } catch (e: Exception) {
                GrayzoneLogger.w(
                    LogComponent.OVERLAY,
                    "Failed to stop background processes for $packageName",
                    e
                )
            }
        }
    }

    /** Best-effort: clear stuck system monochrome from older builds. No-op without grant. */
    private fun clearStaleSystemGrayscale() {
        try {
            android.provider.Settings.Secure.putString(
                contentResolver,
                "accessibility_display_daltonizer_enabled",
                "0"
            )
        } catch (_: SecurityException) {
            // No grant — nothing to clear.
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showOverlay(
        packageName: String,
        appName: String,
        mode: Int,
        lockedUntil: Long,
        waitSeconds: Int? = null
    ) {
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
                
                // F-081/F-082: deep-link Grayzone's overlay settings. Background startActivity is
                // often blocked on API 31+, so always post a tappable recovery notification too.
                val settingsIntent = com.grayzone.app.settingsIntentFor(
                    this,
                    com.grayzone.app.SetupRequirement.OVERLAY
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                try {
                    startActivity(settingsIntent)
                } catch (e: Exception) {
                    com.grayzone.app.GrayzoneLogger.e(
                        com.grayzone.app.LogComponent.OVERLAY,
                        "Failed to open overlay settings",
                        e
                    )
                }
                postOverlayPermissionRecoveryNotification(settingsIntent)
                return
            }
        }
        
        dismissOverlay()
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        val presentation = OverlayPresentation.resolve(mode)

        // Lock-type screens (no progress bar) clear any running session countdown notification.
        if (!presentation.showsProgress) {
            cancelCountdownNotification()
            evictLockedApp(packageName)
        }

        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val resolvedWait = waitSeconds ?: com.grayzone.app.data.PerAppLimits.waitSeconds(
            hasCustom = hasCustom,
            perApp = prefs.getInt(PrefsKeys.PER_APP_WAIT_SECONDS + packageName, DEFAULT_WAIT),
            global = prefs.getInt(PrefsKeys.WAIT_SECONDS, DEFAULT_WAIT)
        )

        secondsRemaining = presentation.initialSeconds(
            SecondsInputs(
                now = System.currentTimeMillis(),
                lockedUntil = lockedUntil,
                waitSeconds = resolvedWait
            )
        )
        val totalSeconds = secondsRemaining
        
        val useCustomOnly = prefs.getBoolean(PrefsKeys.USE_CUSTOM_PROMPTS_ONLY, false)
        // F-148: corrupt JSON → empty custom list for the pool only; never write prefs here.
        val customPrompts = Prompts.parseCustom(
            prefs.getString(PrefsKeys.CUSTOM_PROMPTS_JSON, null),
            sharedGson
        ) ?: emptyList()
        val promptsList = Prompts.resolvePool(customPrompts, useCustomOnly)
        val prompt = promptsList.randomOrNull() ?: "Take a moment to reflect."

        val ctx = this
        val chrome = overlayChrome()
        val root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    // BACK means "I'm leaving": go home and take the overlay with us, so a lock
                    // screen that lingers past the eviction always has an obvious way out.
                    // Nothing is granted — dismissing cancels the friction countdown too.
                    dismissOverlay()
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        ctx.startActivity(homeIntent)
                    } catch (e: Exception) {
                        // ignore
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(chrome.bg)
            // F-233: announce the protection pane to TalkBack.
            contentDescription = OVERLAY_PANE_TITLE
            accessibilityPaneTitle = OVERLAY_PANE_TITLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(chrome.surface)
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        val appNameView = TextView(ctx).apply {
            text = appName.uppercase()
            textSize = 11f
            setTextColor(chrome.greyDim)
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT
        }

        val titleView = TextView(ctx).apply {
            text = presentation.title
            textSize = 34f
            setTextColor(chrome.text)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val divider = View(ctx).apply { setBackgroundColor(chrome.border) }
        val divParams = LinearLayout.LayoutParams(dp(48), dp(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16); bottomMargin = dp(16)
        }

        val promptView = TextView(ctx).apply {
            text = presentation.bodyText(prompt)
            textSize = 17f
            setTextColor(chrome.grey)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }

        val countdownView = TextView(ctx).apply {
            text = presentation.countdownText(secondsRemaining)
            textSize = presentation.countdownTextSizeSp
            setTextColor(chrome.accent)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = totalSeconds
            this.progress = totalSeconds
            progressTintList = android.content.res.ColorStateList.valueOf(chrome.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(chrome.border)
        }
        val progressParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) }
        
        if (!presentation.showsProgress) {
            progress.visibility = View.GONE
        }

        val subView = TextView(ctx).apply {
            text = presentation.subText(secondsRemaining)
            textSize = 12f
            setTextColor(chrome.grey)
            gravity = Gravity.CENTER
        }
        val subParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(24) }

        val skipBtn = Button(ctx).apply {
            text = presentation.skipButtonLabel
            setTextColor(chrome.grey)
            background = makeOutlineDrawable()
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val skipParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
        skipBtn.setOnClickListener {
            val waitedMs = (totalSeconds - secondsRemaining).coerceAtLeast(0) * 1000L
            presentation.onSkip(
                SkipContext(packageName, waitedMs) { pkg, ms -> 
                    serviceScope.launch(Dispatchers.IO) {
                        recordFrictionSkip(pkg, ms)
                    }
                }
            )
            dismissOverlay()
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(homeIntent)
            } catch (e: Exception) {
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.OVERLAY,
                    "Failed to launch Home after skip",
                    e
                )
            }
        }

        card.addView(appNameView, wrapCentered())
        card.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        card.addView(divider, divParams)
        card.addView(promptView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(countdownView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        card.addView(progress, progressParams)
        card.addView(subView, subParams)
        card.addView(skipBtn, skipParams)

        // F-097: scrollable card so landscape / large font can reach Leave App.
        val scroller = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply { leftMargin = dp(24); rightMargin = dp(24); topMargin = dp(24); bottomMargin = dp(24) }
        card.background = makeCardBackground()
        scroller.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(scroller, cardParams)

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = OVERLAY_WINDOW_TITLE
        }

        try {
            windowManager?.addView(root, wlp)
            overlayView = root
            lastOverlayPackageName = packageName
            lastOverlayAppName = appName
            lastOverlayMode = mode
            lastOverlayLockedUntil = lockedUntil
            if (!presentation.showsProgress) armLockOverlayStickiness(packageName)
        } catch (e: Exception) {
            // Must not fail open during a lockout (F-001): keep the service alive and surface the
            // failure so ProtectionHealth / logs can show protection is broken.
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.OVERLAY,
                "addView failed mode=$mode pkg=$packageName — overlay not shown",
                e
            )
            com.grayzone.app.data.ProtectionHealthRepository.updateOverlayStatus(false)
            return
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
                    countdownView.text = presentation.countdownText(remaining)
                    if (presentation.showsProgress) {
                        subView.text = presentation.subText(remaining)
                        progress.progress = remaining
                    }
                }
            }
            // Countdown finished (also reached immediately if it started at 0).
            // If this job was cancelled while the loop was exiting (e.g. an app
            // change dismissed the overlay), don't grant a session.
            if (!isActive) return@launch
            presentation.onCountdownFinished(
                CountdownFinishContext(
                    packageName = packageName,
                    appName = appName,
                    markAppUnlocked = { markAppUnlocked(packageName) },
                    dismissOverlay = { dismissOverlay() },
                    isStillForeground = { isPackageStillVisible(packageName) },
                    showFriction = { showOverlay(packageName, appName, OverlayMode.FRICTION, 0L) }
                )
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // F-231: rebuild overlay against new density / night mode / pane geometry.
        val pkg = lastOverlayPackageName
        val name = lastOverlayAppName
        if (overlayView != null && pkg != null && name != null) {
            showOverlay(pkg, name, lastOverlayMode, lastOverlayLockedUntil)
        }
    }

    /**
     * A lock screen has to outlive the jump to the launcher that [evictLockedApp] just triggered,
     * otherwise Grayzone kicks the user out and then instantly hides the explanation. It stays up
     * over the launcher only — opening anything else dismisses it — with a timeout so a missed
     * transition can never leave the screen wedged.
     */
    private fun armLockOverlayStickiness(packageName: String) {
        lockOverlayPackage = packageName
        lockAutoDismissJob?.cancel()
        lockAutoDismissJob = serviceScope.launch(Dispatchers.Main) {
            delay(LOCK_OVERLAY_MAX_VISIBLE_MS)
            if (lockOverlayPackage == packageName) dismissOverlay()
        }
    }

    private fun dismissOverlay() {
        countdownJobUI?.cancel(); countdownJobUI = null
        lockAutoDismissJob?.cancel(); lockAutoDismissJob = null
        lockOverlayPackage = null
        overlayView?.let { view ->
            try {
                view.clearAnimation()
                windowManager?.removeView(view)
                // F-098: clear only after successful remove so a failed dismiss can retry.
                overlayView = null
                lastOverlayPackageName = null
                lastOverlayAppName = null
                lastOverlayMode = 0
                lastOverlayLockedUntil = 0L
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.OVERLAY, "Failed to remove overlay", e)
                // F-019: ensure references are cleared even if removeView throws
                overlayView = null
                lastOverlayPackageName = null
                lastOverlayAppName = null
                lastOverlayMode = 0
                lastOverlayLockedUntil = 0L
            }
        }
    }

    private fun markAppUnlocked(packageName: String) {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val sessionMins = com.grayzone.app.data.PerAppLimits.sessionMinutes(hasCustom, prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + packageName, 10), prefs.getInt(PrefsKeys.SESSION_MINUTES, 10))

        // Validate session duration: must be between 1 minute and 24 hours.
        // The lockout is validated in [beginLockout], where it is actually applied.
        val validSessionMins = sessionMins.coerceIn(1, 24 * 60)
        if (validSessionMins != sessionMins) {
            GrayzoneLogger.w(
                LogComponent.OVERLAY,
                "Invalid session duration $sessionMins mins for $packageName, clamped to $validSessionMins"
            )
        }

        // Clamp the granted session to whatever daily budget remains so a fresh
        // full-length session can't overshoot a nearly-exhausted budget.
        val requestedSessionMs = validSessionMins * 60 * 1000L
        val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + packageName, 0)
        val budgetRemainingMs = com.grayzone.app.data.DailyBudget.remainingMs(
            budgetMins = budgetMins,
            dateKey = DateUtils.getCurrentDateKey(),
            lastResetDateKey = prefs.getString(PrefsKeys.DAILY_RESET_DATE + packageName, ""),
            usedMs = prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + packageName, 0L)
        )
        val grantedSessionMs = com.grayzone.app.data.DailyBudget.grantedSessionMs(
            sessionMs = requestedSessionMs,
            budgetRemainingMs = budgetRemainingMs
        )

        val activeUntil = now + grantedSessionMs
        val nowElapsed = android.os.SystemClock.elapsedRealtime()

        // The lockout is NOT written here. It is materialised only when the session is actually
        // used up (see [beginLockout]); writing it up front made every paused session look like
        // an active lockout and blocked the user mid-session with a `session + lockout` countdown.
        prefs.edit()
            .remove(PrefsKeys.REMAINING_MILLIS + packageName)
            .remove(PrefsKeys.LOCKED_UNTIL + packageName)
            .remove(PrefsKeys.LOCKED_STARTED_ELAPSED + packageName)
            .remove(PrefsKeys.LOCKED_DURATION_MS + packageName)
            .putLong(PrefsKeys.ACTIVE_UNTIL + packageName, activeUntil)
            .putLong(PrefsKeys.ACTIVE_STARTED_ELAPSED + packageName, nowElapsed)
            .putLong(PrefsKeys.ACTIVE_DURATION_MS + packageName, grantedSessionMs)
            .apply()

        startCountdownNotification(packageName, activeUntil)
        SessionExpiryScheduler.arm(this, packageName, activeUntil)
        startSessionHeartbeat(packageName)
        usageClock.start(packageName, now)
        GrayzoneLogger.i(LogComponent.SESSION, "Session started for $packageName (${grantedSessionMs / 1000}s)")
    }

    private fun recordFrictionSkip(packageName: String, waitedMs: Long) {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val lockoutMins = com.grayzone.app.data.PerAppLimits.lockoutMinutes(
            hasCustom,
            prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 60),
            prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
        )

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
                GrayzoneLogger.e(LogComponent.DATABASE, "Failed to record a skipped pause screen", e)
            }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    private fun wrapCentered() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private data class OverlayChrome(
        val bg: Int,
        val surface: Int,
        val accent: Int,
        val accentDim: Int,
        val text: Int,
        val grey: Int,
        val greyDim: Int,
        val red: Int,
        val border: Int,
        val onAccent: Int,
    )

    private fun overlayChrome(): OverlayChrome {
        val dark = com.grayzone.app.ui.theme.isNightMode(this)
        return if (dark) OverlayChrome(
            bg = BG_DARK, surface = SURFACE_DARK, accent = ACCENT_DARK, accentDim = ACCENT_DIM_DARK,
            text = TEXT_DARK, grey = GREY_DARK, greyDim = GREY_DIM_DARK, red = RED_DARK,
            border = BORDER_DARK, onAccent = ON_ACCENT_DARK
        ) else OverlayChrome(
            bg = BG_LIGHT, surface = SURFACE_LIGHT, accent = ACCENT_LIGHT, accentDim = ACCENT_DIM_LIGHT,
            text = TEXT_LIGHT, grey = GREY_LIGHT, greyDim = GREY_DIM_LIGHT, red = RED_LIGHT,
            border = BORDER_LIGHT, onAccent = ON_ACCENT_LIGHT
        )
    }

    private fun makeCardBackground(): android.graphics.drawable.GradientDrawable {
        val c = overlayChrome()
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(c.surface)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), c.border)
        }
    }

    private fun makeOutlineDrawable(): android.graphics.drawable.GradientDrawable {
        val c = overlayChrome()
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), c.greyDim)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Grayzone Active", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Grayzone is monitoring your app usage"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val recovery = NotificationChannel(
            RECOVERY_CHANNEL_ID,
            "Grayzone Protection Recovery",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a required permission is missing"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(recovery)
        val countdown = NotificationChannel(
            COUNTDOWN_CHANNEL_ID,
            "Session Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live countdown while an app session is running"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(countdown)
    }

    /** F-082: tappable recovery when background startActivity is blocked. */
    private fun postOverlayPermissionRecoveryNotification(settingsIntent: Intent) {
        try {
            val pi = PendingIntent.getActivity(
                this,
                42,
                settingsIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, RECOVERY_CHANNEL_ID)
                .setContentTitle("Protection is OFF")
                .setContentText("Tap to allow Display over other apps for Grayzone.")
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(AMBER_WARNING)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            getSystemService(NotificationManager::class.java).notify(RECOVERY_NOTIF_ID, n)
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "Failed to post overlay recovery notification", e)
        }
    }

    /**
     * Re-read accessibility + overlay permissions, update [ProtectionHealthRepository], and
     * re-post the ongoing notification so it never claims "active" when protection is off (F-080).
     */
    private fun refreshProtectionNotification() {
        try {
            val a11yOn = com.grayzone.app.isAccessibilityServiceEnabled(this)
            val overlayOn = android.provider.Settings.canDrawOverlays(this)
            com.grayzone.app.data.ProtectionHealthRepository.updateAccessibilityStatus(a11yOn)
            com.grayzone.app.data.ProtectionHealthRepository.updateOverlayStatus(overlayOn)

            postProtectionNotification()
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "refreshProtectionNotification failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val a11yOn = com.grayzone.app.isAccessibilityServiceEnabled(this)
        val overlayOn = android.provider.Settings.canDrawOverlays(this)
        val copy = com.grayzone.app.ProtectionNotificationState.resolve(a11yOn, overlayOn)
        val contentIntent = if (copy.fixRequirement != null) {
            PendingIntent.getActivity(
                this,
                copy.fixRequirement.ordinal + 10,
                com.grayzone.app.settingsIntentFor(this, copy.fixRequirement),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(if (copy.isWarning) AMBER_WARNING else NOTIFICATION_ACCENT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

}



