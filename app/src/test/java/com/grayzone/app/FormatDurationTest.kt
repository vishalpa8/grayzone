package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {

    @Test
    fun `formats hours minutes and seconds`() {
        assertEquals("2h 1m 5s", formatDuration(7265))
        assertEquals("1h 0m 0s", formatDuration(3600))
    }

    @Test
    fun `formats under one hour without hours`() {
        assertEquals("3m 5s", formatDuration(185))
        assertEquals("0m 45s", formatDuration(45))
        assertEquals("0m 0s", formatDuration(0))
    }
}
