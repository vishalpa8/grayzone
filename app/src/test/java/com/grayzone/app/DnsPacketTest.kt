package com.grayzone.app

import com.grayzone.app.service.vpn.DnsPacketHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {

    @Test
    fun `malformed packet returns null gracefully`() {
        val malformed = ByteArray(10) { 0xFF.toByte() }
        assertNull(DnsPacketHelper.getDomainName(malformed, malformed.size))
    }

    @Test
    fun `isDnsQuery rejects non-UDP and non-port-53 packets`() {
        val tcpPacket = ByteArray(60) { 0 }
        tcpPacket[0] = 0x45
        tcpPacket[9] = 6
        assertFalse(DnsPacketHelper.isDnsQuery(tcpPacket, tcpPacket.size))

        val udpPacket = ByteArray(60) { 0 }
        udpPacket[0] = 0x45
        udpPacket[9] = 17
        writeShort(udpPacket, 22, 80)
        assertFalse(DnsPacketHelper.isDnsQuery(udpPacket, udpPacket.size))
    }

    @Test
    fun `ipv4 query metadata uses DNS payload offset and question type`() {
        val packet = ipv4DnsQuery(id = 0x1234, qType = 28)

        assertTrue(DnsPacketHelper.isDnsQuery(packet, packet.size))
        assertEquals(28, DnsPacketHelper.getDnsPayloadOffset(packet, packet.size))
        assertEquals("example.com", DnsPacketHelper.getDomainName(packet, packet.size))

        val key = DnsPacketHelper.getQuestionKey(packet, packet.size, "example.com")
        assertNotNull(key)
        assertEquals("example.com", key!!.domain)
        assertEquals(28, key.qType)
        assertEquals(1, key.qClass)
    }

    @Test
    fun `nxdomain response swaps ipv4 addresses ports and preserves request id`() {
        val request = ipv4DnsQuery(id = 0xCAFE, qType = 1)
        val response = DnsPacketHelper.createNxDomainResponse(request, request.size)

        assertNotNull(response)
        response!!
        assertEquals(request.size, response.size)
        assertEquals(53, readShort(response, 20))
        assertEquals(40000, readShort(response, 22))
        assertEquals(192, u(response[12]))
        assertEquals(0, u(response[13]))
        assertEquals(2, u(response[14]))
        assertEquals(53, u(response[15]))
        assertEquals(10, u(response[16]))
        assertEquals(0, u(response[17]))
        assertEquals(0, u(response[18]))
        assertEquals(2, u(response[19]))
        assertEquals(0xCAFE, readShort(response, 28))
        assertEquals(0x8183, readShort(response, 30))
        assertEquals(0, readShort(response, 34))
        assertEquals(0, readShort(response, 26))
        assertTrue(readShort(response, 10) != 0)
    }

    @Test
    fun `wrapped ipv4 response uses current request id and updates lengths`() {
        val request = ipv4DnsQuery(id = 0x1111, qType = 1)
        val payload = dnsResponsePayload(id = 0x9999)
        val wrapped = DnsPacketHelper.wrapDnsResponse(request, payload, payload.size)

        assertNotNull(wrapped)
        wrapped!!
        assertEquals(20 + 8 + payload.size, wrapped.size)
        assertEquals(wrapped.size, readShort(wrapped, 2))
        assertEquals(8 + payload.size, readShort(wrapped, 24))
        assertEquals(53, readShort(wrapped, 20))
        assertEquals(40000, readShort(wrapped, 22))
        assertEquals(0x1111, readShort(wrapped, 28))
        assertEquals(0x8180, readShort(wrapped, 30))
        assertTrue(readShort(wrapped, 10) != 0)
    }

    @Test
    fun `ipv6 query and wrapped response use ipv6 DNS payload offset`() {
        val request = ipv6DnsQuery(id = 0x2222, qType = 28)
        val payload = dnsResponsePayload(id = 0xAAAA)
        val wrapped = DnsPacketHelper.wrapDnsResponse(request, payload, payload.size)

        assertTrue(DnsPacketHelper.isDnsQuery(request, request.size))
        assertEquals(48, DnsPacketHelper.getDnsPayloadOffset(request, request.size))
        assertEquals("example.com", DnsPacketHelper.getDomainName(request, request.size))
        assertNotNull(wrapped)
        wrapped!!
        assertEquals(48 + payload.size, wrapped.size)
        assertEquals(8 + payload.size, readShort(wrapped, 4))
        assertEquals(8 + payload.size, readShort(wrapped, 44))
        assertEquals(53, readShort(wrapped, 40))
        assertEquals(40000, readShort(wrapped, 42))
        assertEquals(0x2222, readShort(wrapped, 48))
        assertEquals(0x8180, readShort(wrapped, 50))
        assertTrue(readShort(wrapped, 46) != 0)
    }

    private fun ipv4DnsQuery(id: Int, qType: Int): ByteArray {
        val dns = dnsQueryPayload(id, qType)
        val packet = ByteArray(20 + 8 + dns.size)
        packet[0] = 0x45
        writeShort(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 17
        packet[12] = 10
        packet[15] = 2
        packet[16] = 192.toByte()
        packet[18] = 2
        packet[19] = 53
        writeShort(packet, 20, 40000)
        writeShort(packet, 22, 53)
        writeShort(packet, 24, 8 + dns.size)
        dns.copyInto(packet, 28)
        return packet
    }

    private fun ipv6DnsQuery(id: Int, qType: Int): ByteArray {
        val dns = dnsQueryPayload(id, qType)
        val packet = ByteArray(40 + 8 + dns.size)
        packet[0] = 0x60
        writeShort(packet, 4, 8 + dns.size)
        packet[6] = 17
        packet[7] = 64
        packet[23] = 1
        packet[39] = 2
        writeShort(packet, 40, 40000)
        writeShort(packet, 42, 53)
        writeShort(packet, 44, 8 + dns.size)
        dns.copyInto(packet, 48)
        return packet
    }

    private fun dnsQueryPayload(id: Int, qType: Int): ByteArray {
        val qname = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        val payload = ByteArray(12 + qname.size + 4)
        writeShort(payload, 0, id)
        writeShort(payload, 2, 0x0100)
        writeShort(payload, 4, 1)
        qname.copyInto(payload, 12)
        val qTail = 12 + qname.size
        writeShort(payload, qTail, qType)
        writeShort(payload, qTail + 2, 1)
        return payload
    }

    private fun dnsResponsePayload(id: Int): ByteArray {
        val query = dnsQueryPayload(id, 1)
        val response = query.copyOf(query.size + 16)
        writeShort(response, 2, 0x8180)
        writeShort(response, 6, 1)
        var offset = query.size
        response[offset++] = 0xC0.toByte()
        response[offset++] = 0x0C
        writeShort(response, offset, 1); offset += 2
        writeShort(response, offset, 1); offset += 2
        response[offset + 3] = 60; offset += 4
        writeShort(response, offset, 4); offset += 2
        response[offset++] = 93.toByte()
        response[offset++] = 184.toByte()
        response[offset++] = 216.toByte()
        response[offset] = 34
        return response
    }

    private fun writeShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun readShort(packet: ByteArray, offset: Int): Int =
        (u(packet[offset]) shl 8) or u(packet[offset + 1])

    private fun u(value: Byte): Int = value.toInt() and 0xFF
}