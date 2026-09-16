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

enum class AppRuntimeStatus { READY, ACTIVE, PAUSED, LOCKED }

/**
 * What a monitored app's runtime state means right now, plus the countdown that goes with it.
 *
 * Every surface that shows a timer — Apps tab, Limits dashboard, the lock overlay — derives it
 * from here, so they cannot disagree about whether an app is locked or about how long is left.
 */
data class AppStatusSnapshot(
    val status: AppRuntimeStatus,
    val remainingMs: Long
) {
    /** Whole minutes left, rounded up, so a fresh 120-minute lockout reads "120m" not "119m". */
    val remainingMinutes: Long
        get() = if (remainingMs <= 0L) 0L else ((remainingMs + 59_999L) / 60_000L)
}

/**
 * Resolves [AppLockState] into a status at [now], mirroring the gate order that
 * `SessionPolicyEngine` uses for a foregrounded app: an active session beats a lockout, which
 * beats a banked remainder.
 */
fun AppLockState.statusAt(now: Long): AppStatusSnapshot {
    val banked = getNormalizedRemainingMillis(remainingMillis)
    return when {
        activeUntil > 0L && now < activeUntil ->
            AppStatusSnapshot(AppRuntimeStatus.ACTIVE, activeUntil - now)
        lockedUntil > 0L && now < lockedUntil ->
            AppStatusSnapshot(AppRuntimeStatus.LOCKED, lockedUntil - now)
        banked > 0L ->
            AppStatusSnapshot(AppRuntimeStatus.PAUSED, banked)
        else -> AppStatusSnapshot(AppRuntimeStatus.READY, 0L)
    }
}
