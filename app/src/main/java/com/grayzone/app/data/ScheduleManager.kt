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
    val enabled: Boolean = true
)

/**
 * Manages schedule rules and focus mode for blocking apps at certain times.
 */
class ScheduleManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getScheduleRules(): List<ScheduleRule> {
        val json = prefs.getString(PrefsKeys.SCHEDULE_RULES_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ScheduleRule>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun saveScheduleRules(rules: List<ScheduleRule>) {
        prefs.edit().putString(PrefsKeys.SCHEDULE_RULES_JSON, gson.toJson(rules)).apply()
    }

    fun addRule(rule: ScheduleRule) {
        val rules = getScheduleRules().toMutableList()
        rules.add(rule)
        saveScheduleRules(rules)
    }

    fun removeRule(ruleId: String) {
        val rules = getScheduleRules().filter { it.id != ruleId }
        saveScheduleRules(rules)
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        val rules = getScheduleRules().map {
            if (it.id == ruleId) it.copy(enabled = enabled) else it
        }
        saveScheduleRules(rules)
    }

    /**
     * Returns true if any active schedule rule blocks apps right now.
     */
    fun isCurrentlyScheduled(): Boolean {
        // Check quick focus mode first
        val focusUntil = prefs.getLong(PrefsKeys.FOCUS_MODE_UNTIL, 0L)
        if (System.currentTimeMillis() < focusUntil) return true

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return matchesAnyRule(getScheduleRules(), dayOfWeek, nowMinutes)
    }

    // ── Quick Focus Mode ──────────────────────────────────────────────────

    fun startFocusMode(durationMinutes: Int) {
        val until = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        prefs.edit()
            .putBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, true)
            .putLong(PrefsKeys.FOCUS_MODE_UNTIL, until)
            .apply()
    }

    fun stopFocusMode() {
        prefs.edit()
            .putBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, false)
            .putLong(PrefsKeys.FOCUS_MODE_UNTIL, 0L)
            .apply()
    }

    fun isFocusModeActive(): Boolean {
        val until = prefs.getLong(PrefsKeys.FOCUS_MODE_UNTIL, 0L)
        if (System.currentTimeMillis() >= until) {
            if (prefs.getBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, false)) {
                stopFocusMode()
            }
            return false
        }
        return true
    }

    fun getFocusModeRemainingMillis(): Long {
        val until = prefs.getLong(PrefsKeys.FOCUS_MODE_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // ── Daily Break ────────────────────────────────────────────────────────
    // One hour per day during which nothing is locked: no friction screens,
    // no lockouts, no schedule blocks. Usable once per calendar day.

    fun isBreakActive(): Boolean =
        System.currentTimeMillis() < prefs.getLong(PrefsKeys.BREAK_UNTIL, 0L)

    fun getBreakRemainingMillis(): Long =
        (prefs.getLong(PrefsKeys.BREAK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** The break can be started once per calendar day. */
    fun canStartBreakToday(): Boolean =
        prefs.getString(PrefsKeys.BREAK_USED_DATE, "") != com.grayzone.app.DateUtils.getCurrentDateKey()

    /**
     * Starts the daily break. Returns false if it was already used today.
     * Ending the break early does not restore today's allowance.
     */
    fun startDailyBreak(): Boolean {
        if (!canStartBreakToday()) return false
        prefs.edit()
            .putLong(PrefsKeys.BREAK_UNTIL, System.currentTimeMillis() + BREAK_DURATION_MILLIS)
            .putString(PrefsKeys.BREAK_USED_DATE, com.grayzone.app.DateUtils.getCurrentDateKey())
            .apply()
        return true
    }

    fun stopBreak() {
        prefs.edit().putLong(PrefsKeys.BREAK_UNTIL, 0L).apply()
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
                if (!rule.enabled) return@any false

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
