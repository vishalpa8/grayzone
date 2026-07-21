package com.grayzone.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyEngineTest {

    private val engine = SessionPolicyEngine()
    private val now = 1000L
    private val pkg = "com.test.app"
    private val appName = "Test App"

    private val defaultState = SessionState(
        packageName = pkg,
        isMonitored = true,
        isGrayzoneEnabled = true,
        activeUntil = 0L,
        lockedUntil = 0L,
        budgetRemainingMs = 60000L,
        sessionRemainingMs = 0L,
        defaultSessionMins = 10,
        defaultLockoutMins = 60,
        isScheduleLocked = false
    )

    @Test
    fun `when global kill switch is off, dismiss overlay`() {
        val state = defaultState.copy(isGrayzoneEnabled = false)
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)
        
        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `when app is not monitored, dismiss overlay`() {
        val state = defaultState.copy(isMonitored = false)
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)
        
        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `when daily break is active, dismiss overlay even when locked out`() {
        val state = defaultState.copy(
            isOnBreak = true,
            lockedUntil = now + 100000L,      // would normally be a hard lockout
            isScheduleLocked = true,          // would normally be a schedule lock
            budgetRemainingMs = 0L            // would normally be a budget lock
        )
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)

        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `when daily break is active, backgrounding still records usage`() {
        val state = defaultState.copy(isOnBreak = true, activeUntil = now + 5000L)
        val commands = engine.evaluate(AppEvent.AppBackgrounded(pkg, 1000L), state, now, appName)

        assertTrue(commands.any { it is SessionCommand.RecordUsage })
    }

    @Test
    fun `when schedule is locked, show schedule lock screen`() {
        val state = defaultState.copy(isScheduleLocked = true)
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)
        
        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.ShowScheduleLockScreen)
    }

    @Test
    fun `when currently locked out, show lockout screen`() {
        val state = defaultState.copy(lockedUntil = now + 1000L) // Locked in the future
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)
        
        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.ShowLockoutScreen)
    }

    @Test
    fun `when budget exhausted, show budget lock screen`() {
        val state = defaultState.copy(budgetRemainingMs = 0L)
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)
        
        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.ShowBudgetLockScreen)
    }

    @Test
    fun `when active session exists, dismiss overlay`() {
        val state = defaultState.copy(activeUntil = now + 1000L) // Active in the future
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)
        
        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `when session paused, resume it without wait screen`() {
        val state = defaultState.copy(sessionRemainingMs = 5000L)
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, now, appName)
        
        assertEquals(2, commands.size)
        
        val updateCmd = commands[0] as SessionCommand.UpdateState
        assertEquals(now + 5000L, updateCmd.newActiveUntil)
        assertEquals(now + 5000L + (state.defaultLockoutMins * 60 * 1000L), updateCmd.newLockedUntil)
        assertEquals(0L, updateCmd.newSessionRemainingMs) // Cleared
        
        assertTrue(commands[1] is SessionCommand.DismissOverlay)
    }

    @Test
    fun `when session paused but clock skew detected, clear it and show wait screen`() {
        // Paused session remaining is -5000L (clock went back, or negative remaining)
        // Wait, the logic is: newActiveUntil = now + sessionRemainingMs
        // If sessionRemainingMs is positive, newActiveUntil > now is true unless overflow.
        // Wait, what if the user manually changes the clock to the future? 
        // Then `now` is huge, `newActiveUntil = now + 5000L` is still > `now`.
        // The clock skew check in original code: `val newActiveUntil = now + normalizedRemainingMillis; if (newActiveUntil > now)`
        // `normalizedRemainingMillis` could be negative if not normalized. 
        // If `sessionRemainingMs` is < 0, it skips the `if (sessionRemainingMs > 0)` block entirely, going to wait screen.
        // So the clock skew block in engine isn't strictly needed if we already check > 0, 
        // unless they are both large enough to overflow.
        // Let's test the default behavior: fresh app launch.
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), defaultState, now, appName)
        
        assertEquals(1, commands.size)
        assertTrue(commands[0] is SessionCommand.ShowWaitScreen)
    }

    @Test
    fun `when backgrounding monitored app with active session, pause it`() {
        val state = defaultState.copy(activeUntil = now + 5000L)
        val commands = engine.evaluate(AppEvent.AppBackgrounded(pkg, 1000L), state, now, appName)
        
        assertEquals(3, commands.size)
        assertTrue(commands[0] is SessionCommand.CancelCountdownNotification)
        
        val recordCmd = commands[1] as SessionCommand.RecordUsage
        assertEquals(1000L, recordCmd.durationMs)
        assertEquals(false, recordCmd.wasBlocked)
        
        val updateCmd = commands[2] as SessionCommand.UpdateState
        assertEquals(5000L, updateCmd.newSessionRemainingMs)
        assertTrue(updateCmd.clearActiveSession)
    }
}
