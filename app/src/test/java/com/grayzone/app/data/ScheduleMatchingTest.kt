package com.grayzone.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Aggressive coverage of the REAL schedule-matching engine
 * (ScheduleManager.matchesAnyRule) — same-day, overnight, day-of-week wrap,
 * and every boundary. Unlike the older ScheduleLogicTest (which tested a copy),
 * this pins the production function so it can't silently drift.
 */
class ScheduleMatchingTest {

    private val allDays = setOf(
        Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
    )

    private fun rule(
        startH: Int, startM: Int, endH: Int, endM: Int,
        days: Set<Int>, enabled: Boolean = true
    ) = ScheduleRule(
        name = "r", startHour = startH, startMinute = startM,
        endHour = endH, endMinute = endM, daysOfWeek = days, enabled = enabled
    )

    private fun match(rules: List<ScheduleRule>, day: Int, minutes: Int) =
        ScheduleManager.matchesAnyRule(rules, day, minutes)

    // ─── Empty / disabled ────────────────────────────────────────────────────

    @Test
    fun `no rules never blocks`() {
        assertFalse(match(emptyList(), Calendar.MONDAY, 12 * 60))
    }

    @Test
    fun `disabled rule never blocks even when time and day match`() {
        val r = rule(0, 0, 23, 59, allDays, enabled = false)
        assertFalse(match(listOf(r), Calendar.MONDAY, 12 * 60))
    }

    // ─── Same-day range ──────────────────────────────────────────────────────

    @Test
    fun `same-day block within window`() {
        val r = rule(9, 0, 17, 0, setOf(Calendar.MONDAY))
        assertTrue(match(listOf(r), Calendar.MONDAY, 10 * 60))
    }

    @Test
    fun `same-day is inclusive of start minute`() {
        val r = rule(9, 0, 17, 0, setOf(Calendar.MONDAY))
        assertTrue(match(listOf(r), Calendar.MONDAY, 9 * 60))
    }

    @Test
    fun `same-day is exclusive of end minute`() {
        val r = rule(9, 0, 17, 0, setOf(Calendar.MONDAY))
        assertFalse(match(listOf(r), Calendar.MONDAY, 17 * 60))
    }

    @Test
    fun `same-day does not block on an unlisted day`() {
        val r = rule(9, 0, 17, 0, setOf(Calendar.MONDAY))
        assertFalse(match(listOf(r), Calendar.TUESDAY, 10 * 60))
    }

    @Test
    fun `zero-length window (start equals end) never blocks`() {
        val r = rule(9, 0, 9, 0, allDays)
        assertFalse(match(listOf(r), Calendar.MONDAY, 9 * 60))
    }

    // ─── Overnight range ─────────────────────────────────────────────────────

    @Test
    fun `overnight first half blocks on the start day`() {
        val r = rule(22, 0, 6, 0, setOf(Calendar.MONDAY))
        assertTrue(match(listOf(r), Calendar.MONDAY, 23 * 60))
    }

    @Test
    fun `overnight second half blocks the following morning via yesterday membership`() {
        val r = rule(22, 0, 6, 0, setOf(Calendar.MONDAY))
        // Tuesday 02:00 is inside the Monday-night window.
        assertTrue(match(listOf(r), Calendar.TUESDAY, 2 * 60))
    }

    @Test
    fun `overnight morning does not block when yesterday is not in the set`() {
        val r = rule(22, 0, 6, 0, setOf(Calendar.MONDAY))
        // Wednesday 02:00 would require a Tuesday-night window — not configured.
        assertFalse(match(listOf(r), Calendar.WEDNESDAY, 2 * 60))
    }

    @Test
    fun `overnight is exclusive of the end minute`() {
        val r = rule(22, 0, 6, 0, setOf(Calendar.MONDAY))
        assertFalse(match(listOf(r), Calendar.TUESDAY, 6 * 60))
    }

    @Test
    fun `overnight gap in the afternoon does not block`() {
        val r = rule(22, 0, 6, 0, setOf(Calendar.MONDAY))
        assertFalse(match(listOf(r), Calendar.MONDAY, 12 * 60))
    }

    // ─── Day-of-week wrap (Sunday <-> Saturday) ──────────────────────────────

    @Test
    fun `sunday-night overnight blocks monday morning (yesterday wraps to sunday)`() {
        val r = rule(22, 0, 6, 0, setOf(Calendar.SUNDAY))
        assertTrue(match(listOf(r), Calendar.MONDAY, 2 * 60))
    }

    @Test
    fun `saturday-night overnight blocks sunday morning (yesterday wraps to saturday)`() {
        val r = rule(23, 0, 1, 0, setOf(Calendar.SATURDAY))
        assertTrue(match(listOf(r), Calendar.SUNDAY, 30))
    }

    // ─── Multiple rules ──────────────────────────────────────────────────────

    @Test
    fun `any single matching rule blocks even when others do not`() {
        val work = rule(9, 0, 17, 0, setOf(Calendar.MONDAY))
        val bedtime = rule(22, 0, 6, 0, setOf(Calendar.MONDAY))
        // 23:00 Monday: work doesn't match, bedtime does.
        assertTrue(match(listOf(work, bedtime), Calendar.MONDAY, 23 * 60))
    }

    @Test
    fun `no rule matching means no block`() {
        val work = rule(9, 0, 17, 0, setOf(Calendar.MONDAY))
        val bedtime = rule(22, 0, 6, 0, setOf(Calendar.MONDAY))
        assertFalse(match(listOf(work, bedtime), Calendar.MONDAY, 20 * 60))
    }

    // ─── Full day ────────────────────────────────────────────────────────────

    @Test
    fun `full 24h same-day rule blocks across the day`() {
        val r = rule(0, 0, 23, 59, setOf(Calendar.MONDAY))
        assertTrue(match(listOf(r), Calendar.MONDAY, 0))
        assertTrue(match(listOf(r), Calendar.MONDAY, 12 * 60))
        assertTrue(match(listOf(r), Calendar.MONDAY, 23 * 60 + 58))
    }
}
