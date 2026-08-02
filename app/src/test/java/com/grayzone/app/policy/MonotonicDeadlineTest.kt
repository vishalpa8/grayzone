package com.grayzone.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicDeadlineTest {

    @Test
    fun `wall clock only — pending before deadline`() {
        assertTrue(MonotonicDeadline.isPending(1_000L, 0L, 0L, 999L, 0L))
        assertFalse(MonotonicDeadline.isPending(1_000L, 0L, 0L, 1_000L, 0L))
    }

    @Test
    fun `elapsed clock resists wall clock jump forward`() {
        val startedElapsed = 10_000L
        val duration = 60_000L
        val untilWall = 1_700_000_000_000L + duration
        // Wall jumps +30 days; elapsed only advanced 1s → still pending
        val nowWall = untilWall + 30L * 24 * 60 * 60 * 1000L
        val nowElapsed = startedElapsed + 1_000L
        assertTrue(
            MonotonicDeadline.isPending(untilWall, startedElapsed, duration, nowWall, nowElapsed)
        )
    }

    @Test
    fun `elapsed expiry ends the deadline without needing wall`() {
        val startedElapsed = 10_000L
        val duration = 60_000L
        val untilWall = 9_999_999_999_999L // far future wall
        val nowElapsed = startedElapsed + duration
        assertFalse(
            MonotonicDeadline.isPending(untilWall, startedElapsed, duration, 0L, nowElapsed)
        )
    }

    @Test
    fun `reboot falls back to wall clock`() {
        val startedElapsed = 5_000_000L
        val duration = 60_000L
        val untilWall = 2_000L
        // After reboot elapsed is small again
        val nowElapsed = 100L
        assertTrue(nowElapsed < startedElapsed)
        // Wall says expired
        assertFalse(
            MonotonicDeadline.isPending(untilWall, startedElapsed, duration, 3_000L, nowElapsed)
        )
        // Wall says still pending
        assertTrue(
            MonotonicDeadline.isPending(untilWall, startedElapsed, duration, 1_000L, nowElapsed)
        )
    }

    @Test
    fun `SessionState lockout ignores wall jump when dual-clock set`() {
        val started = 50_000L
        val duration = 3_600_000L
        val state = SessionState(
            packageName = "com.test",
            isMonitored = true,
            isGrayzoneEnabled = true,
            activeUntil = 0L,
            lockedUntil = 1_700_000_000_000L + duration,
            budgetRemainingMs = Long.MAX_VALUE,
            sessionRemainingMs = 0L,
            defaultSessionMins = 10,
            defaultLockoutMins = 60,
            lockedStartedElapsedMs = started,
            lockedDurationMs = duration
        )
        val futureWall = state.lockedUntil + 30L * 24 * 60 * 60 * 1000L
        assertTrue(state.isLockedOut(futureWall, started + 1_000L))
        assertFalse(state.isLockedOut(futureWall, started + duration + 1L))
    }
}
