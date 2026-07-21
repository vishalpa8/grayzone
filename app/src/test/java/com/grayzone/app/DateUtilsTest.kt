package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateUtilsTest {

    @Test
    fun `formatDateKey is yyyy-MM-dd`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 21, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("2026-07-21", DateUtils.formatDateKey(cal.timeInMillis))
    }

    @Test
    fun `isSameDay is true within a day and false across midnight`() {
        val day = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 21, 1, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(DateUtils.isSameDay(day, day + 10 * 60 * 60 * 1000L))
        assertFalse(DateUtils.isSameDay(day, day + 24L * 60 * 60 * 1000L))
    }

    @Test
    fun `shouldReset when last key differs from now's date`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 21, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(DateUtils.shouldResetDailyRuntimeState(now, "2026-07-20"))
        assertFalse(DateUtils.shouldResetDailyRuntimeState(now, "2026-07-21"))
        assertTrue(DateUtils.shouldResetDailyRuntimeState(now, ""))
    }

    @Test
    fun `millisUntilMidnight is within one day`() {
        val ms = DateUtils.millisUntilMidnight()
        assertTrue(ms in 1 until 24L * 60 * 60 * 1000L)
    }

    @Test
    fun `yesterday date key differs from today`() {
        assertTrue(DateUtils.getYesterdayDateKey() != DateUtils.getCurrentDateKey())
    }
}
