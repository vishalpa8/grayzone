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
    isScheduleLocked: Boolean = false,
    isOnBreak: Boolean = false
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
    isScheduleLocked = isScheduleLocked,
    isOnBreak = isOnBreak
)
