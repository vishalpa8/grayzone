package com.grayzone.app.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThreatProtectionTest {

    private val rawDir = File("src/main/res/raw")

    // ─── Critical-name guard ─────────────────────────────────────────────────

    @Test
    fun `a filter hit on a protected parent never blocks its subdomains`() {
        val listed = setOf("google.com")
        assertFalse(DomainZoneMatcher.anyListedMatch("mail.google.com", setOf("google.com")) { it in listed })
        assertFalse(DomainZoneMatcher.anyListedMatch("google.com", setOf("google.com")) { it in listed })
    }

    @Test
    fun `a listed tenant of a protected platform is still blocked`() {
        val listed = setOf("evil.web.app", "web.app")
        assertTrue(DomainZoneMatcher.anyListedMatch("evil.web.app", setOf("web.app")) { it in listed })
        assertTrue(DomainZoneMatcher.anyListedMatch("cdn.evil.web.app", setOf("web.app")) { it in listed })
        assertFalse(DomainZoneMatcher.anyListedMatch("good.web.app", setOf("web.app")) { it in listed })
    }

    @Test
    fun `domain list parsing skips comments and garbage`() {
        val parsed = BlocklistManager.parseDomainList(
            "# header\n\nGoogle.com\nweb.app # shared hosting\n  \nlocalhost\n8.8.8.8\n"
        )
        assertEquals(setOf("google.com", "web.app"), parsed)
    }

    // ─── Shipped resources ───────────────────────────────────────────────────

    private val critical by lazy {
        BlocklistManager.parseDomainList(File(rawDir, "critical_domains.txt").readText())
    }

    @Test
    fun `critical list is substantial and never protects doh resolvers or ad servers`() {
        assertTrue(critical.size >= 50)
        listOf("dns.google", "cloudflare-dns.com", "dns.quad9.net", "doubleclick.net", "googlesyndication.com")
            .forEach { assertFalse("$it must not be protected", it in critical) }
        critical.forEach { assertFalse("$it is a DoH resolver", BlocklistManager.isDoHBypass(it)) }
    }

    private fun load(name: String) = File(rawDir, name).inputStream().use { GrayzoneBloomFilter.readFrom(it) }

    @Test
    fun `security filter ships and keeps ordinary sites clear`() {
        val security = load("security_bloom.bin")
        listOf("example.com", "wikipedia.org", "kotlinlang.org", "stackoverflow.com", "mozilla.org")
            .forEach { assertFalse("$it must not be a threat", security.mightContain(it)) }
    }

    @Test
    fun `every shipped filter loads`() {
        listOf("adblock_bloom.bin", "adult_bloom.bin", "security_bloom.bin").forEach { load(it) }
    }

    @Test
    fun `built-in manifest matches every shipped list`() {
        val files = com.grayzone.app.service.vpn.update.BlocklistUpdateConfig.FILES
        val manifest = com.grayzone.app.service.vpn.update.BlocklistManifest.parse(
            File(rawDir, "blocklist_manifest.txt").readText(), files
        )
        assertTrue("blocklist_manifest.txt must list every file", manifest != null)
        files.forEach { name ->
            val bytes = File(rawDir, name).readBytes()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val entry = manifest!!.files.getValue(name)
            assertEquals("$name changed without regenerating blocklist_manifest.txt", entry.sha256, sha)
            assertEquals(entry.size, bytes.size.toLong())
        }
    }

    @Test
    fun `shipped doh and telemetry lists are substantial`() {
        assertTrue(BlocklistManager.parseDomainList(File(rawDir, "doh_domains.txt").readText()).size >= 500)
        assertTrue(BlocklistManager.parseDomainList(File(rawDir, "telemetry_domains.txt").readText()).size >= 200)
    }

    @Test
    fun `abused tld list does not swallow protected names`() {
        val tlds = File(rawDir, "abused_tlds.txt").inputStream().use { AbusedTldList.readFrom(it) }
        // .bike is abused, but Rapido lives there: only the zone-wide critical guard keeps it working.
        assertTrue(tlds.blocks("rapido.bike"))
        assertTrue("rapido.bike" in critical)
    }
}
