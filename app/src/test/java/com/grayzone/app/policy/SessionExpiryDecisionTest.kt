package com.grayzone.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers the two defects in User Story 1: the countdown must never render negative
 * (`clampRemainingMs`) and the app must lock at expiry only when appropriate (`decide`).
 * Contracts SE-1/2/3/6 in specs/002-fix-timeout-accessibility-bugs/contracts/session-expiry.md.
 */
class SessionExpiryDecisionTest {

    private val now = 1_000_000L
    private val lockout = now + 60 * 60 * 1000L // 1h lockout window

    private fun decide(
        activeUntil: Long,
        lockedUntil: Long = lockout,
        isForegroundPackage: Boolean = true,
        isGrayzoneEnabled: Boolean = true,
        isOnBreak: Boolean = false
    ) = SessionExpiryDecision.decide(
        now = now,
        activeUntil = activeUntil,
        lockedUntil = lockedUntil,
        isForegroundPackage = isForegroundPackage,
        isGrayzoneEnabled = isGrayzoneEnabled,
        isOnBreak = isOnBreak
    )

    // ─── clamp invariant: remaining is never negative (SC-001) ───────────────

    @Test
    fun `clamp returns remaining while session active`() {
        assertEquals(5_000L, SessionExpiryDecision.clampRemainingMs(now, now + 5_000L))
    }

    @Test
    fun `clamp returns zero exactly at expiry`() {
        assertEquals(0L, SessionExpiryDecision.clampRemainingMs(now, now))
    }

    @Test
    fun `clamp never returns negative after expiry`() {
        assertEquals(0L, SessionExpiryDecision.clampRemainingMs(now, now - 120_000L))
    }

    // ─── ShowLock: expired + foreground + within lockout (SE-1) ──────────────

    @Test
    fun `expired in foreground within lockout shows lock`() {
        assertSame(SessionExpiryOutcome.ShowLock, decide(activeUntil = now))
    }

    @Test
    fun `expired one ms ago in foreground shows lock`() {
        assertSame(SessionExpiryOutcome.ShowLock, decide(activeUntil = now - 1))
    }

    // ─── ClampCountdown: expired but not foreground (SE-2) ───────────────────

    @Test
    fun `expired but not foreground clamps only`() {
        assertSame(
            SessionExpiryOutcome.ClampCountdown,
            decide(activeUntil = now, isForegroundPackage = false)
        )
    }

    @Test
    fun `expired foreground but lockout window over clamps only`() {
        assertSame(
            SessionExpiryOutcome.ClampCountdown,
            decide(activeUntil = now - 10_000L, lockedUntil = now - 5_000L)
        )
    }

    // ─── NoOp: not yet expired / disabled / on break / no session (SE-3, SE-6) ─

    @Test
    fun `not yet expired is noop`() {
        assertSame(SessionExpiryOutcome.NoOp, decide(activeUntil = now + 1))
    }

    @Test
    fun `no session is noop`() {
        assertSame(SessionExpiryOutcome.NoOp, decide(activeUntil = 0L))
    }

    @Test
    fun `disabled is noop even when expired`() {
        assertSame(
            SessionExpiryOutcome.NoOp,
            decide(activeUntil = now, isGrayzoneEnabled = false)
        )
    }

    @Test
    fun `on break is noop even when expired`() {
        assertSame(
            SessionExpiryOutcome.NoOp,
            decide(activeUntil = now, isOnBreak = true)
        )
    }
}
