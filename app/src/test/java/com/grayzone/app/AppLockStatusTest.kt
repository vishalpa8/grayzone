package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Apps tab, the Limits dashboard and the lock overlay all read from [statusAt], so these
 * pin the one definition they share. Three surfaces disagreeing about whether an app was locked
 * (and for how long) is what the split derivations used to produce.
 */
class AppLockStatusTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `running session reports active with the session remainder`() {
        val s = AppLockState(activeUntil = now + 7 * minute).statusAt(now)
        assertEquals(AppRuntimeStatus.ACTIVE, s.status)
        assertEquals(7L, s.remainingMinutes)
    }

    @Test
    fun `banked remainder reports paused, not locked`() {
        val s = AppLockState(remainingMillis = 9 * minute).statusAt(now)
        assertEquals(AppRuntimeStatus.PAUSED, s.status)
        assertEquals(9L, s.remainingMinutes)
    }

    @Test
    fun `a fresh two hour lockout reads as 120 minutes, not 119`() {
        val s = AppLockState(lockedUntil = now + 120 * minute).statusAt(now)
        assertEquals(AppRuntimeStatus.LOCKED, s.status)
        assertEquals(120L, s.remainingMinutes)
    }

    @Test
    fun `an active session wins over a stale lockout, matching the policy engine`() {
        val s = AppLockState(activeUntil = now + minute, lockedUntil = now + 90 * minute).statusAt(now)
        assertEquals(AppRuntimeStatus.ACTIVE, s.status)
    }

    @Test
    fun `an expired lockout falls back to ready`() {
        val s = AppLockState(lockedUntil = now - 1).statusAt(now)
        assertEquals(AppRuntimeStatus.READY, s.status)
        assertEquals(0L, s.remainingMinutes)
    }

    @Test
    fun `an oversized banked remainder is treated as stale`() {
        val s = AppLockState(remainingMillis = 48 * 60 * minute).statusAt(now)
        assertEquals(AppRuntimeStatus.READY, s.status)
    }
}
