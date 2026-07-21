package com.grayzone.app.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the VPN DNS routing decision order. Wrong priority here is a real leak:
 * DoH endpoints must get NXDOMAIN (not a generic ad sinkhole), and allowed
 * domains must never be blocked.
 */
class DnsQueryClassifierTest {

    private fun classify(
        domain: String,
        doh: Set<String> = emptySet(),
        blocked: Set<String> = emptySet()
    ) = DnsQueryClassifier.classify(
        rawDomain = domain,
        isDoHBypass = { it in doh },
        isBlocked = { it in blocked },
        normalize = BlocklistManager::normalizeDomain
    )

    // ─── Firefox canary ──────────────────────────────────────────────────────

    @Test
    fun `firefox doh canary is always nxdomain even before normalize`() {
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            classify("use-application-dns.net")
        )
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            classify("USE-APPLICATION-DNS.NET")
        )
    }

    @Test
    fun `firefox canary wins over a would-be forward`() {
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            classify("use-application-dns.net", blocked = emptySet(), doh = emptySet())
        )
    }

    // ─── DoH bypass ──────────────────────────────────────────────────────────

    @Test
    fun `known doh endpoint is nxdomain not sinkhole`() {
        // Critical: DoH is also "blocked", but must still classify as NXDOMAIN_DOH.
        val domain = "dns.google"
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            classify(domain, doh = setOf(domain), blocked = setOf(domain))
        )
    }

    @Test
    fun `doh check uses normalized form`() {
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            classify("DNS.Google.", doh = setOf("dns.google"))
        )
    }

    // ─── Ad / adult block ────────────────────────────────────────────────────

    @Test
    fun `blocked ad domain is sinkholed`() {
        assertEquals(
            DnsQueryClassifier.Action.SINKHOLE_BLOCK,
            classify("doubleclick.net", blocked = setOf("doubleclick.net"))
        )
    }

    @Test
    fun `blocked path is not taken when normalize rejects the input`() {
        // IP literals normalize to null → must FORWARD (never block blindly).
        assertEquals(
            DnsQueryClassifier.Action.FORWARD,
            classify("8.8.8.8", blocked = setOf("8.8.8.8"), doh = setOf("8.8.8.8"))
        )
    }

    // ─── Allow / forward ─────────────────────────────────────────────────────

    @Test
    fun `clean domain is forwarded`() {
        assertEquals(
            DnsQueryClassifier.Action.FORWARD,
            classify("example.com")
        )
    }

    @Test
    fun `empty and garbage domains are forwarded not crashed`() {
        assertEquals(DnsQueryClassifier.Action.FORWARD, classify(""))
        assertEquals(DnsQueryClassifier.Action.FORWARD, classify("localhost"))
        assertEquals(DnsQueryClassifier.Action.FORWARD, classify("not a domain"))
    }

    // ─── Live BlocklistManager wiring ────────────────────────────────────────

    @Test
    fun `live classifier blocks high-confidence ads via real BlocklistManager`() {
        assertEquals(
            DnsQueryClassifier.Action.SINKHOLE_BLOCK,
            DnsQueryClassifier.classify("doubleclick.net")
        )
        assertEquals(
            DnsQueryClassifier.Action.SINKHOLE_BLOCK,
            DnsQueryClassifier.classify("ads.doubleclick.net")
        )
    }

    @Test
    fun `live classifier nxdomains known doh resolvers`() {
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            DnsQueryClassifier.classify("dns.google")
        )
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            DnsQueryClassifier.classify("cloudflare-dns.com")
        )
        assertEquals(
            DnsQueryClassifier.Action.NXDOMAIN_DOH,
            DnsQueryClassifier.classify("dns.nextdns.io")
        )
    }

    @Test
    fun `live classifier forwards ordinary sites`() {
        assertEquals(
            DnsQueryClassifier.Action.FORWARD,
            DnsQueryClassifier.classify("example.com")
        )
        assertEquals(
            DnsQueryClassifier.Action.FORWARD,
            DnsQueryClassifier.classify("wikipedia.org")
        )
    }
}
