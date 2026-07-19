package com.grayzone.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.grayzone.app.data.CleanupWorker
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
    
    override fun onCreate() {
        super.onCreate()
        
        GrayzoneLogger.i(
            LogComponent.BOOT,
            "Grayzone ${BuildConfig.VERSION_NAME} starting on ${android.os.Build.MODEL}"
        )
        
        validateConfiguration()
        schedulePeriodicCleanup()
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
        val waitSeconds = prefs.getInt(PrefsKeys.WAIT_SECONDS, 5)
        if (waitSeconds !in 3..30) {
            GrayzoneLogger.w(
                LogComponent.BOOT,
                "Invalid WAIT_SECONDS=$waitSeconds, resetting to 5"
            )
            editor.putInt(PrefsKeys.WAIT_SECONDS, 5)
            needsRepair = true
        }
        
        // Validate session duration (1-60 minutes)
        val sessionMinutes = prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
        if (sessionMinutes !in 1..60) {
            GrayzoneLogger.w(
                LogComponent.BOOT,
                "Invalid SESSION_MINUTES=$sessionMinutes, resetting to 10"
            )
            editor.putInt(PrefsKeys.SESSION_MINUTES, 10)
            needsRepair = true
        }
        
        // Validate lockout duration (15 minutes - 5 hours)
        val lockoutMinutes = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
        if (lockoutMinutes !in 15..(5 * 60)) {
            GrayzoneLogger.w(
                LogComponent.BOOT,
                "Invalid LOCKOUT_MINUTES=$lockoutMinutes, resetting to 60"
            )
            editor.putInt(PrefsKeys.LOCKOUT_MINUTES, 60)
            needsRepair = true
        }
        
        // Validate grayscale enabled (boolean)
        if (!prefs.contains(PrefsKeys.GRAYSCALE_ENABLED)) {
            editor.putBoolean(PrefsKeys.GRAYSCALE_ENABLED, true)
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
     * Schedule weekly database cleanup to prevent unbounded growth.
     */
    private fun schedulePeriodicCleanup() {
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
    }
}
