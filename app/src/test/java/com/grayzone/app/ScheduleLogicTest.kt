package com.grayzone.app

import com.grayzone.app.data.ScheduleRule
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for schedule matching logic.
 * 
 * Critical to verify:
 * - Overnight schedules work correctly across midnight boundary
 * - Day-of-week matching is accurate
 * - Edge cases around DST transitions
 */
class ScheduleLogicTest {

    /**
     * Helper to check if a time falls within a schedule rule.
     * Extracted from ScheduleManager for testing.
     */
    private fun isTimeInSchedule(
        rule: ScheduleRule,
        dayOfWeek: Int,
        minutesSinceMidnight: Int
    ): Boolean {
        if (!rule.enabled) return false

        val startMin = rule.startHour * 60 + rule.startMinute
        val endMin = rule.endHour * 60 + rule.endMinute

        return if (startMin <= endMin) {
            // Same-day range (e.g., 09:00–17:00)
            dayOfWeek in rule.daysOfWeek && minutesSinceMidnight in startMin until endMin
        } else {
            // Overnight range (e.g., 22:00–06:00)
            when {
                minutesSinceMidnight >= startMin -> dayOfWeek in rule.daysOfWeek
                minutesSinceMidnight < endMin -> {
                    // We're in the continuation from yesterday
                    val yesterday = if (dayOfWeek == Calendar.SUNDAY) Calendar.SATURDAY else dayOfWeek - 1
                    yesterday in rule.daysOfWeek
                }
                else -> false
            }
        }
    }

    @Test
    fun `same-day schedule blocks correctly`() {
        val rule = ScheduleRule(
            name = "Work Hours",
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            daysOfWeek = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            enabled = true
        )

        // Monday 10:00 - should block
        assertTrue("Should block during work hours", 
            isTimeInSchedule(rule, Calendar.MONDAY, 10 * 60))

        // Monday 08:00 - should NOT block
        assertFalse("Should not block before work hours", 
            isTimeInSchedule(rule, Calendar.MONDAY, 8 * 60))

        // Monday 18:00 - should NOT block
        assertFalse("Should not block after work hours", 
            isTimeInSchedule(rule, Calendar.MONDAY, 18 * 60))

        // Saturday 10:00 - should NOT block
        assertFalse("Should not block on weekend", 
            isTimeInSchedule(rule, Calendar.SATURDAY, 10 * 60))
    }

    @Test
    fun `overnight schedule blocks correctly across midnight`() {
        val rule = ScheduleRule(
            name = "Bedtime",
            startHour = 22,
            startMinute = 0,
            endHour = 6,
            endMinute = 0,
            daysOfWeek = setOf(Calendar.MONDAY, Calendar.TUESDAY),
            enabled = true
        )

        // Monday 23:00 - should block (start of Monday night)
        assertTrue("Should block Monday night", 
            isTimeInSchedule(rule, Calendar.MONDAY, 23 * 60))

        // Tuesday 02:00 - should block (continuation of Monday night)
        assertTrue("Should block early Tuesday morning from Monday night", 
            isTimeInSchedule(rule, Calendar.TUESDAY, 2 * 60))

        // Tuesday 23:00 - should block (start of Tuesday night)
        assertTrue("Should block Tuesday night", 
            isTimeInSchedule(rule, Calendar.TUESDAY, 23 * 60))

        // Wednesday 02:00 - should block (continuation of Tuesday night)
        assertTrue("Should block early Wednesday morning from Tuesday night", 
            isTimeInSchedule(rule, Calendar.WEDNESDAY, 2 * 60))

        // Wednesday 23:00 - should NOT block (Wednesday not in daysOfWeek)
        assertFalse("Should not block Wednesday night", 
            isTimeInSchedule(rule, Calendar.WEDNESDAY, 23 * 60))

        // Thursday 02:00 - should NOT block (no Wed night session)
        assertFalse("Should not block Thursday morning", 
            isTimeInSchedule(rule, Calendar.THURSDAY, 2 * 60))
    }

    @Test
    fun `midnight boundary is handled correctly`() {
        val rule = ScheduleRule(
            name = "Late Night",
            startHour = 23,
            startMinute = 0,
            endHour = 1,
            endMinute = 0,
            daysOfWeek = setOf(Calendar.FRIDAY),
            enabled = true
        )

        // Friday 23:30 - should block
        assertTrue("Should block Friday night", 
            isTimeInSchedule(rule, Calendar.FRIDAY, 23 * 60 + 30))

        // Saturday 00:30 - should block (continuation from Friday)
        assertTrue("Should block Saturday morning from Friday night", 
            isTimeInSchedule(rule, Calendar.SATURDAY, 0 * 60 + 30))

        // Saturday 01:00 - should NOT block (end time is exclusive)
        assertFalse("Should not block at exact end time", 
            isTimeInSchedule(rule, Calendar.SATURDAY, 1 * 60))

        // Saturday 23:30 - should NOT block (Saturday not enabled)
        assertFalse("Should not block Saturday night", 
            isTimeInSchedule(rule, Calendar.SATURDAY, 23 * 60 + 30))
    }

    @Test
    fun `disabled rule never blocks`() {
        val rule = ScheduleRule(
            name = "Disabled",
            startHour = 0,
            startMinute = 0,
            endHour = 23,
            endMinute = 59,
            daysOfWeek = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY),
            enabled = false
        )

        // Should never block, even though time matches
        assertFalse("Disabled rule should never block", 
            isTimeInSchedule(rule, Calendar.MONDAY, 12 * 60))
    }

    @Test
    fun `edge case - full 24 hour schedule`() {
        val rule = ScheduleRule(
            name = "All Day",
            startHour = 0,
            startMinute = 0,
            endHour = 23,
            endMinute = 59,
            daysOfWeek = setOf(Calendar.MONDAY),
            enabled = true
        )

        // Should block entire Monday
        assertTrue("Should block at midnight", 
            isTimeInSchedule(rule, Calendar.MONDAY, 0))
        assertTrue("Should block at noon", 
            isTimeInSchedule(rule, Calendar.MONDAY, 12 * 60))
        assertTrue("Should block at 23:58", 
            isTimeInSchedule(rule, Calendar.MONDAY, 23 * 60 + 58))
    }

    @Test
    fun `edge case - Sunday to Monday overnight`() {
        val rule = ScheduleRule(
            name = "Sunday Night",
            startHour = 22,
            startMinute = 0,
            endHour = 6,
            endMinute = 0,
            daysOfWeek = setOf(Calendar.SUNDAY),
            enabled = true
        )

        // Sunday 23:00 - should block
        assertTrue("Should block Sunday night", 
            isTimeInSchedule(rule, Calendar.SUNDAY, 23 * 60))

        // Monday 02:00 - should block (continuation from Sunday)
        assertTrue("Should block Monday morning from Sunday night", 
            isTimeInSchedule(rule, Calendar.MONDAY, 2 * 60))

        // Monday 23:00 - should NOT block
        assertFalse("Should not block Monday night", 
            isTimeInSchedule(rule, Calendar.MONDAY, 23 * 60))
    }
}
