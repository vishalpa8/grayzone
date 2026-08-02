package com.grayzone.app.policy

/**
 * Dual-clock deadline checks so wall-clock changes cannot skip a wait.
 *
 * Mirrors [com.grayzone.app.service.vpn.VpnHoldLock]: prefer elapsedRealtime-based remaining
 * when a start timestamp was recorded; after reboot (elapsed clock resets below the start)
 * fall back to the wall-clock deadline.
 */
object MonotonicDeadline {

    /**
     * True while the deadline has not yet elapsed.
     *
     * @param untilWallMs absolute wall-clock deadline (0 = none)
     * @param startedElapsedMs [android.os.SystemClock.elapsedRealtime] when the deadline was set (0 = wall only)
     * @param durationMs length of the wait from [startedElapsedMs] (0 = wall only)
     * @param nowWallMs current [System.currentTimeMillis]
     * @param nowElapsedMs current elapsedRealtime
     */
    fun isPending(
        untilWallMs: Long,
        startedElapsedMs: Long,
        durationMs: Long,
        nowWallMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        if (untilWallMs <= 0L) return false
        if (startedElapsedMs > 0L && durationMs > 0L) {
            val rebooted = nowElapsedMs < startedElapsedMs
            if (!rebooted) {
                return nowElapsedMs < startedElapsedMs + durationMs
            }
        }
        return nowWallMs < untilWallMs
    }

    fun remainingMs(
        untilWallMs: Long,
        startedElapsedMs: Long,
        durationMs: Long,
        nowWallMs: Long,
        nowElapsedMs: Long
    ): Long {
        if (untilWallMs <= 0L) return 0L
        if (startedElapsedMs > 0L && durationMs > 0L) {
            val rebooted = nowElapsedMs < startedElapsedMs
            if (!rebooted) {
                return (startedElapsedMs + durationMs - nowElapsedMs).coerceAtLeast(0L)
            }
        }
        return (untilWallMs - nowWallMs).coerceAtLeast(0L)
    }
}
