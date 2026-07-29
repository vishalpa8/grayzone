package com.grayzone.app.service.vpn

import java.util.concurrent.TimeUnit

/**
 * Pure 24-hour hold: once VPN is enabled, user cannot disable until the hold elapses;
 * then the service auto-disables. No PIN.
 *
 * Remaining time prefers [State.startedElapsedMs] so wall-clock changes cannot skip the wait;
 * after reboot (elapsed clock resets), falls back to wall [State.startedWallMs].
 */
object VpnHoldLock {

    val HOLD_MS: Long = TimeUnit.HOURS.toMillis(24)

    data class State(
        /** Wall-clock epoch when VPN was enabled; 0 = no active hold. */
        val startedWallMs: Long,
        /** [android.os.SystemClock.elapsedRealtime] when enabled; 0 = none. */
        val startedElapsedMs: Long
    ) {
        val isTracked: Boolean get() = startedWallMs > 0L
    }

    fun remainingMs(state: State, nowWallMs: Long, nowElapsedMs: Long): Long {
        if (!state.isTracked) return 0L
        val started = state.startedElapsedMs
        val rebooted = started > 0L && nowElapsedMs < started
        return if (!rebooted && started > 0L) {
            (started + HOLD_MS - nowElapsedMs).coerceAtLeast(0L)
        } else {
            (state.startedWallMs + HOLD_MS - nowWallMs).coerceAtLeast(0L)
        }
    }

    fun isHoldActive(state: State, nowWallMs: Long, nowElapsedMs: Long): Boolean =
        remainingMs(state, nowWallMs, nowElapsedMs) > 0L

    /** User-initiated disable is only allowed when there is no active hold. */
    fun canUserDisable(state: State, nowWallMs: Long, nowElapsedMs: Long): Boolean =
        !isHoldActive(state, nowWallMs, nowElapsedMs)

    fun expiresAtWallMs(state: State): Long =
        if (!state.isTracked) 0L else state.startedWallMs + HOLD_MS

    fun formatRemaining(remainingMs: Long): String {
        val totalMins = TimeUnit.MILLISECONDS.toMinutes(remainingMs.coerceAtLeast(0)).coerceAtLeast(1)
        val hours = totalMins / 60
        val mins = totalMins % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }
}
