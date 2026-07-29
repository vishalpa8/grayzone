package com.grayzone.app.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class VpnHoldLockTest {

    private val hold = VpnHoldLock.HOLD_MS

    @Test
    fun remaining_prefersElapsedClock() {
        val startWall = 1_000_000L
        val startElapsed = 50_000L
        val state = VpnHoldLock.State(startWall, startElapsed)
        val nowElapsed = startElapsed + TimeUnit.HOURS.toMillis(6)
        val remaining = VpnHoldLock.remainingMs(state, startWall + TimeUnit.HOURS.toMillis(6), nowElapsed)
        assertEquals(TimeUnit.HOURS.toMillis(18), remaining)
    }

    @Test
    fun remaining_fallsBackToWallAfterReboot() {
        val startWall = 1_000_000L
        val startElapsed = 50_000L
        val state = VpnHoldLock.State(startWall, startElapsed)
        // Elapsed clock reset after reboot (nowElapsed < started)
        val nowWall = startWall + TimeUnit.HOURS.toMillis(6)
        val remaining = VpnHoldLock.remainingMs(state, nowWall, nowElapsedMs = 100L)
        assertEquals(TimeUnit.HOURS.toMillis(18), remaining)
    }

    @Test
    fun canUserDisable_falseWhileActive() {
        val state = VpnHoldLock.State(1_000L, 1_000L)
        assertFalse(VpnHoldLock.canUserDisable(state, 1_000L, 1_000L))
        assertTrue(VpnHoldLock.isHoldActive(state, 1_000L, 1_000L))
    }

    @Test
    fun canUserDisable_trueWhenExpired() {
        val state = VpnHoldLock.State(1_000L, 1_000L)
        val later = 1_000L + hold
        assertTrue(VpnHoldLock.canUserDisable(state, later, 1_000L + hold))
        assertEquals(0L, VpnHoldLock.remainingMs(state, later, 1_000L + hold))
    }

    @Test
    fun canUserDisable_trueWhenNotTracked() {
        val state = VpnHoldLock.State(0L, 0L)
        assertTrue(VpnHoldLock.canUserDisable(state, 5_000L, 5_000L))
    }

    @Test
    fun expiresAtWall() {
        val state = VpnHoldLock.State(10_000L, 1L)
        assertEquals(10_000L + hold, VpnHoldLock.expiresAtWallMs(state))
    }
}
