package com.grayzone.app.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aggressive coverage of [BlocklistManager.normalizeDomain] — the gate every
 * DNS decision passes through. A leak here (accepting IPs / single labels /
 * garbage) would either crash matching or let bypass traffic through.
 */
class DomainNormalizationTest {

    @Test
    fun `happy path lowercases and strips trailing dot`() {
        assertEquals("example.com", BlocklistManager.normalizeDomain("Example.COM"))
        assertEquals("example.com", BlocklistManager.normalizeDomain("example.com."))
        assertEquals("example.com", BlocklistManager.normalizeDomain("  Example.Com.  "))
    }

    @Test
    fun `subdomains and multi-label domains are preserved`() {
        assertEquals("a.b.example.co.uk", BlocklistManager.normalizeDomain("A.B.Example.CO.UK"))
    }

    @Test
    fun `empty whitespace and space-containing strings are rejected`() {
        assertNull(BlocklistManager.normalizeDomain(""))
        assertNull(BlocklistManager.normalizeDomain("   "))
        assertNull(BlocklistManager.normalizeDomain("."))
        assertNull(BlocklistManager.normalizeDomain("bad domain.com"))
    }

    @Test
    fun `single-label hosts are rejected`() {
        assertNull(BlocklistManager.normalizeDomain("localhost"))
        assertNull(BlocklistManager.normalizeDomain("com"))
        assertNull(BlocklistManager.normalizeDomain("intranet"))
    }

    @Test
    fun `ipv4 literals are rejected`() {
        assertNull(BlocklistManager.normalizeDomain("8.8.8.8"))
        assertNull(BlocklistManager.normalizeDomain("127.0.0.1"))
        assertNull(BlocklistManager.normalizeDomain("192.168.1.1"))
    }

    @Test
    fun `ipv6 literals are rejected`() {
        assertNull(BlocklistManager.normalizeDomain("2001:db8::1"))
        assertNull(BlocklistManager.normalizeDomain("::1"))
        assertNull(BlocklistManager.normalizeDomain("[2001:db8::1]"))
    }

    @Test
    fun `normalization is idempotent via cache`() {
        val first = BlocklistManager.normalizeDomain("Cache.Me.Example")
        val second = BlocklistManager.normalizeDomain("Cache.Me.Example")
        assertEquals(first, second)
        assertEquals("cache.me.example", first)
    }

    @Test
    fun `null-cached rejections stay rejected`() {
        assertNull(BlocklistManager.normalizeDomain("8.8.8.8"))
        assertNull(BlocklistManager.normalizeDomain("8.8.8.8"))
    }

    @Test
    fun `punycode idn domains are accepted as-is`() {
        assertEquals("xn--mnchen-3ya.de", BlocklistManager.normalizeDomain("xn--mnchen-3ya.de"))
    }
}
