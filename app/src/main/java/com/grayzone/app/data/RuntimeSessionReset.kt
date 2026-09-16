package com.grayzone.app.data

import android.content.SharedPreferences
import com.grayzone.app.DateUtils
import com.grayzone.app.PrefsKeys
import java.util.TimeZone

/**
 * Clears runtime-only session lock state (active sessions, lockouts, paused
 * remainders) once per calendar day, so every day starts fresh after midnight.
 * Daily budgets reset independently via their own date-keyed storage.
 *
 * Called from GrayzoneApplication.onCreate() (process restart) AND from
 * OverlayService (long-running service crossing midnight).
 *
 * A timezone change that advances the local date string must NOT clear lockouts
 * (F-240) — only a same-zone midnight crossing does.
 */
object RuntimeSessionReset {

    private const val RUNTIME_KEY_SUFFIX = "runtime"

    /**
     * Performs the reset if the calendar day changed since the last reset.
     * Returns true when a reset was actually performed.
     *
     * [now] is injectable so midnight-boundary tests do not depend on wall clock.
     * [zoneId] is injectable for tests; production uses the device default zone.
     */
    fun resetIfNeeded(
        prefs: SharedPreferences,
        now: Long = System.currentTimeMillis(),
        nowElapsed: Long = android.os.SystemClock.elapsedRealtime(),
        zoneId: String = TimeZone.getDefault().id
    ): Boolean {
        val lastResetDateKey = prefs.getString(PrefsKeys.DAILY_RESET_DATE + RUNTIME_KEY_SUFFIX, "") ?: ""
        val lastZone = prefs.getString(PrefsKeys.DAILY_RESET_ZONE_ID, null)
        val currentDateKey = DateUtils.formatDateKey(now)

        // Timezone jump: realign the stored date key without clearing protection state.
        if (lastZone != null && lastZone != zoneId) {
            prefs.edit()
                .putString(PrefsKeys.DAILY_RESET_DATE + RUNTIME_KEY_SUFFIX, currentDateKey)
                .putString(PrefsKeys.DAILY_RESET_ZONE_ID, zoneId)
                .apply()
            return false
        }

        if (!DateUtils.shouldResetDailyRuntimeState(now, lastResetDateKey)) {
            if (lastZone == null) {
                prefs.edit().putString(PrefsKeys.DAILY_RESET_ZONE_ID, zoneId).apply()
            }
            return false
        }

        val editor = prefs.edit()
        // F-052: clear runtime locks for every package that has keys, not only currently monitored.
        val packages = linkedSetOf<String>()
        prefs.all.keys.forEach { key ->
            when {
                key.startsWith(PrefsKeys.ACTIVE_UNTIL) ->
                    packages.add(key.removePrefix(PrefsKeys.ACTIVE_UNTIL))
                key.startsWith(PrefsKeys.LOCKED_UNTIL) ->
                    packages.add(key.removePrefix(PrefsKeys.LOCKED_UNTIL))
                key.startsWith(PrefsKeys.REMAINING_MILLIS) ->
                    packages.add(key.removePrefix(PrefsKeys.REMAINING_MILLIS))
            }
        }
        packages.forEach { pkg ->
            if (pkg.isEmpty()) return@forEach
            
            // Do not clear locks that are still active based on elapsedRealtime
            val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
            val activeStartedElapsedMs = prefs.getLong(PrefsKeys.ACTIVE_STARTED_ELAPSED + pkg, 0L)
            val activeDurationMs = prefs.getLong(PrefsKeys.ACTIVE_DURATION_MS + pkg, 0L)
            
            val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
            val lockedStartedElapsedMs = prefs.getLong(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg, 0L)
            val lockedDurationMs = prefs.getLong(PrefsKeys.LOCKED_DURATION_MS + pkg, 0L)
            
            val isSessionActive = if (activeStartedElapsedMs > 0L) {
                com.grayzone.app.policy.MonotonicDeadline.isPending(activeUntil, activeStartedElapsedMs, activeDurationMs, now, nowElapsed)
            } else {
                activeUntil > 0L && now < activeUntil
            }
            
            val isLocked = if (lockedStartedElapsedMs > 0L) {
                com.grayzone.app.policy.MonotonicDeadline.isPending(lockedUntil, lockedStartedElapsedMs, lockedDurationMs, now, nowElapsed)
            } else {
                lockedUntil > 0L && now < lockedUntil
            }

            if (!isSessionActive) {
                editor.remove(PrefsKeys.ACTIVE_UNTIL + pkg)
                    .remove(PrefsKeys.REMAINING_MILLIS + pkg)
                    .remove(PrefsKeys.ACTIVE_STARTED_ELAPSED + pkg)
                    .remove(PrefsKeys.ACTIVE_DURATION_MS + pkg)
            }
            
            if (!isLocked) {
                editor.remove(PrefsKeys.LOCKED_UNTIL + pkg)
                    .remove(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg)
                    .remove(PrefsKeys.LOCKED_DURATION_MS + pkg)
            }
        }
        editor.putString(PrefsKeys.DAILY_RESET_DATE + RUNTIME_KEY_SUFFIX, currentDateKey)
            .putString(PrefsKeys.DAILY_RESET_ZONE_ID, zoneId)
            .apply()
        return true
    }
}
