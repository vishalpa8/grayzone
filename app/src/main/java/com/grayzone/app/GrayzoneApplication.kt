package com.grayzone.app

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.grayzone.app.data.CleanupWorker
import com.grayzone.app.data.ConfigValidator
import com.grayzone.app.data.DefaultMonitoredApps
import com.grayzone.app.data.StreakEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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
    // failure never tears the scope down for later callers.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        
        GrayzoneLogger.i(
            LogComponent.BOOT,
            "Grayzone ${BuildConfig.VERSION_NAME} starting on ${android.os.Build.MODEL}"
        )
        
        // Keep onCreate light: this process also hosts GrayzoneAccessibilityService. Heavy or
        // failing work on the main thread can ANR/crash the process, which makes Android disable
        // the accessibility service.
        validateConfiguration()
        resetRuntimeStateIfNeeded()
        runDeferredStartupWork()
        catchUpStreaks()
        schedulePeriodicCleanup()
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
        
        // Validate wait duration (3-30 seconds)
        val waitSeconds = prefs.getInt(PrefsKeys.WAIT_SECONDS, ConfigValidator.DEFAULT_WAIT_SECONDS)
        if (!ConfigValidator.isWaitSecondsValid(waitSeconds)) {
            val repaired = ConfigValidator.repairWaitSeconds(waitSeconds)
            GrayzoneLogger.w(LogComponent.BOOT, "Invalid WAIT_SECONDS=$waitSeconds, resetting to $repaired")
            editor.putInt(PrefsKeys.WAIT_SECONDS, repaired)
            needsRepair = true
        }
        
        // Validate session duration (1-60 minutes)
        val sessionMinutes = prefs.getInt(PrefsKeys.SESSION_MINUTES, ConfigValidator.DEFAULT_SESSION_MINUTES)
        if (!ConfigValidator.isSessionMinutesValid(sessionMinutes)) {
            val repaired = ConfigValidator.repairSessionMinutes(sessionMinutes)
            GrayzoneLogger.w(LogComponent.BOOT, "Invalid SESSION_MINUTES=$sessionMinutes, resetting to $repaired")
            editor.putInt(PrefsKeys.SESSION_MINUTES, repaired)
            needsRepair = true
        }
        
        // Validate lockout duration (15 minutes - 5 hours)
        val lockoutMinutes = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, ConfigValidator.DEFAULT_LOCKOUT_MINUTES)
        if (!ConfigValidator.isLockoutMinutesValid(lockoutMinutes)) {
            val repaired = ConfigValidator.repairLockoutMinutes(lockoutMinutes)
            GrayzoneLogger.w(LogComponent.BOOT, "Invalid LOCKOUT_MINUTES=$lockoutMinutes, resetting to $repaired")
            editor.putInt(PrefsKeys.LOCKOUT_MINUTES, repaired)
            needsRepair = true
        }
        
        // Validate monitored apps is a valid set
        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, null)
        if (monitoredApps == null) {
            editor.putStringSet(PrefsKeys.MONITORED_APPS, emptySet())
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
            } catch (_: Exception) {
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
