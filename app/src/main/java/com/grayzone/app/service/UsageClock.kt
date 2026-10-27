package com.grayzone.app.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-app foreground usage start times. Each app's time is recorded exactly once when it is
 * paused; a second pause (e.g. a heartbeat and a deferred recheck for the same app) records
 * nothing, and returning to an app before it was paused continues the same stint.
 */
class UsageClock {
    private val starts = ConcurrentHashMap<String, Long>()

    /** Starts timing [pkg] unless it is already being timed. */
    fun start(pkg: String, now: Long) {
        starts.putIfAbsent(pkg, now)
    }

    /** Stops timing [pkg] and returns the elapsed ms, or 0 if it wasn't being timed. */
    fun stop(pkg: String, now: Long): Long {
        val started = starts.remove(pkg) ?: return 0L
        return (now - started).coerceAtLeast(0L)
    }

    fun isRunning(pkg: String): Boolean = starts.containsKey(pkg)
}
