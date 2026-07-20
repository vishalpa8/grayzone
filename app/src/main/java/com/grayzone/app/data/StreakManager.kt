package com.grayzone.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.grayzone.app.PrefsKeys
import java.text.SimpleDateFormat
import java.util.*

/**
 * Achievement definition with unlock criteria.
 */
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,         // Emoji
    val unlockedDate: String? = null  // null = locked
) {
    val isUnlocked: Boolean get() = unlockedDate != null
}

/**
 * Manages streak tracking and gamification achievements.
 */
class StreakManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun getCurrentStreak(): Int = prefs.getInt(PrefsKeys.CURRENT_STREAK, 0)
    fun getLongestStreak(): Int = prefs.getInt(PrefsKeys.LONGEST_STREAK, 0)
    fun getTotalSessionsBlocked(): Int = prefs.getInt(PrefsKeys.TOTAL_SESSIONS_BLOCKED, 0)
    fun getTotalTimeSavedMins(): Int = prefs.getInt(PrefsKeys.TOTAL_TIME_SAVED_MINS, 0)

    /**
     * Called when a user successfully resists an app (closes friction without opening).
     * Increments session blocked count and time saved.
     */
    fun recordBlockedSession(lockoutMinutes: Int) {
        val blocked = getTotalSessionsBlocked() + 1
        val saved = getTotalTimeSavedMins() + lockoutMinutes
        prefs.edit()
            .putInt(PrefsKeys.TOTAL_SESSIONS_BLOCKED, blocked)
            .putInt(PrefsKeys.TOTAL_TIME_SAVED_MINS, saved)
            .apply()
        checkAndUnlockAchievements()
    }

    /**
     * Records a completed calendar day. The current in-progress day is never awarded.
     */
    fun checkDailyStreak(stayedUnderBudget: Boolean) {
        checkDailyStreakForDate(previousDateKey(dateFormat.format(Date())), stayedUnderBudget)
    }

    fun checkDailyStreakForDate(completedDateKey: String, stayedUnderBudget: Boolean) {
        val lastDate = prefs.getString(PrefsKeys.STREAK_LAST_DATE, null)
        if (lastDate == completedDateKey) return

        val currentStreak = getCurrentStreak()
        val previousCompletedDate = previousDateKey(completedDateKey)

        val newStreak = if (stayedUnderBudget) {
            if (lastDate == previousCompletedDate || lastDate == null) currentStreak + 1 else 1
        } else {
            0
        }

        val longest = maxOf(getLongestStreak(), newStreak)

        prefs.edit()
            .putInt(PrefsKeys.CURRENT_STREAK, newStreak)
            .putInt(PrefsKeys.LONGEST_STREAK, longest)
            .putString(PrefsKeys.STREAK_LAST_DATE, completedDateKey)
            .apply()

        checkAndUnlockAchievements()
    }

    private fun previousDateKey(dateKey: String): String {
        val cal = Calendar.getInstance()
        cal.time = dateFormat.parse(dateKey) ?: Date()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return dateFormat.format(cal.time)
    }

    // ── Achievements ──────────────────────────────────────────────────────

    fun getAchievements(): List<Achievement> {
        val json = prefs.getString(PrefsKeys.ACHIEVEMENTS_JSON, null)
        val existing: Map<String, String> = if (json != null) {
            try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        return ALL_ACHIEVEMENTS.map { def ->
            def.copy(unlockedDate = existing[def.id])
        }
    }

    private fun unlockAchievement(id: String) {
        val json = prefs.getString(PrefsKeys.ACHIEVEMENTS_JSON, null)
        val existing: MutableMap<String, String> = if (json != null) {
            try {
                val type = object : TypeToken<MutableMap<String, String>>() {}.type
                gson.fromJson(json, type) ?: mutableMapOf()
            } catch (_: Exception) { mutableMapOf() }
        } else mutableMapOf()

        if (id !in existing) {
            existing[id] = dateFormat.format(Date())
            prefs.edit().putString(PrefsKeys.ACHIEVEMENTS_JSON, gson.toJson(existing)).apply()
        }
    }

    private fun checkAndUnlockAchievements() {
        val streak = getCurrentStreak()
        val blocked = getTotalSessionsBlocked()
        val saved = getTotalTimeSavedMins()

        if (streak >= 1) unlockAchievement("first_day")
        if (streak >= 3) unlockAchievement("three_day_streak")
        if (streak >= 7) unlockAchievement("week_warrior")
        if (streak >= 30) unlockAchievement("monthly_master")
        if (blocked >= 1) unlockAchievement("first_resist")
        if (blocked >= 10) unlockAchievement("ten_resists")
        if (blocked >= 50) unlockAchievement("fifty_resists")
        if (blocked >= 100) unlockAchievement("century_club")
        if (saved >= 60) unlockAchievement("hour_saved")
        if (saved >= 600) unlockAchievement("ten_hours_saved")
    }

    companion object {
        val ALL_ACHIEVEMENTS = listOf(
            Achievement("first_day", "First Day", "Complete your first day under budget", "🌱"),
            Achievement("three_day_streak", "Three-Peat", "3-day streak under budget", "🔥"),
            Achievement("week_warrior", "Week Warrior", "7-day streak under budget", "⚔️"),
            Achievement("monthly_master", "Monthly Master", "30-day streak under budget", "👑"),
            Achievement("first_resist", "First Resist", "Close a friction screen without opening the app", "🛡️"),
            Achievement("ten_resists", "Willpower 10", "Resist 10 app openings", "💪"),
            Achievement("fifty_resists", "Fifty Strong", "Resist 50 app openings", "🏆"),
            Achievement("century_club", "Century Club", "Resist 100 app openings", "💎"),
            Achievement("hour_saved", "Hour Reclaimed", "Save a total of 1 hour from distractions", "⏰"),
            Achievement("ten_hours_saved", "Time Master", "Save 10 hours total from distractions", "🌟"),
        )
    }
}
