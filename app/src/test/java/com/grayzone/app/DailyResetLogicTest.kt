package com.grayzone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DailyResetLogicTest {
    @Test
    fun `reset is triggered once at midnight for a new day`() {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val yesterdayKey = DateUtils.formatDateKey(today - 24L * 60 * 60 * 1000L)

        assertTrue(DateUtils.shouldResetDailyRuntimeState(today, yesterdayKey))
        assertFalse(DateUtils.shouldResetDailyRuntimeState(today, DateUtils.formatDateKey(today)))
    }
}
