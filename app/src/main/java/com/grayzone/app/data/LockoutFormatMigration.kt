package com.grayzone.app.data

import android.content.SharedPreferences
import com.grayzone.app.PrefsKeys

/**
 * Retires lockouts written by builds that armed the penalty box at session-grant time.
 *
 * Those builds stored `lockedUntil = activeUntil + lockout`, so `LOCKED_DURATION_MS` covered the
 * session as well as the lockout. That made a merely-paused session look like an active lockout
 * and produced countdowns like "2h 4m" for a 2h limit. The new format stores exactly the lockout
 * length, so any record longer than the app's configured lockout is stale and is cleared once.
 */
object LockoutFormatMigration {

    private const val MIGRATED_KEY = "lockout_format_v2_migrated"

    /** Returns the number of stale lockouts cleared. */
    fun run(prefs: SharedPreferences): Int {
        if (prefs.getBoolean(MIGRATED_KEY, false)) return 0

        val packages = prefs.all.keys
            .filter { it.startsWith(PrefsKeys.LOCKED_DURATION_MS) }
            .map { it.removePrefix(PrefsKeys.LOCKED_DURATION_MS) }
            .filter { it.isNotEmpty() }

        val editor = prefs.edit()
        var cleared = 0
        packages.forEach { pkg ->
            val storedDurationMs = prefs.getLong(PrefsKeys.LOCKED_DURATION_MS + pkg, 0L)
            if (storedDurationMs <= 0L) return@forEach

            val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + pkg, false)
            val lockoutMs = PerAppLimits.lockoutMinutes(
                hasCustom = hasCustom,
                perApp = prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg, 60),
                global = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
            ).coerceIn(15, 24 * 60) * 60 * 1000L

            if (storedDurationMs > lockoutMs) {
                editor.remove(PrefsKeys.LOCKED_UNTIL + pkg)
                    .remove(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg)
                    .remove(PrefsKeys.LOCKED_DURATION_MS + pkg)
                cleared++
            }
        }
        editor.putBoolean(MIGRATED_KEY, true).apply()
        return cleared
    }
}
