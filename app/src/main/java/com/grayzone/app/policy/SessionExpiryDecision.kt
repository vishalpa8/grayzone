package com.grayzone.app.policy

/**
 * Pure, Android-free decision for what to do when a session's expiry boundary is reached (or when
 * re-evaluated after a restart). Keeping this logic Android-free makes the fix for the
 * negative-countdown / missing-lock defect fully unit-testable off-device.
 *
 * See specs/002-fix-timeout-accessibility-bugs/contracts/session-expiry.md.
 */
sealed class SessionExpiryOutcome {
    /** Disabled, on break, or the session still has time — do nothing. */
    object NoOp : SessionExpiryOutcome()

    /** Session ended while the app is not foreground — clear the countdown UI so it never shows a negative value. */
    object ClampCountdown : SessionExpiryOutcome()

    /** Session ended, the app is foreground, and still within the lockout window — clamp the countdown AND show the lock. */
    object ShowLock : SessionExpiryOutcome()
}

object SessionExpiryDecision {

    /**
     * Decide the outcome for a monitored package at/after its session boundary.
     *
     * @param now current epoch millis
     * @param activeUntil session end (<= 0 means no session)
     * @param lockedUntil lockout end
     * @param isForegroundPackage whether the monitored app is currently in the foreground
     * @param isGrayzoneEnabled global kill switch
     * @param isOnBreak daily break active (nothing is locked while true)
     */
    fun decide(
        now: Long,
        activeUntil: Long,
        lockedUntil: Long,
        isForegroundPackage: Boolean,
        isGrayzoneEnabled: Boolean,
        isOnBreak: Boolean,
        nowElapsed: Long = -1L,
        activeStartedElapsedMs: Long = 0L,
        activeDurationMs: Long = 0L,
        lockedStartedElapsedMs: Long = 0L,
        lockedDurationMs: Long = 0L
    ): SessionExpiryOutcome {
        if (!isGrayzoneEnabled || isOnBreak) return SessionExpiryOutcome.NoOp
        
        val isSessionActive = if (activeStartedElapsedMs > 0L) {
            MonotonicDeadline.isPending(activeUntil, activeStartedElapsedMs, activeDurationMs, now, nowElapsed)
        } else {
            activeUntil > 0L && now < activeUntil
        }
        
        // No session, or the session still has time left.
        if (activeUntil <= 0L || isSessionActive) return SessionExpiryOutcome.NoOp
        
        val isLocked = if (lockedStartedElapsedMs > 0L) {
            MonotonicDeadline.isPending(lockedUntil, lockedStartedElapsedMs, lockedDurationMs, now, nowElapsed)
        } else {
            lockedUntil > 0L && now < lockedUntil
        }
        
        // Session has ended.
        return if (isForegroundPackage && isLocked) {
            SessionExpiryOutcome.ShowLock
        } else {
            SessionExpiryOutcome.ClampCountdown
        }
    }

    /** Remaining session time to display; clamped so it is never negative (fixes the negative counter). */
    fun clampRemainingMs(
        now: Long, 
        activeUntil: Long,
        nowElapsed: Long = -1L,
        activeStartedElapsedMs: Long = 0L,
        activeDurationMs: Long = 0L
    ): Long {
        if (activeStartedElapsedMs > 0L) {
            return MonotonicDeadline.remainingMs(activeUntil, activeStartedElapsedMs, activeDurationMs, now, nowElapsed)
        }
        return (activeUntil - now).coerceAtLeast(0L)
    }
}
