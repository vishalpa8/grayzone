package com.grayzone.app

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.grayzone.app.data.CleanupWorker
import com.grayzone.app.data.ConfigValidator
import com.grayzone.app.data.StreakEvaluator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for global initialization.
 * 
 * Handles:
 * - Scheduling periodic database cleanup (weekly)
 * - Future: Crash reporting initialization
 * - Future: Analytics initialization
 */
@HiltAndroidApp
class GrayzoneApplication : Application() {

    // App-scoped background work (DB-backed catch-up); SupervisorJob so one
    // failure never tears the scope down for later callers. Handler (F-206) keeps
    // an uncaught streak failure from killing the whole process.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            GrayzoneLogger.e(LogComponent.BOOT, "Uncaught exception in appScope", t)
        }
    )

    override fun onCreate() {
        super.onCreate()

        // The :a11y process must NEVER touch prefs or WorkManager —
        // SharedPreferences isn't multi-process safe.
        if (Application.getProcessName() != packageName) return

        GrayzoneLogger.i(
            LogComponent.BOOT,
            "Grayzone ${BuildConfig.VERSION_NAME} starting on ${android.os.Build.MODEL}"
        )

        // Force-stop strips Accessibility in Settings; sync health before UI/services assume it is on.
        com.grayzone.app.data.ProtectionHealthRepository.updateAccessibilityStatus(
            isAccessibilityServiceEnabled(this)
        )

        // Keep onCreate light: this is the main app process. GrayzoneAccessibilityService now runs
        // in an isolated ":a11y" process (see the manifest), so heavy or failing work here can no
        // longer ANR/crash the accessibility service — but a light onCreate still avoids UI jank and
        // slow cold starts, so keep deferring non-critical work.
        validateConfiguration()
        migrateLockoutFormat()
        resetRuntimeStateIfNeeded()
        catchUpStreaks()
        schedulePeriodicCleanup()
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
     * One-shot cleanup of lockouts armed by the old "lock at session grant" format.
     */
    private fun migrateLockoutFormat() {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val cleared = com.grayzone.app.data.LockoutFormatMigration.run(prefs)
        if (cleared > 0) {
            GrayzoneLogger.i(LogComponent.BOOT, "Cleared $cleared stale lockout(s) from the old format")
        }
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
