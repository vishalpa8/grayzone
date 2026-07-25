package com.grayzone.app.data

import android.content.Context
import com.grayzone.app.FakeSharedPreferences
import com.grayzone.app.PrefsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class StreakManagerTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var manager: StreakManager

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(prefs)
        manager = StreakManager(context)
    }

    @Test
    fun `defaults are zero`() {
        assertEquals(0, manager.getCurrentStreak())
        assertEquals(0, manager.getLongestStreak())
        assertEquals(0, manager.getTotalSessionsBlocked())
        assertEquals(0, manager.getTotalTimeSavedMins())
    }

    @Test
    fun `blocked session increments counters and unlocks first resist`() {
        manager.recordBlockedSession(lockoutMinutes = 30)
        assertEquals(1, manager.getTotalSessionsBlocked())
        assertEquals(30, manager.getTotalTimeSavedMins())
        assertTrue(manager.getAchievements().first { it.id == "first_resist" }.isUnlocked)
    }

    @Test
    fun `successful consecutive days grow the streak`() {
        manager.checkDailyStreakForDate("2026-07-19", stayedUnderBudget = true)
        manager.checkDailyStreakForDate("2026-07-20", stayedUnderBudget = true)
        manager.checkDailyStreakForDate("2026-07-21", stayedUnderBudget = true)
        assertEquals(3, manager.getCurrentStreak())
        assertEquals(3, manager.getLongestStreak())
        assertTrue(manager.getAchievements().first { it.id == "three_day_streak" }.isUnlocked)
    }

    @Test
    fun `failing a day resets current streak but keeps longest`() {
        manager.checkDailyStreakForDate("2026-07-19", true)
        manager.checkDailyStreakForDate("2026-07-20", true)
        manager.checkDailyStreakForDate("2026-07-21", false)
        assertEquals(0, manager.getCurrentStreak())
        assertEquals(2, manager.getLongestStreak())
    }

    @Test
    fun `gap in dates restarts streak at one`() {
        manager.checkDailyStreakForDate("2026-07-18", true)
        manager.checkDailyStreakForDate("2026-07-21", true) // skipped 19+20
        assertEquals(1, manager.getCurrentStreak())
    }

    @Test
    fun `same completed date is idempotent`() {
        manager.checkDailyStreakForDate("2026-07-21", true)
        manager.checkDailyStreakForDate("2026-07-21", true)
        assertEquals(1, manager.getCurrentStreak())
        assertEquals("2026-07-21", prefs.getString(PrefsKeys.STREAK_LAST_DATE, null))
    }

    @Test
    fun `hour saved unlocks at 60 minutes`() {
        manager.recordBlockedSession(60)
        assertTrue(manager.getAchievements().first { it.id == "hour_saved" }.isUnlocked)
        assertFalse(manager.getAchievements().first { it.id == "ten_hours_saved" }.isUnlocked)
    }

    @Test
    fun `corrupt achievements json degrades to all locked`() {
        prefs.edit().putString(PrefsKeys.ACHIEVEMENTS_JSON, "{bad").apply()
        assertTrue(manager.getAchievements().all { !it.isUnlocked })
    }

    // ── pendingDates catch-up window ────────────────────────────────────────

    @Test
    fun `pendingDates returns only the through day on first run`() {
        assertEquals(listOf("2026-07-21"), StreakManager.pendingDates(null, "2026-07-21"))
    }

    @Test
    fun `pendingDates fills every missed day inclusively`() {
        assertEquals(
            listOf("2026-07-19", "2026-07-20", "2026-07-21"),
            StreakManager.pendingDates("2026-07-18", "2026-07-21")
        )
    }

    @Test
    fun `pendingDates is empty when already up to date`() {
        assertTrue(StreakManager.pendingDates("2026-07-21", "2026-07-21").isEmpty())
        assertTrue(StreakManager.pendingDates("2026-07-22", "2026-07-21").isEmpty())
    }

    @Test
    fun `pendingDates caps very large gaps to maxDays`() {
        val dates = StreakManager.pendingDates("2020-01-01", "2026-07-21", maxDays = 5)
        assertEquals(5, dates.size)
        assertEquals("2026-07-21", dates.last())
    }

    // ── evaluatePendingDays uses usage source of truth + catch-up ───────────

    @Test
    fun `missed over-budget day breaks the streak on catch-up`() = kotlinx.coroutines.runBlocking {
        prefs.edit().putString(PrefsKeys.STREAK_LAST_DATE, "2026-07-18").apply()
        prefs.edit().putInt(PrefsKeys.CURRENT_STREAK, 5).apply()

        val pkg = "com.test.app"
        val budgets = mapOf(pkg to 30) // 30-minute daily budget

        // 07-19 under budget, 07-20 OVER budget (should reset), 07-21 under budget.
        manager.evaluatePendingDays("2026-07-21", budgets) { dateKey ->
            when (dateKey) {
                "2026-07-20" -> mapOf(pkg to 45 * 60_000L) // over the 30-min budget
                else -> mapOf(pkg to 5 * 60_000L)          // comfortably under
            }
        }

        // 07-20 zeroes the streak, then 07-21 starts a fresh streak of 1.
        assertEquals(1, manager.getCurrentStreak())
    }

    @Test
    fun `evaluatePendingDays is idempotent within the same day`() = kotlinx.coroutines.runBlocking {
        val pkg = "com.test.app"
        val budgets = mapOf(pkg to 30)
        val usage: suspend (String) -> Map<String, Long> = { mapOf(pkg to 5 * 60_000L) }

        manager.evaluatePendingDays("2026-07-21", budgets, usedMsForDate = usage)
        val afterFirst = manager.getCurrentStreak()
        manager.evaluatePendingDays("2026-07-21", budgets, usedMsForDate = usage)

        assertEquals(afterFirst, manager.getCurrentStreak())
    }
}
