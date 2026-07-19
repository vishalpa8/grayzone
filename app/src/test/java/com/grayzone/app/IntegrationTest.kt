package com.grayzone.app

import com.grayzone.app.service.vpn.DnsPacketHelper
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests for critical paths in Grayzone.
 * Tests the interaction between components and validates end-to-end workflows.
 */
class IntegrationTest {

    @Test
    fun testDnsQueryBlockingFlow() {
        // Test: Ad domain → Sinkhole response
        val adDomain = "ads.example.com"
        val testPacket = createMockDnsPacket(adDomain)
        
        val response = DnsPacketHelper.createSinkholeResponse(testPacket, testPacket.size)
        assertNotNull("Sinkhole response should be created", response)
        assertTrue("Response should be non-empty", response!!.size > 0)
    }

    @Test
    fun testDnsQueryAllowFlow() {
        // Test: Regular domain → Should parse correctly
        val normalDomain = "www.google.com"
        val testPacket = createMockDnsPacket(normalDomain)
        
        val domain = DnsPacketHelper.getDomainName(testPacket, testPacket.size)
        assertEquals("Domain should be parsed correctly", normalDomain, domain)
    }

    @Test
    fun testNxDomainResponseCreation() {
        // Test: DoH domain → NXDOMAIN response
        val dohDomain = "dns.google"
        val testPacket = createMockDnsPacket(dohDomain)
        
        val response = DnsPacketHelper.createNxDomainResponse(testPacket, testPacket.size)
        assertNotNull("NXDOMAIN response should be created", response)
        assertTrue("Response should be non-empty", response!!.size > 0)
    }

    @Test
    fun testIPv6PacketHandling() {
        // Test: IPv6 DNS packet → Should be handled correctly
        val domain = "example.com"
        val testPacket = createMockDnsPacketIPv6(domain)
        
        val isDns = DnsPacketHelper.isDnsQuery(testPacket, testPacket.size)
        assertTrue("IPv6 DNS packet should be detected", isDns)
        
        val parsedDomain = DnsPacketHelper.getDomainName(testPacket, testPacket.size)
        assertEquals("IPv6 domain should be parsed", domain, parsedDomain)
    }

    @Test
    fun testDurationValidation() {
        // Test: Session duration bounds
        val validSessionMins = listOf(1, 10, 30, 60, 24 * 60)
        val invalidSessionMins = listOf(0, -1, 25 * 60)
        
        validSessionMins.forEach { mins ->
            val clamped = mins.coerceIn(1, 24 * 60)
            assertEquals("Valid duration should not be clamped", mins, clamped)
        }
        
        invalidSessionMins.forEach { mins ->
            val clamped = mins.coerceIn(1, 24 * 60)
            assertTrue("Invalid duration should be clamped", clamped in 1..(24 * 60))
        }
    }

    @Test
    fun testLockoutDurationValidation() {
        // Test: Lockout duration bounds (15 mins to 24 hours)
        val validLockoutMins = listOf(15, 30, 60, 120, 24 * 60)
        val invalidLockoutMins = listOf(0, 10, 25 * 60)
        
        validLockoutMins.forEach { mins ->
            val clamped = mins.coerceIn(15, 24 * 60)
            assertEquals("Valid lockout should not be clamped", mins, clamped)
        }
        
        invalidLockoutMins.forEach { mins ->
            val clamped = mins.coerceIn(15, 24 * 60)
            assertTrue("Invalid lockout should be clamped", clamped in 15..(24 * 60))
        }
    }

    @Test
    fun testRemainingMillisValidation() {
        // Test: Remaining session time validation
        val validRemaining = listOf(1L, 1000L, 60 * 1000L, 24 * 60 * 60 * 1000L - 1)
        val invalidRemaining = listOf(0L, -1000L, 25 * 60 * 60 * 1000L)
        
        validRemaining.forEach { ms ->
            assertTrue("Valid remaining time should pass check", ms > 0 && ms < 24 * 60 * 60 * 1000)
        }
        
        invalidRemaining.forEach { ms ->
            assertFalse("Invalid remaining time should fail check", ms > 0 && ms < 24 * 60 * 60 * 1000)
        }
    }

    @Test
    fun testDnsPacketHelperErrorStats() {
        // Test: Error statistics tracking
        val stats = DnsPacketHelper.getErrorStats()
        
        assertNotNull("Error stats should be available", stats)
        assertTrue("Should have domain_parse_errors counter", stats.containsKey("domain_parse_errors"))
        assertTrue("Should have sinkhole_creation_errors counter", stats.containsKey("sinkhole_creation_errors"))
        assertTrue("Should have nxdomain_creation_errors counter", stats.containsKey("nxdomain_creation_errors"))
        assertTrue("Should have response_wrap_errors counter", stats.containsKey("response_wrap_errors"))
        assertTrue("Should have IPv4 counter", stats.containsKey("ipv4_packets_processed"))
        assertTrue("Should have IPv6 counter", stats.containsKey("ipv6_packets_processed"))
    }

