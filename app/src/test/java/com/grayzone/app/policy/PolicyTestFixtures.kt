package com.grayzone.app.policy

/** Shared fixtures for SessionPolicyEngine tests. */

internal const val PKG = "com.test.app"
internal const val APP = "Test App"

/**
 * Builds a [SessionState] with sensible, non-locking defaults so each test only
 * has to override the fields relevant to the scenario under test.
 *
 * budgetRemainingMs defaults to Long.MAX_VALUE = "no daily budget / unlimited".
 */
internal fun state(
    isMonitored: Boolean = true,
    isGrayzoneEnabled: Boolean = true,
    activeUntil: Long = 0L,
    lockedUntil: Long = 0L,
    budgetRemainingMs: Long = Long.MAX_VALUE,
    sessionRemainingMs: Long = 0L,
    defaultSessionMins: Int = 10,
    defaultLockoutMins: Int = 60,
    waitSeconds: Int = 5,
    isScheduleLocked: Boolean = false,
    isOnBreak: Boolean = false,
    activeStartedElapsedMs: Long = 0L,
    activeDurationMs: Long = 0L,
    lockedStartedElapsedMs: Long = 0L,
    lockedDurationMs: Long = 0L
) = SessionState(
    packageName = PKG,
    isMonitored = isMonitored,
    isGrayzoneEnabled = isGrayzoneEnabled,
    activeUntil = activeUntil,
    lockedUntil = lockedUntil,
    budgetRemainingMs = budgetRemainingMs,
    sessionRemainingMs = sessionRemainingMs,
    defaultSessionMins = defaultSessionMins,
    defaultLockoutMins = defaultLockoutMins,
    waitSeconds = waitSeconds,
    isScheduleLocked = isScheduleLocked,
    isOnBreak = isOnBreak,
    activeStartedElapsedMs = activeStartedElapsedMs,
    activeDurationMs = activeDurationMs,
    lockedStartedElapsedMs = lockedStartedElapsedMs,
    lockedDurationMs = lockedDurationMs
)
