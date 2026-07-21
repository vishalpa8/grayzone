package com.grayzone.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aggressive coverage of SessionPolicyEngine for AppBackgrounded events:
 * usage recording (wasBlocked classification) and mid-session pausing.
 */
class SessionPolicyEngineBackgroundTest {

    private val engine = SessionPolicyEngine()
    private val now = 1_000_000L
    private fun bg(durationMs: Long) = AppEvent.AppBackgrounded(PKG, durationMs)
    private fun eval(event: AppEvent, s: SessionState) = engine.evaluate(event, s, now, APP)

    private inline fun <reified T> List<SessionCommand>.first(): T =
        first { it is T } as T

    private inline fun <reified T> List<SessionCommand>.none(): Boolean =
        none { it is T }

    // ─── Guards ──────────────────────────────────────────────────────────────

    @Test
    fun `unmonitored background produces no commands`() {
        val cmds = eval(bg(5_000), state(isMonitored = false, activeUntil = now + 10_000))
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun `kill switch off dismisses and records nothing`() {
        val cmds = eval(bg(5_000), state(isGrayzoneEnabled = false, activeUntil = now + 10_000))
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.DismissOverlay)
    }

    // ─── Notification cleanup always happens for a monitored app ─────────────

    @Test
    fun `monitored background with no usage only cancels the countdown notification`() {
        val cmds = eval(bg(0), state())
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is SessionCommand.CancelCountdownNotification)
    }

    // ─── wasBlocked classification ───────────────────────────────────────────

    @Test
    fun `usage with no active session at start is recorded as blocked`() {
        val cmds = eval(bg(5_000), state(activeUntil = 0L))
        val rec = cmds.first<SessionCommand.RecordUsage>()
        assertEquals(5_000L, rec.durationMs)
        assertTrue("friction-only usage must be flagged blocked", rec.wasBlocked)
        assertTrue("no active session must not pause", cmds.none<SessionCommand.UpdateState>())
    }

    @Test
    fun `usage inside an active session is recorded as not blocked and pauses remainder`() {
        val au = now + 10_000
        val cmds = eval(bg(5_000), state(activeUntil = au))
        val rec = cmds.first<SessionCommand.RecordUsage>()
        assertEquals(5_000L, rec.durationMs)
        assertFalse(rec.wasBlocked)

        val update = cmds.first<SessionCommand.UpdateState>()
        assertEquals(au - now, update.newSessionRemainingMs)
        assertTrue(update.clearActiveSession)
        assertNull(update.newActiveUntil)
    }

    @Test
    fun `session started exactly at its expiry boundary counts as blocked`() {
        // now - duration == activeUntil -> hasActiveSession(start) is false (strict <).
        val au = now - 5_000
        val cmds = eval(bg(5_000), state(activeUntil = au))
        val rec = cmds.first<SessionCommand.RecordUsage>()
        assertTrue(rec.wasBlocked)
        assertTrue(cmds.none<SessionCommand.UpdateState>())
    }

    // ─── Pausing behaviour ───────────────────────────────────────────────────

    @Test
    fun `active session with zero usage duration still pauses the remainder`() {
        val au = now + 8_000
        val cmds = eval(bg(0), state(activeUntil = au))
        // No usage recorded (duration 0), but session paused.
        assertTrue(cmds.none<SessionCommand.RecordUsage>())
        val update = cmds.first<SessionCommand.UpdateState>()
        assertEquals(8_000L, update.newSessionRemainingMs)
        assertTrue(update.clearActiveSession)
    }

    @Test
    fun `backgrounding after the session expired does not pause anything`() {
        // now == activeUntil -> not active -> no pause command.
        val cmds = eval(bg(5_000), state(activeUntil = now))
        assertTrue(cmds.none<SessionCommand.UpdateState>())
        // Was active at session start (now-5000 < now) -> not blocked.
        assertFalse(cmds.first<SessionCommand.RecordUsage>().wasBlocked)
    }

    // ─── Break interaction ───────────────────────────────────────────────────

    @Test
    fun `break does not suppress usage recording on background`() {
        // The break gate only short-circuits AppForegrounded; usage must still be
        // recorded so daily budgets stay accurate during a break.
        val au = now + 10_000
        val cmds = eval(bg(3_000), state(isOnBreak = true, activeUntil = au))
        assertTrue(cmds.any { it is SessionCommand.RecordUsage })
        assertTrue(cmds.any { it is SessionCommand.UpdateState })
    }

    // ─── TimerTick is inert ──────────────────────────────────────────────────

    @Test
    fun `timer tick produces no commands`() {
        val cmds = eval(AppEvent.TimerTick(PKG), state(activeUntil = now + 10_000))
        assertTrue(cmds.isEmpty())
    }
}
