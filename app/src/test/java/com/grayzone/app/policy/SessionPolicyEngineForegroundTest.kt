package com.grayzone.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aggressive coverage of SessionPolicyEngine for AppForegrounded events:
 * gate ordering, happy paths, negative paths, and boundary conditions.
 *
 * The engine's decision order is safety-critical:
 *   killSwitch > break > notMonitored > schedule > activeSession > lockout > budget > paused-resume > wait
 */
class SessionPolicyEngineForegroundTest {

    private val engine = SessionPolicyEngine()
    private val now = 1_000_000L
    private val lockoutMs = 60 * 60 * 1000L
    private val fg = AppEvent.AppForegrounded(PKG)

    private fun eval(s: SessionState) = engine.evaluate(fg, s, now, APP)

    // ─── Kill switch (highest priority) ──────────────────────────────────────

    @Test
    fun `kill switch off dismisses regardless of every other flag`() {
        val s = state(
            isGrayzoneEnabled = false,
            activeUntil = now + 5_000,
            lockedUntil = now + 9_000_000,
            budgetRemainingMs = 0L,
            isScheduleLocked = true,
            isOnBreak = true,
            sessionRemainingMs = 5_000
        )
        val cmds = eval(s)
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.DismissOverlay)
    }

    // ─── Daily break ─────────────────────────────────────────────────────────

    @Test
    fun `break dismisses over schedule, lockout, budget and active session`() {
        val s = state(
            isOnBreak = true,
            isScheduleLocked = true,
            lockedUntil = now + 1_000,
            budgetRemainingMs = 0L,
            activeUntil = now + 1_000
        )
        val cmds = eval(s)
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.DismissOverlay)
    }

    // ─── Not monitored ───────────────────────────────────────────────────────

    @Test
    fun `unmonitored app always passes even with stale lock state`() {
        val s = state(isMonitored = false, activeUntil = now + 1_000, lockedUntil = now + 9_999)
        val cmds = eval(s)
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.DismissOverlay)
    }

    // ─── Schedule lock ───────────────────────────────────────────────────────

    @Test
    fun `schedule lock is shown`() {
        val cmds = eval(state(isScheduleLocked = true))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowScheduleLockScreen)
    }

    @Test
    fun `schedule lock overrides an active session (hard focus block)`() {
        val au = now + 5 * 60_000
        val cmds = eval(state(isScheduleLocked = true, activeUntil = au, lockedUntil = au + lockoutMs))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowScheduleLockScreen)
    }

    // ─── Active session (the "1h 3m" regression area) ────────────────────────

    @Test
    fun `active session dismisses with realistic lockedUntil in the future`() {
        val au = now + 3 * 60_000
        val cmds = eval(state(activeUntil = au, lockedUntil = au + lockoutMs))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `active session dismisses even when the daily budget is exhausted`() {
        val au = now + 60_000
        val cmds = eval(state(activeUntil = au, lockedUntil = au + lockoutMs, budgetRemainingMs = 0L))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `session expired exactly at now is not active - lockout takes over`() {
        val cmds = eval(state(activeUntil = now, lockedUntil = now + lockoutMs))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowLockoutScreen)
    }

    @Test
    fun `session expired one ms ago within lockout shows lockout`() {
        val cmds = eval(state(activeUntil = now - 1, lockedUntil = now + lockoutMs))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowLockoutScreen)
    }

    // ─── Lockout ─────────────────────────────────────────────────────────────

    @Test
    fun `lockout screen carries the correct lockedUntil timestamp`() {
        val lu = now + 42_000
        val cmds = eval(state(lockedUntil = lu))
        assertEquals(1, cmds.size)
        val cmd = cmds[0] as SessionCommand.ShowLockoutScreen
        assertEquals(lu, cmd.lockedUntil)
    }

    @Test
    fun `lockout boundary - now equals lockedUntil is no longer locked`() {
        // Not locked, not active, no budget/paused -> fresh wait screen.
        val cmds = eval(state(lockedUntil = now))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowWaitScreen)
    }

    @Test
    fun `lockout takes priority over exhausted budget`() {
        val cmds = eval(state(lockedUntil = now + 1_000, budgetRemainingMs = 0L))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowLockoutScreen)
    }

    // ─── Budget ──────────────────────────────────────────────────────────────

    @Test
    fun `budget exactly zero is exhausted`() {
        val cmds = eval(state(budgetRemainingMs = 0L))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowBudgetLockScreen)
    }

    @Test
    fun `budget negative is exhausted`() {
        val cmds = eval(state(budgetRemainingMs = -5_000L))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowBudgetLockScreen)
    }

    @Test
    fun `exhausted budget blocks a paused resume`() {
        val cmds = eval(state(budgetRemainingMs = 0L, sessionRemainingMs = 5_000))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowBudgetLockScreen)
    }

    @Test
    fun `one millisecond of budget left is not exhausted`() {
        val cmds = eval(state(budgetRemainingMs = 1L))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.ShowWaitScreen)
    }

    // ─── Paused resume ───────────────────────────────────────────────────────

    @Test
    fun `paused session resumes with new active window, lockout and cleared remainder`() {
        val remaining = 5_000L
        val cmds = eval(state(sessionRemainingMs = remaining, defaultLockoutMins = 60))
        assertEquals(2, cmds.size)
        val update = cmds[0] as SessionCommand.UpdateState
        assertEquals(now + remaining, update.newActiveUntil)
        assertEquals(now + remaining + lockoutMs, update.newLockedUntil)
        assertEquals(0L, update.newSessionRemainingMs)
        assertTrue(cmds[1] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `paused resume respects a custom lockout duration`() {
        val remaining = 90_000L
        val cmds = eval(state(sessionRemainingMs = remaining, defaultLockoutMins = 15))
        val update = cmds[0] as SessionCommand.UpdateState
        assertEquals(now + remaining + 15 * 60_000L, update.newLockedUntil)
    }

    @Test
    fun `resume with overflowing remainder is treated as clock skew and clears remainder`() {
        // now + Long.MAX_VALUE overflows to negative -> newActiveUntil <= now -> skew path.
        val cmds = eval(state(sessionRemainingMs = Long.MAX_VALUE))
        assertEquals(2, cmds.size)
        val update = cmds[0] as SessionCommand.UpdateState
        assertEquals(0L, update.newSessionRemainingMs)
        assertEquals(null, update.newActiveUntil)
        assertTrue(cmds[1] is SessionCommand.ShowWaitScreen)
    }

    // ─── Fresh wait ──────────────────────────────────────────────────────────

    @Test
    fun `no session shows friction wait screen with default wait seconds`() {
        val cmds = eval(state())
        assertEquals(1, cmds.size)
        val cmd = cmds[0] as SessionCommand.ShowWaitScreen
        assertEquals(SessionPolicyEngine.DEFAULT_WAIT_SECONDS, cmd.waitSeconds)
    }
}