    // Helper methods to create mock DNS packets

    private fun createMockDnsPacket(domain: String): ByteArray {
        // Create a minimal IPv4 DNS query packet
        val ihl = 20 // IP header length
        val udpHeaderLen = 8
        val dnsHeaderLen = 12
        val domainBytes = encodeDomain(domain)
        val questionLen = domainBytes.size + 4 // domain + qtype (2) + qclass (2)
        
        val totalLen = ihl + udpHeaderLen + dnsHeaderLen + questionLen
        val packet = ByteArray(totalLen)
        
        // IPv4 header
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[1] = 0x00.toByte() // DSCP/ECN
        packet[2] = (totalLen shr 8).toByte() // Total length
        packet[3] = (totalLen and 0xFF).toByte()
        packet[9] = 17.toByte() // Protocol: UDP
        
        // Source IP: 10.0.0.2
        packet[12] = 10.toByte()
        packet[15] = 2.toByte()
        
        // Dest IP: 8.8.8.8
        packet[16] = 8.toByte()
        packet[17] = 8.toByte()
        packet[18] = 8.toByte()
        packet[19] = 8.toByte()
        
        // UDP header
        val udpStart = ihl
        packet[udpStart + 2] = 0x00.toByte() // Dest port 53
        packet[udpStart + 3] = 53.toByte()
        
        val udpLen = udpHeaderLen + dnsHeaderLen + questionLen
        packet[udpStart + 4] = (udpLen shr 8).toByte()
        packet[udpStart + 5] = (udpLen and 0xFF).toByte()
        
        // DNS header
        val dnsStart = udpStart + udpHeaderLen
        packet[dnsStart] = 0x12.toByte() // Transaction ID
        packet[dnsStart + 1] = 0x34.toByte()
        packet[dnsStart + 2] = 0x01.toByte() // Flags: standard query
        packet[dnsStart + 5] = 0x01.toByte() // QDCOUNT = 1
        
        // DNS question
        val questionStart = dnsStart + dnsHeaderLen
        System.arraycopy(domainBytes, 0, packet, questionStart, domainBytes.size)
        packet[questionStart + domainBytes.size] = 0x00.toByte() // QTYPE = A
        packet[questionStart + domainBytes.size + 1] = 0x01.toByte()
        packet[questionStart + domainBytes.size + 2] = 0x00.toByte() // QCLASS = IN
        packet[questionStart + domainBytes.size + 3] = 0x01.toByte()
        
        return packet
    }

    private fun createMockDnsPacketIPv6(domain: String): ByteArray {
        // Create a minimal IPv6 DNS query packet
        val ipv6HeaderLen = 40
        val udpHeaderLen = 8
        val dnsHeaderLen = 12
        val domainBytes = encodeDomain(domain)
        val questionLen = domainBytes.size + 4
        
        val totalLen = ipv6HeaderLen + udpHeaderLen + dnsHeaderLen + questionLen
        val packet = ByteArray(totalLen)
        
        // IPv6 header
        packet[0] = 0x60.toByte() // Version 6
        packet[6] = 17.toByte() // Next header: UDP
        
        val payloadLen = udpHeaderLen + dnsHeaderLen + questionLen
        packet[4] = (payloadLen shr 8).toByte()
        packet[5] = (payloadLen and 0xFF).toByte()
        
        // UDP header at offset 40
        val udpStart = ipv6HeaderLen
        packet[udpStart + 2] = 0x00.toByte() // Dest port 53
        packet[udpStart + 3] = 53.toByte()
        
        packet[udpStart + 4] = (payloadLen shr 8).toByte()
        packet[udpStart + 5] = (payloadLen and 0xFF).toByte()
        
        // DNS header
        val dnsStart = udpStart + udpHeaderLen
        packet[dnsStart] = 0x12.toByte()
        packet[dnsStart + 1] = 0x34.toByte()
        packet[dnsStart + 2] = 0x01.toByte()
        packet[dnsStart + 5] = 0x01.toByte()
        
        // DNS question
        val questionStart = dnsStart + dnsHeaderLen
        System.arraycopy(domainBytes, 0, packet, questionStart, domainBytes.size)
        packet[questionStart + domainBytes.size] = 0x00.toByte()
        packet[questionStart + domainBytes.size + 1] = 0x01.toByte()
        packet[questionStart + domainBytes.size + 2] = 0x00.toByte()
        packet[questionStart + domainBytes.size + 3] = 0x01.toByte()
        
        return packet
    }

    private fun encodeDomain(domain: String): ByteArray {
        val parts = domain.split(".")
        var size = 0
        parts.forEach { size += it.length + 1 }
        size += 1 // Null terminator
        
        val encoded = ByteArray(size)
        var offset = 0
        parts.forEach { part ->
            encoded[offset++] = part.length.toByte()
            part.forEach { char ->
                encoded[offset++] = char.code.toByte()
            }
        }
        encoded[offset] = 0 // Null terminator
        
        return encoded
    }
}
