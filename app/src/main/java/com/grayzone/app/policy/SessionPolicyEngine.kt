package com.grayzone.app.policy

/**
 * Pure Kotlin class that encapsulates the core business logic of Grayzone's sessions.
 * Completely decoupled from Android dependencies like SharedPreferences, Services, or Contexts.
 */
class SessionPolicyEngine {

    companion object {
        const val DEFAULT_WAIT_SECONDS = 5
    }

    /**
     * Evaluates a given event against the current state and returns a list of commands 
     * (side-effects) to be executed by the service.
     */
    fun evaluate(event: AppEvent, state: SessionState, now: Long, appName: String): List<SessionCommand> {
        val commands = mutableListOf<SessionCommand>()

        // 1. Global kill switch check
        if (!state.isGrayzoneEnabled) {
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

                // Schedule blocked? Hard lockout.
                if (state.isScheduleLocked) {
                    commands.add(SessionCommand.ShowScheduleLockScreen(state.packageName, appName))
                    return commands
                }

                // Currently locked out? (Penalty box)
                if (state.isLockedOut(now)) {
                    commands.add(SessionCommand.ShowLockoutScreen(state.packageName, appName, state.lockedUntil))
                    return commands
                }

                // Budget completely exhausted? (Daily limit reached)
                if (state.isBudgetExhausted()) {
                    // Set a lockout until tomorrow. We calculate this in the repository,
                    // but for now, we just enforce the lockout.
                    // If budget is 0, we treat it as an infinite lockout until budget resets.
                    // We'll show the regular lockout screen with the next day's reset time,
                    // but for the engine's purpose, we use a generic "end of day" or just generic lock.
                    // For now, if lockedOut is false but budget is exhausted, it shouldn't happen.
                    // The service should have set lockedUntil to tomorrow.
                    // But if it happens, we just show a lock.
                    commands.add(SessionCommand.ShowLockoutScreen(state.packageName, appName, state.lockedUntil))
                    return commands
                }

                // Active session exists? Let them pass.
                if (state.hasActiveSession(now)) {
                    commands.add(SessionCommand.DismissOverlay)
                    return commands
                }

                // Was the session paused? Resume it.
                if (state.sessionRemainingMs > 0) {
                    val newActiveUntil = now + state.sessionRemainingMs
                    // Clock skew sanity check
                    if (newActiveUntil > now) {
                        val newLockedUntil = newActiveUntil + (state.defaultLockoutMins * 60 * 1000L)
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            newActiveUntil = newActiveUntil,
                            newLockedUntil = newLockedUntil,
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
                commands.add(SessionCommand.ShowWaitScreen(state.packageName, appName, DEFAULT_WAIT_SECONDS))
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

                // Pause the session if time remains
                if (state.hasActiveSession(now)) {
                    val remainingMs = state.activeUntil - now
                    if (remainingMs > 0) {
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            newSessionRemainingMs = remainingMs,
                            clearActiveSession = true
                        ))
                    } else {
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
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
