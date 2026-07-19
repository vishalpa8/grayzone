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

        // Calendar day that was "yesterday" — needed for overnight rule check below.
        val yesterday = ((dayOfWeek - 2 + 7) % 7) + 1  // keeps result in [1..7]

        return getScheduleRules().any { rule ->
            if (!rule.enabled) return@any false

            val startMin = rule.startHour * 60 + rule.startMinute
            val endMin   = rule.endHour   * 60 + rule.endMinute

            if (startMin <= endMin) {
                // ── Same-day range (e.g. 09:00–17:00) ─────────────────────
                // Today must be in the rule's day set.
                dayOfWeek in rule.daysOfWeek && nowMinutes in startMin until endMin
            } else {
                // ── Overnight range (e.g. 22:00–06:00) ────────────────────
                // Two cases:
                //
                //   A) nowMinutes >= startMin  →  we are in the FIRST half of the
                //      overnight window (e.g. 22:30 on Monday). The rule day set
                //      must contain TODAY.
                //
                //   B) nowMinutes < endMin     →  we are in the SECOND half of the
                //      overnight window (e.g. 02:00 on Tuesday, still inside the
                //      Mon-night rule). The rule day set must contain YESTERDAY
                //      (the day the window started).
                //
                // Bug before fix: case B used `dayOfWeek in rule.daysOfWeek` which
                // checked Tuesday — the wrong day — so a Mon–Fri bedtime rule at
                // 22:00–06:00 would fail to block at 02:00 Tue through Sat morning.
                when {
                    nowMinutes >= startMin -> dayOfWeek in rule.daysOfWeek
                    nowMinutes < endMin    -> yesterday in rule.daysOfWeek
                    else                   -> false
                }
            }
        }
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

    companion object {
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
