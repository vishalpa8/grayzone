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
    // Add schedule constraint support if needed (e.g. isScheduleLocked)
    val isScheduleLocked: Boolean = false,
    /** Daily break active: nothing is locked while true. */
    val isOnBreak: Boolean = false
) {
    fun hasActiveSession(now: Long): Boolean = now < activeUntil
    fun isLockedOut(now: Long): Boolean = now < lockedUntil
    fun isBudgetExhausted(): Boolean = budgetRemainingMs <= 0
}

/**
 * Represents an action that the service must execute based on the engine's decision.
 */
sealed class SessionCommand {
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
