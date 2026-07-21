package com.grayzone.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive boundary checks for the three raw predicates that every
 * higher-level decision relies on. Off-by-one here would cascade into
 * premature locks or leaks.
 */
class SessionStatePredicateTest {

    private val now = 1_000_000L

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
}
