package com.grayzone.app.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamRankingTest {

    @Test
    fun `configured order is used until there are measurements`() {
        assertEquals(listOf("cf", "q9"), UpstreamRanking(listOf("cf", "q9")).ordered())
    }

    @Test
    fun `the faster server moves to the front`() {
        val r = UpstreamRanking(listOf("q9", "cf"))
        repeat(5) {
            r.recordSuccess("q9", 370)
            r.recordSuccess("cf", 20)
        }
        assertEquals("cf", r.ordered().first())
    }

    @Test
    fun `timeouts sink a server and good answers bring it back`() {
        val r = UpstreamRanking(listOf("cf", "q9"))
        repeat(3) { r.recordSuccess("cf", 20); r.recordSuccess("q9", 60) }
        r.recordFailure("cf")
        assertEquals("q9", r.ordered().first())
        repeat(20) { r.recordSuccess("cf", 20) }
        assertEquals("cf", r.ordered().first())
    }

    @Test
    fun `hedge delay follows the fastest server within bounds`() {
        val r = UpstreamRanking(listOf("cf"))
        repeat(20) { r.recordSuccess("cf", 20) }
        assertEquals(60L, r.hedgeDelayMs()) // 3 x ~20 ms, floored at 60
        repeat(20) { r.recordSuccess("cf", 100) }
        assertTrue(r.hedgeDelayMs() in 250L..400L)
        repeat(20) { r.recordFailure("cf") }
        assertEquals(400L, r.hedgeDelayMs())
    }

    @Test
    fun `round trip time is bounded after repeated failures`() {
        val r = UpstreamRanking(listOf("dead"))
        repeat(50) { r.recordFailure("dead") }
        assertTrue(r.rttOf("dead")!! <= 10_000L)
    }

    @Test
    fun `unknown servers are ignored`() {
        val r = UpstreamRanking(listOf("cf"))
        r.recordSuccess("other", 1)
        r.recordFailure("other")
        assertEquals(listOf("cf"), r.ordered())
    }
}
