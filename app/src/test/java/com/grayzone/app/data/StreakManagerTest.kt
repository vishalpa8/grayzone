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
}
