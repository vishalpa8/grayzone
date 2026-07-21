package com.grayzone.app.service.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the TLD-guarded parent walk shared by high-confidence sets and Bloom
 * filters. A regression that queries bare TLDs would let a Bloom collision
 * block the entire internet under that TLD.
 */
class DomainZoneMatcherTest {

    @Test
    fun `empty domain never matches`() {
        assertFalse(DomainZoneMatcher.anyMatch("") { true })
    }

    @Test
    fun `exact domain match wins`() {
        val set = setOf("doubleclick.net")
        assertTrue(DomainZoneMatcher.anyMatch("doubleclick.net") { it in set })
    }

    @Test
    fun `subdomain matches via parent zone`() {
        val set = setOf("doubleclick.net")
        assertTrue(DomainZoneMatcher.anyMatch("ads.doubleclick.net") { it in set })
        assertTrue(DomainZoneMatcher.anyMatch("a.b.doubleclick.net") { it in set })
    }

    @Test
    fun `bare tld is never queried as a parent`() {
        val queried = mutableListOf<String>()
        DomainZoneMatcher.anyMatch("ads.example.com") {
            queried.add(it)
            false
        }
        assertTrue(queried.contains("ads.example.com"))
        assertTrue(queried.contains("example.com"))
        assertFalse("bare TLD must never be queried", queried.contains("com"))
    }

    @Test
    fun `unrelated domain does not match`() {
        val set = setOf("doubleclick.net")
        assertFalse(DomainZoneMatcher.anyMatch("google.com") { it in set })
        assertFalse(DomainZoneMatcher.anyMatch("click.net") { it in set })
    }

    @Test
    fun `two-label domain does not walk into bare tld`() {
        val queried = mutableListOf<String>()
        DomainZoneMatcher.anyMatch("example.com") {
            queried.add(it)
            false
        }
        assertTrue(queried == listOf("example.com"))
    }

    @Test
    fun `deep nest stops at registrable-style parent not tld`() {
        val set = setOf("bad-ads.co.uk")
        assertTrue(DomainZoneMatcher.anyMatch("x.y.bad-ads.co.uk") { it in set })
    }
}
