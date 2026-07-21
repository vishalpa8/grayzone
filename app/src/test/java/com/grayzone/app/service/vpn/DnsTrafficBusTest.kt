package com.grayzone.app.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bounded in-memory DNS event feed used by the Network Tools UI.
 * Capacity and ordering leaks would either OOM or show stale traffic.
 */
class DnsTrafficBusTest {

    @Before
    fun clearBus() {
        DnsTrafficBus.clear()
    }

    @Test
    fun `emit prepends newest events`() {
        DnsTrafficBus.emit(event("a.com", DnsTrafficBus.DnsEvent.Status.ALLOWED))
        DnsTrafficBus.emit(event("b.com", DnsTrafficBus.DnsEvent.Status.BLOCKED_AD))

        val domains = DnsTrafficBus.events.value.map { it.domain }
        assertEquals(listOf("b.com", "a.com"), domains)
    }

    @Test
    fun `capacity is hard-capped at MAX_EVENTS`() {
        repeat(DnsTrafficBus.MAX_EVENTS + 25) { i ->
            DnsTrafficBus.emit(event("host$i.example", DnsTrafficBus.DnsEvent.Status.ALLOWED))
        }
        assertEquals(DnsTrafficBus.MAX_EVENTS, DnsTrafficBus.events.value.size)
        // Newest must survive at the front.
        assertEquals("host${DnsTrafficBus.MAX_EVENTS + 24}.example", DnsTrafficBus.events.value.first().domain)
        // Oldest of the retained window is still present; anything older is gone.
        assertTrue(DnsTrafficBus.events.value.none { it.domain == "host0.example" })
    }

    @Test
    fun `clear empties the feed`() {
        DnsTrafficBus.emit(event("x.com", DnsTrafficBus.DnsEvent.Status.BLOCKED_DOH))
        DnsTrafficBus.clear()
        assertTrue(DnsTrafficBus.events.value.isEmpty())
    }

    @Test
    fun `status values cover allow ad and doh`() {
        DnsTrafficBus.emit(event("ok.com", DnsTrafficBus.DnsEvent.Status.ALLOWED))
        DnsTrafficBus.emit(event("ad.com", DnsTrafficBus.DnsEvent.Status.BLOCKED_AD))
        DnsTrafficBus.emit(event("doh.com", DnsTrafficBus.DnsEvent.Status.BLOCKED_DOH))
        val statuses = DnsTrafficBus.events.value.map { it.status }.toSet()
        assertEquals(
            setOf(
                DnsTrafficBus.DnsEvent.Status.ALLOWED,
                DnsTrafficBus.DnsEvent.Status.BLOCKED_AD,
                DnsTrafficBus.DnsEvent.Status.BLOCKED_DOH
            ),
            statuses
        )
    }

    private fun event(domain: String, status: DnsTrafficBus.DnsEvent.Status) =
        DnsTrafficBus.DnsEvent(domain, status, timestampMs = 1L)
}
