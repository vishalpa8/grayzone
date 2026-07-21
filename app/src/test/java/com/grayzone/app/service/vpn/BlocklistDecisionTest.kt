package com.grayzone.app.service.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aggressive coverage of [BlocklistManager.isBlocked] / [BlocklistManager.isDoHBypass]
 * without requiring bloom filter assets to be loaded.
 *
 * Covers the pre-load safety net (high-confidence + DoH) — the path that must
 * never leak even when bloom loading fails or is still in flight.
 */
class BlocklistDecisionTest {

    // ─── DoH bypass list ─────────────────────────────────────────────────────

    @Test
    fun `major doh endpoints are blocked as bypass`() {
        val endpoints = listOf(
            "dns.google",
            "dns.google.com",
            "cloudflare-dns.com",
            "1dot1dot1dot1.cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "dns.quad9.net",
            "dns.adguard.com",
            "dns.nextdns.io",
            "doh.opendns.com",
            "dns.alidns.com",
            "doh.pub",
            "dns.mullvad.net",
            "use-application-dns.net"
        )
        endpoints.forEach { host ->
            assertTrue("$host must be a DoH bypass", BlocklistManager.isDoHBypass(host))
            assertTrue("$host must be blocked", BlocklistManager.isBlocked(host))
        }
    }

    @Test
    fun `ordinary sites are not doh bypass`() {
        assertFalse(BlocklistManager.isDoHBypass("google.com"))
        assertFalse(BlocklistManager.isDoHBypass("example.com"))
        assertFalse(BlocklistManager.isDoHBypass("github.com"))
    }

    @Test
    fun `doh check rejects ip literals and garbage`() {
        assertFalse(BlocklistManager.isDoHBypass("8.8.8.8"))
        assertFalse(BlocklistManager.isDoHBypass(""))
        assertFalse(BlocklistManager.isDoHBypass("localhost"))
    }

    // ─── High-confidence ads / adult (works before bloom load) ───────────────

    @Test
    fun `high-confidence ad domains are blocked before bloom load`() {
        listOf(
            "doubleclick.net",
            "adservice.google.com",
            "googleadservices.com",
            "googlesyndication.com",
            "amazon-adsystem.com",
            "taboola.com",
            "criteo.com"
        ).forEach { host ->
            assertTrue("$host must be blocked", BlocklistManager.isBlocked(host))
        }
    }

    @Test
    fun `high-confidence adult domains are blocked before bloom load`() {
        listOf("pornhub.com", "xvideos.com", "xhamster.com", "onlyfans.com").forEach { host ->
            assertTrue("$host must be blocked", BlocklistManager.isBlocked(host))
        }
    }

    @Test
    fun `subdomains of high-confidence domains are blocked via parent walk`() {
        assertTrue(BlocklistManager.isBlocked("ads.doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("pagead2.googlesyndication.com"))
        assertTrue(BlocklistManager.isBlocked("www.pornhub.com"))
    }

    @Test
    fun `unrelated ordinary domains are not blocked before bloom load`() {
        // Without bloom filters loaded, unknown domains must pass (no false block).
        // High-confidence and DoH are the only pre-load blocks.
        assertFalse(BlocklistManager.isBlocked("example.com"))
        assertFalse(BlocklistManager.isBlocked("wikipedia.org"))
        assertFalse(BlocklistManager.isBlocked("github.com"))
        assertFalse(BlocklistManager.isBlocked("developer.android.com"))
    }

    @Test
    fun `case and trailing-dot variants of blocked domains still block`() {
        assertTrue(BlocklistManager.isBlocked("DoubleClick.NET"))
        assertTrue(BlocklistManager.isBlocked("dns.google."))
        assertTrue(BlocklistManager.isBlocked("  PORNHUB.COM  "))
    }

    @Test
    fun `partial name similarity is not enough to block`() {
        // Must not treat substring-ish hosts as matches.
        assertFalse(BlocklistManager.isBlocked("notdoubleclick.net"))
        assertFalse(BlocklistManager.isBlocked("doubleclick.net.evil.example"))
        assertFalse(BlocklistManager.isBlocked("mydns.google.example.com"))
    }
}
