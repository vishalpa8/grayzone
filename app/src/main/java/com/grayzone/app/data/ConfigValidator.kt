package com.grayzone.app.data

/**
 * Pure validation/repair for the numeric preference values that gate the wellbeing
 * engine. Out-of-range values (corrupt prefs, older builds, tampering) are clamped
 * back to safe defaults. Extracted from GrayzoneApplication so the repair policy is
 * unit-testable without an Android Context.
 */
object ConfigValidator {

    const val DEFAULT_WAIT_SECONDS = 5
    const val DEFAULT_SESSION_MINUTES = 10
    const val DEFAULT_LOCKOUT_MINUTES = 60

    val WAIT_SECONDS_RANGE = 3..30
    val SESSION_MINUTES_RANGE = 1..60
    val LOCKOUT_MINUTES_RANGE = 15..(5 * 60)

    fun repairWaitSeconds(value: Int): Int =
        if (value in WAIT_SECONDS_RANGE) value else DEFAULT_WAIT_SECONDS

    fun repairSessionMinutes(value: Int): Int =
        if (value in SESSION_MINUTES_RANGE) value else DEFAULT_SESSION_MINUTES

    fun repairLockoutMinutes(value: Int): Int =
        if (value in LOCKOUT_MINUTES_RANGE) value else DEFAULT_LOCKOUT_MINUTES

    /** True when [value] is outside the accepted range and would be repaired. */
    fun isWaitSecondsValid(value: Int): Boolean = value in WAIT_SECONDS_RANGE
    fun isSessionMinutesValid(value: Int): Boolean = value in SESSION_MINUTES_RANGE
    fun isLockoutMinutesValid(value: Int): Boolean = value in LOCKOUT_MINUTES_RANGE
}
