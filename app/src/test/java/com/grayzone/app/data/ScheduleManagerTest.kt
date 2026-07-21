package com.grayzone.app.data

import android.content.Context
import com.grayzone.app.DateUtils
import com.grayzone.app.FakeSharedPreferences
import com.grayzone.app.PrefsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.Calendar

/**
 * Lifecycle coverage for the three ScheduleManager-backed features:
 *   - Deep Work / Quick Focus  (startFocusMode / isFocusModeActive / remaining)
 *   - Daily Break              (once-per-day allowance, no early-restore)
 *   - Manage Schedule          (rule CRUD + isCurrentlyScheduled wiring)
 *
 * Uses an in-memory SharedPreferences behind a mocked Context so the real
 * manager code runs without Robolectric.
 */
class ScheduleManagerTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var manager: ScheduleManager

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(prefs)
        manager = ScheduleManager(context)
    }

    // ─── Deep Work / Focus Mode ──────────────────────────────────────────────

    @Test
    fun `focus mode is inactive by default`() {
        assertFalse(manager.isFocusModeActive())
        assertEquals(0L, manager.getFocusModeRemainingMillis())
    }

    @Test
    fun `starting focus mode activates it with a positive remainder`() {
        manager.startFocusMode(30)
        assertTrue(manager.isFocusModeActive())
        val remaining = manager.getFocusModeRemainingMillis()
        assertTrue(remaining > 0)
        assertTrue(remaining <= 30 * 60 * 1000L)
    }

    @Test
    fun `stopping focus mode deactivates it`() {
        manager.startFocusMode(30)
        manager.stopFocusMode()
        assertFalse(manager.isFocusModeActive())
        assertEquals(0L, manager.getFocusModeRemainingMillis())
    }

    @Test
    fun `expired focus window is inactive and self-clears the active flag`() {
        // Simulate a stale focus window left over from the past.
        prefs.edit()
            .putBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, true)
            .putLong(PrefsKeys.FOCUS_MODE_UNTIL, System.currentTimeMillis() - 1_000L)
            .apply()

        assertFalse(manager.isFocusModeActive())
        // Side effect: the stale active flag is reset.
        assertFalse(prefs.getBoolean(PrefsKeys.FOCUS_MODE_ACTIVE, true))
    }

    // ─── Daily Break ─────────────────────────────────────────────────────────

    @Test
    fun `break is available and inactive by default`() {
        assertTrue(manager.canStartBreakToday())
        assertFalse(manager.isBreakActive())
    }

    @Test
    fun `starting the daily break activates it and consumes today's allowance`() {
        assertTrue(manager.startDailyBreak())
        assertTrue(manager.isBreakActive())

        val remaining = manager.getBreakRemainingMillis()
        assertTrue(remaining > 0)
        assertTrue(remaining <= ScheduleManager.BREAK_DURATION_MILLIS)

        assertFalse("break can only start once per day", manager.canStartBreakToday())
    }

    @Test
    fun `break cannot be started twice in the same day`() {
        assertTrue(manager.startDailyBreak())
        assertFalse(manager.startDailyBreak())
    }

    @Test
    fun `ending the break early does not restore today's allowance`() {
        assertTrue(manager.startDailyBreak())
        manager.stopBreak()

        assertFalse(manager.isBreakActive())
        assertFalse(manager.canStartBreakToday())
        assertFalse("no second break after stopping early", manager.startDailyBreak())
    }

    @Test
    fun `break used on a previous day is available again today`() {
        // Pretend the break was used yesterday.
        prefs.edit().putString(PrefsKeys.BREAK_USED_DATE, "2000-01-01").apply()
        assertTrue(manager.canStartBreakToday())
        assertTrue(manager.startDailyBreak())
        // And now it records today's key.
        assertEquals(DateUtils.getCurrentDateKey(), prefs.getString(PrefsKeys.BREAK_USED_DATE, ""))
    }

    // ─── Schedule rule CRUD ──────────────────────────────────────────────────

    @Test
    fun `rules are empty by default`() {
        assertTrue(manager.getScheduleRules().isEmpty())
    }

    @Test
    fun `add persists and round-trips a rule`() {
        val r = ScheduleRule(name = "Work", startHour = 9, startMinute = 0, endHour = 17, endMinute = 0, daysOfWeek = setOf(Calendar.MONDAY))
        manager.addRule(r)
        val rules = manager.getScheduleRules()
        assertEquals(1, rules.size)
        assertEquals("Work", rules[0].name)
        assertEquals(setOf(Calendar.MONDAY), rules[0].daysOfWeek)
    }

    @Test
    fun `toggle flips the enabled flag of the target rule only`() {
        val a = ScheduleRule(name = "A", startHour = 9, startMinute = 0, endHour = 10, endMinute = 0, daysOfWeek = setOf(Calendar.MONDAY))
        val b = ScheduleRule(name = "B", startHour = 11, startMinute = 0, endHour = 12, endMinute = 0, daysOfWeek = setOf(Calendar.TUESDAY))
        manager.addRule(a)
        manager.addRule(b)

        manager.toggleRule(a.id, false)
        val rules = manager.getScheduleRules().associateBy { it.id }
        assertFalse(rules.getValue(a.id).enabled)
        assertTrue(rules.getValue(b.id).enabled)
    }

    @Test
    fun `remove deletes only the target rule`() {
        val a = ScheduleRule(name = "A", startHour = 9, startMinute = 0, endHour = 10, endMinute = 0, daysOfWeek = setOf(Calendar.MONDAY))
        val b = ScheduleRule(name = "B", startHour = 11, startMinute = 0, endHour = 12, endMinute = 0, daysOfWeek = setOf(Calendar.TUESDAY))
        manager.addRule(a)
        manager.addRule(b)

        manager.removeRule(a.id)
        val rules = manager.getScheduleRules()
        assertEquals(1, rules.size)
        assertEquals(b.id, rules[0].id)
    }

    @Test
    fun `corrupt schedule json degrades gracefully to an empty list`() {
        prefs.edit().putString(PrefsKeys.SCHEDULE_RULES_JSON, "{not valid json").apply()
        assertTrue(manager.getScheduleRules().isEmpty())
    }

    // ─── isCurrentlyScheduled wiring (deterministic branches) ────────────────

    @Test
    fun `nothing scheduled when there are no rules and no focus`() {
        assertFalse(manager.isCurrentlyScheduled())
    }

    @Test
    fun `active focus mode forces currently-scheduled regardless of rules`() {
        manager.startFocusMode(10)
        assertTrue(manager.isCurrentlyScheduled())
    }

    @Test
    fun `a disabled rule alone never marks currently-scheduled`() {
        val r = ScheduleRule(
            name = "AllDay", startHour = 0, startMinute = 0, endHour = 23, endMinute = 59,
            daysOfWeek = ScheduleManager.ALL_DAYS, enabled = false
        )
        manager.addRule(r)
        assertFalse(manager.isCurrentlyScheduled())
    }
}
