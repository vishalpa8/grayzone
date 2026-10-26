package com.grayzone.app.policy

/**
 * Pure Kotlin class that encapsulates the core business logic of Grayzone's sessions.
 * Completely decoupled from Android dependencies like SharedPreferences, Services, or Contexts.
 */
class SessionPolicyEngine {

    companion object {
        const val DEFAULT_WAIT_SECONDS = 5

        /** Lockout length for [state], in millis. */
        fun lockoutMs(state: SessionState): Long = state.defaultLockoutMins * 60 * 1000L
    }

    /**
     * Evaluates a given event against the current state and returns a list of commands 
     * (side-effects) to be executed by the service.
     */
    fun evaluate(
        event: AppEvent,
        state: SessionState,
        now: Long,
        appName: String,
        nowElapsed: Long = -1L
    ): List<SessionCommand> {
        val commands = mutableListOf<SessionCommand>()

        // 1. Global kill switch check
        if (!state.isGrayzoneEnabled) {
            commands.add(SessionCommand.DismissOverlay)
            return commands
        }

        // 2. Daily break: while active, nothing is locked — no friction screen,
        // no lockout, no schedule block. Usage is still recorded on backgrounding
        // so daily budgets stay accurate.
        if (state.isOnBreak && event is AppEvent.AppForegrounded) {
            commands.add(SessionCommand.DismissOverlay)
            return commands
        }

        when (event) {
            is AppEvent.AppForegrounded -> {
                // Not monitored? Let them pass.
                if (!state.isMonitored) {
                    commands.add(SessionCommand.DismissOverlay)
                    return commands
                }

                // Schedule blocked? Hard lockout. A scheduled focus window overrides
                // even an active session, since that's the whole point of the block.
                if (state.isScheduleLocked) {
                    commands.add(SessionCommand.ShowScheduleLockScreen(state.packageName, appName))
                    return commands
                }

                // Active session exists? Let them pass.
                if (state.hasActiveSession(now, nowElapsed)) {
                    commands.add(SessionCommand.DismissOverlay)
                    return commands
                }

                // Currently locked out? (Penalty box)
                if (state.isLockedOut(now, nowElapsed)) {
                    commands.add(SessionCommand.ShowLockoutScreen(state.packageName, appName, state.lockedUntil))
                    return commands
                }

                // The session ran all the way out but the lockout was never written — the expiry
                // alarm was missed (Doze, process death, OEM alarm throttling). Start the penalty
                // box now, measured from this moment so it lasts exactly `defaultLockoutMins`.
                if (state.isSessionConsumed(now, nowElapsed)) {
                    val lockedUntil = now + lockoutMs(state)
                    commands.add(SessionCommand.UpdateState(
                        packageName = state.packageName,
                        newLockedUntil = lockedUntil,
                        clearActiveSession = true
                    ))
                    commands.add(SessionCommand.ShowLockoutScreen(state.packageName, appName, lockedUntil))
                    return commands
                }

                // Budget completely exhausted? (Daily limit reached)
                // The budget lock has no meaningful "locked until" timestamp — it resets
                // at midnight — so it gets its own screen instead of the lockout screen.
                if (state.isBudgetExhausted()) {
                    commands.add(SessionCommand.ShowBudgetLockScreen(state.packageName, appName))
                    return commands
                }

                // Was the session paused? Resume it.
                if (state.sessionRemainingMs > 0) {
                    val newActiveUntil = now + state.sessionRemainingMs
                    // Clock skew sanity check
                    if (newActiveUntil > now) {
                        // No lockout is written here: the penalty box only starts once the
                        // session is actually used up (see isSessionConsumed).
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            newActiveUntil = newActiveUntil,
                            newSessionRemainingMs = 0L // Clear remaining
                        ))
                        commands.add(SessionCommand.DismissOverlay)
                        return commands
                    } else {
                        // Clock skew detected, fall through to create a new session via wait screen
                        // but clear the invalid remaining time.
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            newSessionRemainingMs = 0L
                        ))
                    }
                }

                // No active session, not locked out, budget exists.
                // Action: Show the wait screen (Countdown).
                commands.add(SessionCommand.ShowWaitScreen(state.packageName, appName, state.waitSeconds))
            }

            is AppEvent.AppBackgrounded -> {
                // If it wasn't monitored, do nothing.
                if (!state.isMonitored) return commands

                commands.add(SessionCommand.CancelCountdownNotification)
                
                // Record usage
                if (event.sessionDurationMs > 0) {
                    // Was it blocked? If active session was false, yes it was blocked by wait screen.
                    val wasBlocked = !state.hasActiveSession(now - event.sessionDurationMs)
                    commands.add(SessionCommand.RecordUsage(state.packageName, event.sessionDurationMs, wasBlocked))
                }

                // Pause the session if time remains. The lockout is deliberately untouched:
                // leaving mid-session banks the remainder, it is not a penalty.
                if (state.hasActiveSession(now, nowElapsed)) {
                    // Wall-clock `activeUntil - now` would let a clock change bank extra time
                    // (clock set back) or jump straight to a lockout (clock set forward).
                    val remainingMs = state.activeRemainingMs(now, nowElapsed)
                    if (remainingMs > 0) {
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            newSessionRemainingMs = remainingMs,
                            clearActiveSession = true
                        ))
                    } else {
                        // Session ran out at the exact moment the app left — owe the lockout.
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            newLockedUntil = now + lockoutMs(state),
                            clearActiveSession = true
                        ))
                    }
                }
            }

            is AppEvent.TimerTick -> {
                // Timer events (e.g. countdown finished) handled elsewhere for now, 
                // but engine could process them.
            }
        }

        return commands
    }
}
