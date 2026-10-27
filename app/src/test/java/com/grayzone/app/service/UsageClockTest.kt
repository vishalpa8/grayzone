package com.grayzone.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageClockTest {

    @Test
    fun `stop returns the time since start once`() {
        val clock = UsageClock()
        clock.start("a", 1_000)
        assertEquals(4_000, clock.stop("a", 5_000))
        assertEquals("a second pause records nothing", 0L, clock.stop("a", 9_000))
    }

    @Test
    fun `returning before the pause continues the same stint`() {
        val clock = UsageClock()
        clock.start("a", 1_000)
        clock.start("a", 3_000) // came back while still being timed
        assertEquals(9_000, clock.stop("a", 10_000))
    }

    @Test
    fun `apps are timed independently`() {
        val clock = UsageClock()
        clock.start("a", 0)
        clock.start("b", 5_000)
        assertEquals(6_000, clock.stop("a", 6_000))
        assertTrue(clock.isRunning("b"))
        assertFalse(clock.isRunning("a"))
    }

    @Test
    fun `clock going backwards never yields negative usage`() {
        val clock = UsageClock()
        clock.start("a", 10_000)
        assertEquals(0L, clock.stop("a", 5_000))
    }
}
