package com.grayzone.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary checks for the three raw predicates that every higher-level
 * decision relies on. Off-by-one here would cascade into premature locks
 * or leaks.
 *
 * Wall-clock-only paths (elapsed fields = 0 / nowElapsed default) remain for
 * legacy prefs and unit tests. Dual-clock behaviour is covered below and in
 * [MonotonicDeadlineTest] (F-190 / F-110 / F-111).
 */
class SessionStatePredicateTest {

    private val now = 1_000_000L
    private val elapsed = 50_000L

    // ─── hasActiveSession: strict `now < activeUntil` ────────────────────────

    @Test
    fun `hasActiveSession is true one ms before expiry`() {
        assertTrue(state(activeUntil = now + 1).hasActiveSession(now))
    }

    @Test
    fun `hasActiveSession is false exactly at expiry`() {
        assertFalse(state(activeUntil = now).hasActiveSession(now))
    }

    @Test
    fun `hasActiveSession is false after expiry`() {
        assertFalse(state(activeUntil = now - 1).hasActiveSession(now))
    }

    @Test
    fun `hasActiveSession is false when never set`() {
        assertFalse(state(activeUntil = 0L).hasActiveSession(now))
    }

    // ─── isLockedOut: strict `now < lockedUntil` ─────────────────────────────

    @Test
    fun `isLockedOut is true one ms before release`() {
        assertTrue(state(lockedUntil = now + 1).isLockedOut(now))
    }

    @Test
    fun `isLockedOut is false exactly at release`() {
        assertFalse(state(lockedUntil = now).isLockedOut(now))
    }

    @Test
    fun `isLockedOut is false after release`() {
        assertFalse(state(lockedUntil = now - 1).isLockedOut(now))
    }

    @Test
    fun `isLockedOut is false when never set`() {
        assertFalse(state(lockedUntil = 0L).isLockedOut(now))
    }

    // ─── isSessionConsumed: session spent with nothing banked ────────────────

    @Test
    fun `session is consumed once it expires with no remainder`() {
        assertTrue(state(activeUntil = now - 1, sessionRemainingMs = 0L).isSessionConsumed(now))
    }

    @Test
    fun `a paused session is never consumed`() {
        assertFalse(state(activeUntil = now - 1, sessionRemainingMs = 5_000L).isSessionConsumed(now))
    }

    @Test
    fun `a running session is not consumed`() {
        assertFalse(state(activeUntil = now + 1).isSessionConsumed(now))
    }

    @Test
    fun `an app that never had a session is not consumed`() {
        assertFalse(state(activeUntil = 0L).isSessionConsumed(now))
    }

    // ─── isBudgetExhausted: `budgetRemainingMs <= 0` ─────────────────────────

    @Test
    fun `budget exhausted at exactly zero`() {
        assertTrue(state(budgetRemainingMs = 0L).isBudgetExhausted())
    }

    @Test
    fun `budget exhausted when negative`() {
        assertTrue(state(budgetRemainingMs = -1L).isBudgetExhausted())
    }

    @Test
    fun `budget not exhausted with one ms left`() {
        assertFalse(state(budgetRemainingMs = 1L).isBudgetExhausted())
    }

    @Test
    fun `budget not exhausted when unlimited`() {
        assertFalse(state(budgetRemainingMs = Long.MAX_VALUE).isBudgetExhausted())
    }

    // ─── Dual-clock: wall jump must not clear an active lockout (F-110) ───────

    @Test
    fun `dual clock lockout survives wall clock jump forward`() {
        val s = state(
            lockedUntil = now + 60_000L,
            lockedStartedElapsedMs = elapsed,
            lockedDurationMs = 60_000L
        )
        val jumpedWall = now + 30L * 24 * 60 * 60 * 1000L
        assertTrue(s.isLockedOut(jumpedWall, elapsed + 1_000L))
    }

    @Test
    fun `dual clock active session resists wall clock rewind`() {
        val s = state(
            activeUntil = now + 60_000L,
            activeStartedElapsedMs = elapsed,
            activeDurationMs = 60_000L
        )
        // Wall rewound before start, but elapsed still inside the window.
        assertTrue(s.hasActiveSession(now - 10_000L, elapsed + 1_000L))
        // Elapsed past duration → inactive even if wall still says active.
        assertFalse(s.hasActiveSession(now + 1_000L, elapsed + 60_001L))
    }
}
