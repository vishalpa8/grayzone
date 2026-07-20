package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateLogicTest {
    @Test
    fun `paused remaining time is preserved only within the 24 hour window`() {
        val maxPausedMillis = 24L * 60 * 60 * 1000L

        assertEquals(1L, getNormalizedRemainingMillis(1L))
        assertEquals(maxPausedMillis - 1L, getNormalizedRemainingMillis(maxPausedMillis - 1L))
        assertEquals(0L, getNormalizedRemainingMillis(0L))
        assertEquals(0L, getNormalizedRemainingMillis(maxPausedMillis))
        assertEquals(0L, getNormalizedRemainingMillis(maxPausedMillis + 1L))
    }
}
