package com.grayzone.app.policy

/**
 * Represents an event entering the policy engine.
 */
sealed class AppEvent {
    data class AppForegrounded(val packageName: String) : AppEvent()
    data class AppBackgrounded(val packageName: String, val sessionDurationMs: Long) : AppEvent()
    data class TimerTick(val packageName: String) : AppEvent()
}

/**
 * Represents the current immutable truth of a specific app's session state.
 */
data class SessionState(
    val packageName: String,
    val isMonitored: Boolean,
    val isGrayzoneEnabled: Boolean,
    val activeUntil: Long,
    val lockedUntil: Long,
    val budgetRemainingMs: Long,
    val sessionRemainingMs: Long,
    val defaultSessionMins: Int,
    val defaultLockoutMins: Int,
    /** Validated friction wait (seconds); sourced via PerAppLimits (F-112). */
    val waitSeconds: Int = 5,
    // Add schedule constraint support if needed (e.g. isScheduleLocked)
    val isScheduleLocked: Boolean = false,
    /** Daily break active: nothing is locked while true. */
    val isOnBreak: Boolean = false,
    /** Dual-clock fields for [MonotonicDeadline]; 0 = wall-clock only (legacy / tests). */
    val activeStartedElapsedMs: Long = 0L,
    val activeDurationMs: Long = 0L,
    val lockedStartedElapsedMs: Long = 0L,
    val lockedDurationMs: Long = 0L
) {
    /**
     * @param nowElapsed elapsedRealtime; pass a negative value (default) to use wall-clock only.
     *   Production callers must pass [android.os.SystemClock.elapsedRealtime] when dual-clock
     *   fields are populated.
     */
    fun hasActiveSession(now: Long, nowElapsed: Long = -1L): Boolean {
        if (activeStartedElapsedMs <= 0L || activeDurationMs <= 0L || nowElapsed < 0L) {
            return activeUntil > 0L && now < activeUntil
        }
        return MonotonicDeadline.isPending(
            activeUntil, activeStartedElapsedMs, activeDurationMs, now, nowElapsed
        )
    }

    fun isLockedOut(now: Long, nowElapsed: Long = -1L): Boolean {
        if (lockedStartedElapsedMs <= 0L || lockedDurationMs <= 0L || nowElapsed < 0L) {
            return lockedUntil > 0L && now < lockedUntil
        }
        return MonotonicDeadline.isPending(
            lockedUntil, lockedStartedElapsedMs, lockedDurationMs, now, nowElapsed
        )
    }

    fun isBudgetExhausted(): Boolean = budgetRemainingMs <= 0
}

/**
 * Represents an action that the service must execute based on the engine's decision.
 */
sealed class SessionCommand {
    /** Policy signal that overlays should be clear; OverlayService treats as intentional no-op. */
    object DismissOverlay : SessionCommand()
    data class ShowWaitScreen(
        val packageName: String, 
        val appName: String,
        val waitSeconds: Int
    ) : SessionCommand()
    data class ShowLockoutScreen(
        val packageName: String, 
        val appName: String, 
        val lockedUntil: Long
    ) : SessionCommand()
    data class ShowScheduleLockScreen(
        val packageName: String,
        val appName: String
    ) : SessionCommand()
    data class ShowBudgetLockScreen(
        val packageName: String,
        val appName: String
    ) : SessionCommand()
    data class RecordUsage(
        val packageName: String,
        val durationMs: Long,
        val wasBlocked: Boolean
    ) : SessionCommand()
    data class UpdateState(
        val packageName: String,
        val newActiveUntil: Long? = null,
        val newLockedUntil: Long? = null,
        val newSessionRemainingMs: Long? = null,
        val clearActiveSession: Boolean = false
    ) : SessionCommand()
    object CancelCountdownNotification : SessionCommand()
}
