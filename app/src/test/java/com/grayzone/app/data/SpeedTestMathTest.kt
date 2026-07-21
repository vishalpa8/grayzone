package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestMathTest {

    @Test
    fun `download mbps is zero when duration is zero`() {
        assertEquals(0.0, SpeedTestRunner.downloadMbps(1_000_000, 0), 0.0)
    }

    @Test
    fun `download mbps uses bits over milliseconds`() {
        // 1_250_000 bytes in 1000 ms → 10 Mbps
        // (bytes * 8) / (ms * 1000) = 10_000_000 / 1_000_000 = 10
        assertEquals(10.0, SpeedTestRunner.downloadMbps(1_250_000, 1_000), 0.0001)
    }

    @Test
    fun `small transfer produces fractional mbps`() {
        val mbps = SpeedTestRunner.downloadMbps(125_000, 1_000) // 1 Mbps
        assertEquals(1.0, mbps, 0.0001)
    }
}
