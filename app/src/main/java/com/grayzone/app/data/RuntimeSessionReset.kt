package com.grayzone.app.data

import android.content.SharedPreferences
import com.grayzone.app.DateUtils
import com.grayzone.app.PrefsKeys

/**
 * Clears runtime-only session lock state (active sessions, lockouts, paused
 * remainders) once per calendar day, so every day starts fresh after midnight.
 * Daily budgets reset independently via their own date-keyed storage.
 *
 * Called from GrayzoneApplication.onCreate() (process restart) AND from
 * OverlayService (long-running service crossing midnight).
 */
object RuntimeSessionReset {

    private const val RUNTIME_KEY_SUFFIX = "runtime"

    /**
     * Performs the reset if the calendar day changed since the last reset.
     * Returns true when a reset was actually performed.
     */
    fun resetIfNeeded(prefs: SharedPreferences): Boolean {
        val lastResetDateKey = prefs.getString(PrefsKeys.DAILY_RESET_DATE + RUNTIME_KEY_SUFFIX, "") ?: ""
        val now = System.currentTimeMillis()

        if (!DateUtils.shouldResetDailyRuntimeState(now, lastResetDateKey)) {
            return false
        }

        val editor = prefs.edit()
        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
        monitoredApps.forEach { pkg ->
            editor.remove(PrefsKeys.ACTIVE_UNTIL + pkg)
                .remove(PrefsKeys.LOCKED_UNTIL + pkg)
                .remove(PrefsKeys.REMAINING_MILLIS + pkg)
        }
        editor.putString(PrefsKeys.DAILY_RESET_DATE + RUNTIME_KEY_SUFFIX, DateUtils.getCurrentDateKey())
            .apply()
        return true
    }
}
