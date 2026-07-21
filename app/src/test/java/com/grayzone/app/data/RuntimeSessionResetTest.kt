package com.grayzone.app.data

import com.grayzone.app.DateUtils
import com.grayzone.app.FakeSharedPreferences
import com.grayzone.app.PrefsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RuntimeSessionResetTest {

    private val pkg = "com.test.app"

    private fun midnightToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `no reset when already reset today`() {
        val today = DateUtils.formatDateKey(midnightToday())
        val prefs = FakeSharedPreferences(
            mapOf(
                PrefsKeys.DAILY_RESET_DATE + "runtime" to today,
                PrefsKeys.MONITORED_APPS to setOf(pkg),
                PrefsKeys.ACTIVE_UNTIL + pkg to 999L
            )
        )
        assertFalse(RuntimeSessionReset.resetIfNeeded(prefs, midnightToday()))
        assertEquals(999L, prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L))
    }

    @Test
    fun `reset clears active locked and remaining for monitored apps`() {
        val todayMs = midnightToday()
        val yesterday = DateUtils.formatDateKey(todayMs - 24L * 60 * 60 * 1000L)
        val prefs = FakeSharedPreferences(
            mapOf(
                PrefsKeys.DAILY_RESET_DATE + "runtime" to yesterday,
                PrefsKeys.MONITORED_APPS to mutableSetOf(pkg),
                PrefsKeys.ACTIVE_UNTIL + pkg to 111L,
                PrefsKeys.LOCKED_UNTIL + pkg to 222L,
                PrefsKeys.REMAINING_MILLIS + pkg to 333L,
                // Budget keys must survive — budgets reset independently.
                PrefsKeys.DAILY_USED_MILLIS + pkg to 444L
            )
        )

        assertTrue(RuntimeSessionReset.resetIfNeeded(prefs, todayMs))
        assertEquals(0L, prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L))
        assertEquals(0L, prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L))
        assertEquals(0L, prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L))
        assertFalse(prefs.contains(PrefsKeys.ACTIVE_UNTIL + pkg))
        assertEquals(444L, prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L))
        assertEquals(DateUtils.formatDateKey(todayMs), prefs.getString(PrefsKeys.DAILY_RESET_DATE + "runtime", ""))
    }

    @Test
    fun `empty monitored set still stamps the reset date`() {
        val todayMs = midnightToday()
        val prefs = FakeSharedPreferences(
            mapOf(PrefsKeys.DAILY_RESET_DATE + "runtime" to "2000-01-01")
        )
        assertTrue(RuntimeSessionReset.resetIfNeeded(prefs, todayMs))
        assertEquals(DateUtils.formatDateKey(todayMs), prefs.getString(PrefsKeys.DAILY_RESET_DATE + "runtime", ""))
    }
}
