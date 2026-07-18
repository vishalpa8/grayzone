package com.grayzone.app

/**
 * Runtime lock state for a single monitored app.
 * Replaces the error-prone Triple<Long, Long, Long> pattern used in lockStateMap —
 * named fields make destructuring order mistakes impossible.
 */
data class AppLockState(
    val activeUntil: Long    = 0L,
    val lockedUntil: Long    = 0L,
    val remainingMillis: Long = 0L
)
