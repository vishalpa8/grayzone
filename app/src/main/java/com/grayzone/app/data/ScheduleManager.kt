package com.grayzone.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.grayzone.app.PrefsKeys
import java.util.Calendar

/**
 * Schedule rule — defines when monitored apps should be blocked.
 */
data class ScheduleRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,               // "Work Hours", "Bedtime", etc.
    val startHour: Int,             // 0-23
    val startMinute: Int,           // 0-59
    val endHour: Int,               // 0-23
    val endMinute: Int,             // 0-59
    val daysOfWeek: Set<Int>,       // Calendar.MONDAY, Calendar.TUESDAY, etc.
    /**
     * Nullable so Gson missing-field deserializes to null (not JVM `false`).
     * Treat null as enabled — see [isEffectivelyEnabled].
     */
    val enabled: Boolean? = true
) {
    /** F-055: missing/null `enabled` in JSON must not disable the rule. */
    fun isEffectivelyEnabled(): Boolean = enabled != false
}

/**
 * Manages schedule rules and focus mode for blocking apps at certain times.
 */
class ScheduleManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = com.grayzone.app.globalGson

    /**
     * Parse result that distinguishes "no rules" from "corrupt JSON" so writers never
     * persist an empty list over the user's real rules (F-241).
     */
    private sealed class RulesLoad {
        data class Ok(val rules: List<ScheduleRule>) : RulesLoad()
        object Corrupt : RulesLoad()
    }

    private fun loadScheduleRules(): RulesLoad {
        val json = prefs.getString(PrefsKeys.SCHEDULE_RULES_JSON, null) ?: return RulesLoad.Ok(emptyList())
        return try {
            val type = object : TypeToken<List<ScheduleRule>>() {}.type
            val parsed = gson.fromJson<List<ScheduleRule>>(json, type) ?: emptyList()
            // F-055: normalize null enabled → true so Gson Unsafe defaults never disable rules.
            RulesLoad.Ok(parsed.map { rule ->
                rule.copy(enabled = rule.isEffectivelyEnabled())
            })
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(com.grayzone.app.LogComponent.SCHEDULE, "Schedule rules are corrupt; refusing to overwrite them", e)
            RulesLoad.Corrupt
        }
    }

    fun getScheduleRules(): List<ScheduleRule> = when (val load = loadScheduleRules()) {
        is RulesLoad.Ok -> load.rules
        RulesLoad.Corrupt -> emptyList() // read path degrades; writers must check loadScheduleRules
    }

    fun saveScheduleRules(rules: List<ScheduleRule>) {
        prefs.edit().putString(PrefsKeys.SCHEDULE_RULES_JSON, gson.toJson(rules)).apply()
    }

    fun addRule(rule: ScheduleRule) {
        when (val load = loadScheduleRules()) {
            RulesLoad.Corrupt -> return // do not wipe
            is RulesLoad.Ok -> {
                val rules = load.rules.toMutableList()
                // Never persist enabled=false from a null/missing Gson default (F-055).
                rules.add(rule.copy(enabled = rule.isEffectivelyEnabled()))
                saveScheduleRules(rules)
            }
        }
    }

    fun removeRule(ruleId: String) {
        when (val load = loadScheduleRules()) {
            RulesLoad.Corrupt -> return
            is RulesLoad.Ok -> saveScheduleRules(load.rules.filter { it.id != ruleId })
        }
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        when (val load = loadScheduleRules()) {
            RulesLoad.Corrupt -> return
            is RulesLoad.Ok -> saveScheduleRules(
                load.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
            )
        }
    }

    /**
     * Returns true if any active schedule rule blocks apps right now.
     */
    fun isCurrentlyScheduled(): Boolean {
        if (isFocusModeActive()) return true

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return matchesAnyRule(getScheduleRules(), dayOfWeek, nowMinutes)
    }

    // ── Quick Focus Mode ──────────────────────────────────────────────────

    fun startFocusMode(durationMinutes: Int) {
        val durationMs = durationMinutes * 60 * 1000L
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        prefs.edit()
            .putBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, true)
            .putLong(PrefsKeys.FOCUS_MODE_UNTIL, nowWall + durationMs)
            .putLong(PrefsKeys.FOCUS_STARTED_ELAPSED, nowElapsed)
            .putLong(PrefsKeys.FOCUS_DURATION_MS, durationMs)
            .apply()
    }

    fun stopFocusMode() {
        prefs.edit()
            .putBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, false)
            .putLong(PrefsKeys.FOCUS_MODE_UNTIL, 0L)
            .putLong(PrefsKeys.FOCUS_STARTED_ELAPSED, 0L)
            .putLong(PrefsKeys.FOCUS_DURATION_MS, 0L)
            .apply()
    }

    fun isFocusModeActive(): Boolean {
        val until = prefs.getLong(PrefsKeys.FOCUS_MODE_UNTIL, 0L)
        val startedElapsed = prefs.getLong(PrefsKeys.FOCUS_STARTED_ELAPSED, 0L)
        val durationMs = prefs.getLong(PrefsKeys.FOCUS_DURATION_MS, 0L)
        val pending = com.grayzone.app.policy.MonotonicDeadline.isPending(
            until, startedElapsed, durationMs,
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()
        )
        if (!pending) {
            if (prefs.getBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, false) || until > 0L) {
                stopFocusMode()
            }
            return false
        }
        return true
    }

    fun getFocusModeRemainingMillis(): Long {
        val until = prefs.getLong(PrefsKeys.FOCUS_MODE_UNTIL, 0L)
        val startedElapsed = prefs.getLong(PrefsKeys.FOCUS_STARTED_ELAPSED, 0L)
        val durationMs = prefs.getLong(PrefsKeys.FOCUS_DURATION_MS, 0L)
        return com.grayzone.app.policy.MonotonicDeadline.remainingMs(
            until, startedElapsed, durationMs,
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()
        )
    }

    // ── Daily Break ────────────────────────────────────────────────────────
    // One hour per day during which nothing is locked: no friction screens,
    // no lockouts, no schedule blocks. Usable once per calendar day.

    fun isBreakActive(): Boolean {
        val until = prefs.getLong(PrefsKeys.BREAK_UNTIL, 0L)
        val startedElapsed = prefs.getLong(PrefsKeys.BREAK_STARTED_ELAPSED, 0L)
        val durationMs = prefs.getLong(PrefsKeys.BREAK_DURATION_MS_KEY, 0L)
        return com.grayzone.app.policy.MonotonicDeadline.isPending(
            until, startedElapsed, durationMs,
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()
        )
    }

    fun getBreakRemainingMillis(): Long {
        val until = prefs.getLong(PrefsKeys.BREAK_UNTIL, 0L)
        val startedElapsed = prefs.getLong(PrefsKeys.BREAK_STARTED_ELAPSED, 0L)
        val durationMs = prefs.getLong(PrefsKeys.BREAK_DURATION_MS_KEY, 0L)
        return com.grayzone.app.policy.MonotonicDeadline.remainingMs(
            until, startedElapsed, durationMs,
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()
        )
    }

    /** The break can be started once per calendar day. */
    fun canStartBreakToday(): Boolean =
        prefs.getString(PrefsKeys.BREAK_USED_DATE, "") != com.grayzone.app.DateUtils.getCurrentDateKey()

    /**
     * Starts the daily break. Returns false if it was already used today or if an app is currently locked.
     * Ending the break early does not restore today's allowance.
     */
    fun startDailyBreak(): Boolean {
        if (!canStartBreakToday()) return false
        val nowWall = System.currentTimeMillis()
        val monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
        if (com.grayzone.app.isAnyAppLocked(prefs, monitoredApps, nowWall)) {
            com.grayzone.app.GrayzoneLogger.w(com.grayzone.app.LogComponent.SCHEDULE, "Cannot start break while an app is locked.")
            return false
        }
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        prefs.edit()
            .putLong(PrefsKeys.BREAK_UNTIL, nowWall + BREAK_DURATION_MILLIS)
            .putLong(PrefsKeys.BREAK_STARTED_ELAPSED, nowElapsed)
            .putLong(PrefsKeys.BREAK_DURATION_MS_KEY, BREAK_DURATION_MILLIS)
            .putString(PrefsKeys.BREAK_USED_DATE, com.grayzone.app.DateUtils.getCurrentDateKey())
            .apply()
        return true
    }

    fun stopBreak() {
        prefs.edit()
            .putLong(PrefsKeys.BREAK_UNTIL, 0L)
            .putLong(PrefsKeys.BREAK_STARTED_ELAPSED, 0L)
            .putLong(PrefsKeys.BREAK_DURATION_MS_KEY, 0L)
            .apply()
    }

    companion object {
        const val BREAK_DURATION_MILLIS = 60L * 60 * 1000  // 1 hour

        /**
         * Pure schedule-match check: does ANY enabled rule block at [dayOfWeek]
         * (Calendar.SUNDAY..SATURDAY) and [minutesSinceMidnight] (0..1439)?
         *
         * Extracted from [isCurrentlyScheduled] so the matching math can be unit
         * tested deterministically — the caller supplies the wall-clock inputs.
         *
         * ── Overnight windows (e.g. 22:00–06:00) ──
         *   A) minutesSinceMidnight >= startMin  → first half; TODAY must be in the set.
         *   B) minutesSinceMidnight <  endMin    → second half (after midnight);
         *      YESTERDAY (the day the window started) must be in the set.
         *
         * ── DST ── Android's Calendar handles transitions; the minor 1-hour
         * gap/overlap is acceptable and self-resolves after DST ends.
         */
        fun matchesAnyRule(
            rules: List<ScheduleRule>,
            dayOfWeek: Int,
            minutesSinceMidnight: Int
        ): Boolean {
            // Calendar day that was "yesterday" — kept in [1..7].
            val yesterday = ((dayOfWeek - 2 + 7) % 7) + 1
            return rules.any { rule ->
                if (!rule.isEffectivelyEnabled()) return@any false

                val startMin = rule.startHour * 60 + rule.startMinute
                val endMin   = rule.endHour   * 60 + rule.endMinute

                if (startMin <= endMin) {
                    // Same-day range (e.g. 09:00–17:00): today must be in the set.
                    dayOfWeek in rule.daysOfWeek && minutesSinceMidnight in startMin until endMin
                } else {
                    when {
                        minutesSinceMidnight >= startMin -> dayOfWeek in rule.daysOfWeek
                        minutesSinceMidnight <  endMin   -> yesterday in rule.daysOfWeek
                        else                             -> false
                    }
                }
            }
        }

        /** Day-of-week constants matching Calendar for UI display. */
        val DAY_NAMES = mapOf(
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun"
        )
        val WEEKDAYS = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val WEEKEND = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        val ALL_DAYS = WEEKDAYS + WEEKEND
    }
}
