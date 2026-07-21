package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBudgetTest {

    private val today = "2026-07-21"
    private val yesterday = "2026-07-20"

    @Test
    fun `unlimited budget returns max long`() {
        assertEquals(Long.MAX_VALUE, DailyBudget.remainingMs(0, today, today, 9_999L))
        assertEquals(Long.MAX_VALUE, DailyBudget.remainingMs(-5, today, today, 0L))
    }

    @Test
    fun `remaining subtracts todays usage`() {
        val budgetMins = 30
        val used = 10 * 60 * 1000L
        assertEquals(
            20 * 60 * 1000L,
            DailyBudget.remainingMs(budgetMins, today, today, used)
        )
    }

    @Test
    fun `stale previous-day usage is ignored`() {
        assertEquals(
            30 * 60 * 1000L,
            DailyBudget.remainingMs(30, today, yesterday, 99 * 60 * 1000L)
        )
    }

    @Test
    fun `exhausted budget can go negative`() {
        assertTrue(DailyBudget.remainingMs(10, today, today, 15 * 60 * 1000L) < 0)
    }

    @Test
    fun `accumulate appends on same day`() {
        assertEquals(
            15_000L,
            DailyBudget.accumulateUsedMs(today, today, 10_000L, 5_000L)
        )
    }

    @Test
    fun `accumulate starts fresh on a new day`() {
        assertEquals(
            5_000L,
            DailyBudget.accumulateUsedMs(today, yesterday, 99_000L, 5_000L)
        )
    }

    @Test
    fun `zero duration does not invent usage on a new day`() {
        assertEquals(0L, DailyBudget.accumulateUsedMs(today, yesterday, 99_000L, 0L))
    }

    @Test
    fun `budget lock requires foreground not on break and exhausted`() {
        assertTrue(DailyBudget.shouldShowBudgetLock(30 * 60_000L, 30, true, false))
        assertFalse(DailyBudget.shouldShowBudgetLock(30 * 60_000L, 30, false, false))
        assertFalse(DailyBudget.shouldShowBudgetLock(30 * 60_000L, 30, true, true))
        assertFalse(DailyBudget.shouldShowBudgetLock(10 * 60_000L, 30, true, false))
        assertFalse(DailyBudget.shouldShowBudgetLock(99 * 60_000L, 0, true, false))
    }
}
