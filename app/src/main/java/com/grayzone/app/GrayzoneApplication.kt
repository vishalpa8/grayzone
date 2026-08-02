package com.grayzone.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.grayzone.app.data.CleanupWorker
import com.grayzone.app.data.ConfigValidator
import com.grayzone.app.data.DefaultMonitoredApps
import com.grayzone.app.data.StateBackupManager
import com.grayzone.app.data.StreakEvaluator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application class for global initialization.
 * 
 * Handles:
 * - Scheduling periodic database cleanup (weekly)
 * - Future: Crash reporting initialization
 * - Future: Analytics initialization
 */
class GrayzoneApplication : Application() {

    // App-scoped background work (DB-backed catch-up); SupervisorJob so one
    // failure never tears the scope down for later callers. Handler (F-206) keeps
    // an uncaught backup/streak failure from killing the whole process.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            GrayzoneLogger.e(LogComponent.BOOT, "Uncaught exception in appScope", t)
        }
    )

    // Held so SharedPreferences (which keeps only a weak reference to change listeners) does not
    // let the auto-backup listener be garbage-collected.
    private var backupListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // Set (cheaply) whenever a preference changes; the actual encrypt+write happens later, only when
    // dirty, on backgrounding or the coarse flush loop. Decouples write frequency from edit frequency.
    private val backupDirty = AtomicBoolean(false)

    // Number of started Activities; when it returns to 0 the app has gone to the background.
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        
        // The :a11y process must NEVER touch prefs, the backup file, WorkManager, or restore —
        // SharedPreferences isn't multi-process safe and two writers corrupt the backup.
        if (Application.getProcessName() != packageName) return

        GrayzoneLogger.i(
            LogComponent.BOOT,
            "Grayzone ${BuildConfig.VERSION_NAME} starting on ${android.os.Build.MODEL}"
        )

        // MUST run first: if the user cleared data/cache to escape their locks, pull the state back
        // from the external backup BEFORE anything else reads preferences (config validation below,
        // services, and the UI). Synchronous by design and kept small so it never ANRs onCreate.
        StateBackupManager.restoreIfWiped(this)

        // Force-stop strips Accessibility in Settings; sync health before UI/services assume it is on.
        com.grayzone.app.data.ProtectionHealthRepository.updateAccessibilityStatus(
            isAccessibilityServiceEnabled(this)
        )

        // Keep onCreate light: this is the main app process. GrayzoneAccessibilityService now runs
        // in an isolated ":a11y" process (see the manifest), so heavy or failing work here can no
        // longer ANR/crash the accessibility service — but a light onCreate still avoids UI jank and
        // slow cold starts, so keep deferring non-critical work.
        validateConfiguration()
        resetRuntimeStateIfNeeded()
        runDeferredStartupWork()
        catchUpStreaks()
        schedulePeriodicCleanup()
        registerStateBackup()
    }

    /**
     * Keeps the external anti-tamper backup fresh while being kind to the battery.
     *
     * Rather than re-encrypting and writing on every preference edit (bursty during heavy
     * app-switching), a change only flips a cheap in-memory [backupDirty] flag. The snapshot is
     * actually written:
     *  - once at startup, but only if no backup file exists yet (avoids re-writing on every restart);
     *  - the moment the app goes to the background — which is exactly when the user could reach
     *    Settings to clear data, so the backup is guaranteed fresh at the critical instant; and
     *  - on a coarse [StateBackupManager.FLUSH_INTERVAL_MS] loop, and only when dirty, to capture
     *    changes the accessibility service makes while no UI is visible (e.g. a lock applied while the
     *    user is in another app).
     *
     * Net effect: at most one small (~1 KB) write per foreground session or per flush interval,
     * versus potentially dozens per minute before — with fresher data at the one moment that matters.
     */
    private fun registerStateBackup() {
        // One-time baseline so a wipe is recoverable even before the first backgrounding/flush.
        appScope.launch {
            if (!StateBackupManager.backupExists(this@GrayzoneApplication)) {
                StateBackupManager.writeBackup(this@GrayzoneApplication)
            }
        }

        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // Ignore restore's clear+write storm — would race a flush against applyInto.
            if (!StateBackupManager.restoreInProgress) {
                backupDirty.set(true) // no I/O here; the flush points below do the actual write
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        backupListener = listener

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) flushBackupIfDirty() // app went to the background
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Coarse safety net for changes made while no Activity is visible. delay() adds no wakelock
        // and is suspended during Doze, so an idle app costs nothing here.
        appScope.launch {
            while (true) {
                delay(StateBackupManager.FLUSH_INTERVAL_MS)
                flushBackupIfDirty()
            }
        }
    }

    /** Writes the backup only if something changed since the last write; clears dirty only on success. */
    private fun flushBackupIfDirty() {
        if (StateBackupManager.restoreInProgress) return
        if (!backupDirty.compareAndSet(true, false)) return
        appScope.launch {
            // F-063: restore dirty so a skipped/failed write is retried on the next flush.
            if (!StateBackupManager.writeBackup(this@GrayzoneApplication)) {
                backupDirty.set(true)
            }
        }
    }

    /**
     * PackageManager queries, prefs migration, and one-shot default-app seeding — off the main
     * thread so accessibility service bind is never blocked.
     */
    private fun runDeferredStartupWork() {
        appScope.launch {
            try {
                seedDefaultMonitoredAppsIfNeeded()
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.BOOT, "Deferred startup work failed: ${e.message}")
            }
        }
    }

    /**
     * Evaluate any missed streak days on process start so streak progress does
     * not depend on the Home screen being opened. Idempotent per calendar day.
     */
    private fun catchUpStreaks() {
        appScope.launch {
            try {
                StreakEvaluator.run(this@GrayzoneApplication)
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.BOOT, "Streak catch-up on start failed: ${e.message}")
            }
        }
    }
    
    /**
     * Validate SharedPreferences configuration and reset corrupted values to defaults.
     * This prevents crashes or unexpected behavior from invalid stored values.
     */
    private fun validateConfiguration() {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        var needsRepair = false

        // Typed getters can throw ClassCastException after a poisoned/type-drifted restore (F-060).
        // Never let that escape Application.onCreate.
        try {
            val waitSeconds = prefs.getInt(PrefsKeys.WAIT_SECONDS, ConfigValidator.DEFAULT_WAIT_SECONDS)
            if (!ConfigValidator.isWaitSecondsValid(waitSeconds)) {
                val repaired = ConfigValidator.repairWaitSeconds(waitSeconds)
                GrayzoneLogger.w(LogComponent.BOOT, "Invalid WAIT_SECONDS=$waitSeconds, resetting to $repaired")
                editor.putInt(PrefsKeys.WAIT_SECONDS, repaired)
                needsRepair = true
            }

            val sessionMinutes = prefs.getInt(PrefsKeys.SESSION_MINUTES, ConfigValidator.DEFAULT_SESSION_MINUTES)
            if (!ConfigValidator.isSessionMinutesValid(sessionMinutes)) {
                val repaired = ConfigValidator.repairSessionMinutes(sessionMinutes)
                GrayzoneLogger.w(LogComponent.BOOT, "Invalid SESSION_MINUTES=$sessionMinutes, resetting to $repaired")
                editor.putInt(PrefsKeys.SESSION_MINUTES, repaired)
                needsRepair = true
            }

            val lockoutMinutes = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, ConfigValidator.DEFAULT_LOCKOUT_MINUTES)
            if (!ConfigValidator.isLockoutMinutesValid(lockoutMinutes)) {
                val repaired = ConfigValidator.repairLockoutMinutes(lockoutMinutes)
                GrayzoneLogger.w(LogComponent.BOOT, "Invalid LOCKOUT_MINUTES=$lockoutMinutes, resetting to $repaired")
                editor.putInt(PrefsKeys.LOCKOUT_MINUTES, repaired)
                needsRepair = true
            }

            val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, null)
            if (monitoredApps == null) {
                editor.putStringSet(PrefsKeys.MONITORED_APPS, emptySet())
                needsRepair = true
            }
        } catch (e: ClassCastException) {
            GrayzoneLogger.e(
                LogComponent.BOOT,
                "Preference type drift in validateConfiguration; resetting corrupted keys",
                e
            )
            editor
                .remove(PrefsKeys.WAIT_SECONDS)
                .remove(PrefsKeys.SESSION_MINUTES)
                .remove(PrefsKeys.LOCKOUT_MINUTES)
                .remove(PrefsKeys.MONITORED_APPS)
                .putStringSet(PrefsKeys.MONITORED_APPS, emptySet())
            needsRepair = true
        }

        if (needsRepair) {
            editor.apply()
            GrayzoneLogger.i(
                LogComponent.BOOT,
                "Configuration repaired, invalid values reset to defaults"
            )
        } else {
            GrayzoneLogger.d(
                LogComponent.BOOT,
                "Configuration validation passed"
            )
        }
    }
    
    /**
     * One-shot: enable Instagram + YouTube for sessions/lockouts when installed.
     * Runs on [appScope] (IO) — [PackageManager.getPackageInfo] must not block service bind.
     */
    private fun seedDefaultMonitoredAppsIfNeeded() {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        if (prefs.contains(PrefsKeys.DEFAULT_APPS_SEEDED)) return

        val existing = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
        val installed = DefaultMonitoredApps.DEFAULT_PACKAGES.filter { pkg ->
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(
                        pkg,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                // F-216: binder failures are not "not installed" — log and skip, don't seed wrongly.
                GrayzoneLogger.w(LogComponent.BOOT, "getPackageInfo failed for $pkg: ${e.message}")
                false
            }
        }.toSet()
        val seeded = DefaultMonitoredApps.seedIfNeeded(
            alreadySeeded = false,
            existing = existing,
            installedPackages = installed
        ) ?: return
        prefs.edit()
            .putStringSet(PrefsKeys.MONITORED_APPS, seeded)
            .putBoolean(PrefsKeys.DEFAULT_APPS_SEEDED, true)
            .apply()
        GrayzoneLogger.i(
            LogComponent.BOOT,
            "Default monitored apps seeded: ${seeded.intersect(DefaultMonitoredApps.DEFAULT_PACKAGES.toSet())}"
        )
    }

    /**
     * Clears runtime-only session state once per calendar day while preserving stats.
     */
    private fun resetRuntimeStateIfNeeded() {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (com.grayzone.app.data.RuntimeSessionReset.resetIfNeeded(prefs)) {
            GrayzoneLogger.i(
                LogComponent.BOOT,
                "Runtime session state reset for a new day; stats preserved"
            )
        }
    }

    /**
     * Schedule weekly database cleanup to prevent unbounded growth.
     */
    private fun schedulePeriodicCleanup() {
        try {
            val cleanupRequest = PeriodicWorkRequestBuilder<CleanupWorker>(
                7, TimeUnit.DAYS  // Run weekly
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "database_cleanup",
                ExistingPeriodicWorkPolicy.KEEP,  // Don't restart if already scheduled
                cleanupRequest
            )

            GrayzoneLogger.d(
                LogComponent.CLEANUP,
                "Scheduled weekly database cleanup"
            )
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.CLEANUP, "Failed to schedule cleanup: ${e.message}")
        }
    }
}
