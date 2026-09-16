package com.grayzone.app.service.vpn

import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

object DnsPacketHelper {

    data class DnsQuestionKey(
        val domain: String,
        val qType: Int,
        val qClass: Int
    )

    // Error counters for monitoring DNS packet manipulation failures
    private val domainParseErrors = AtomicInteger(0)
    private val sinkholeCreationErrors = AtomicInteger(0)
    private val nxdomainCreationErrors = AtomicInteger(0)
    private val responseWrapErrors = AtomicInteger(0)
    
    // IPv6 usage statistics
    private val ipv4PacketsProcessed = AtomicInteger(0)
    private val ipv6PacketsProcessed = AtomicInteger(0)

    /**
     * Get error statistics for debugging.
     * Returns map of error type to count.
     */
    fun getErrorStats(): Map<String, Int> = mapOf(
        "domain_parse_errors" to domainParseErrors.get(),
        "sinkhole_creation_errors" to sinkholeCreationErrors.get(),
        "nxdomain_creation_errors" to nxdomainCreationErrors.get(),
        "response_wrap_errors" to responseWrapErrors.get(),
        "ipv4_packets_processed" to ipv4PacketsProcessed.get(),
        "ipv6_packets_processed" to ipv6PacketsProcessed.get()
    )

    fun isDnsQuery(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false
        val offset = getDnsPayloadOffset(packet, length) ?: return false
        val udpOffset = offset - 8
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        return dstPort == 53
    }

    fun getDnsPayloadOffset(packet: ByteArray, length: Int): Int? {
        if (length < 28) return null

        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> {
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < 20 || length < ihl + 8) null else ihl + 8
            }
            6 -> {
                if (length < 48) return null
                var offset = 40
                var nextHeader = packet[6].toInt() and 0xFF
                
                while (offset < length && nextHeader != 17 && nextHeader != 59) {
                    if (nextHeader == 0 || nextHeader == 43 || nextHeader == 60 || nextHeader == 44 || nextHeader == 51) {
                        if (offset + 2 > length) return null
                        val extLen = packet[offset + 1].toInt() and 0xFF
                        val headerLen = if (nextHeader == 44) 8 else (extLen + 1) * 8
                        nextHeader = packet[offset].toInt() and 0xFF
                        offset += headerLen
                    } else {
                        return null
                    }
                }
                
                if (nextHeader == 17) {
                    if (length < offset + 8) return null
                    return offset + 8
                }
                return null
            }
            else -> null
        }
    }

    fun getDnsPayloadLength(packet: ByteArray, length: Int): Int? {
        val dnsOffset = getDnsPayloadOffset(packet, length) ?: return null
        val dnsLength = length - dnsOffset
        return if (dnsLength > 0) dnsLength else null
    }

    fun getQuestionKey(packet: ByteArray, length: Int, normalizedDomain: String): DnsQuestionKey? {
        return try {
            val dnsOffset = getDnsPayloadOffset(packet, length) ?: return null
            if (length < dnsOffset + 12) return null

            var offset = dnsOffset + 12
            while (offset < length) {
                val labelLength = packet[offset].toInt() and 0xFF
                offset++
                if (labelLength == 0) break
                if ((labelLength and 0xC0) == 0xC0) return null
                if (labelLength > 63 || offset + labelLength > length) return null
                offset += labelLength
            }

            if (offset + 4 > length) return null
            val qType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val qClass = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
            DnsQuestionKey(normalizedDomain, qType, qClass)
        } catch (e: Exception) {
            domainParseErrors.incrementAndGet()
            null
        }
    }

    fun getDomainName(packet: ByteArray, length: Int): String? {
        if (length < 28) return null
        try {
            val version = (packet[0].toInt() and 0xF0) shr 4
            // Reuse the shared offset logic so IPv6 extension headers are handled
            // consistently, instead of assuming a fixed 48-byte DNS offset.
            val dnsOffset = getDnsPayloadOffset(packet, length) ?: return null

            // Diagnostic counters (surfaced via getErrorStats()).
            when (version) {
                4 -> ipv4PacketsProcessed.incrementAndGet()
                6 -> ipv6PacketsProcessed.incrementAndGet()
            }

            if (length < dnsOffset + 12) return null
            val qdCount = ((packet[dnsOffset + 4].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 5].toInt() and 0xFF)
            if (qdCount == 0) return null

            var offset = dnsOffset + 12
            val sb = java.lang.StringBuilder(64)

            while (offset < length) {
                val len = packet[offset].toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) break
                if (len > 63) return null
                offset++
                if (offset + len > length) return null
                val label = String(packet, offset, len, StandardCharsets.US_ASCII)
                if (sb.isNotEmpty()) sb.append(".")
                sb.append(label)
                offset += len
            }
            return sb.toString()
        } catch (e: Exception) {
            domainParseErrors.incrementAndGet()
            GrayzoneLogger.w(
                LogComponent.DNS,
                "Failed to parse domain name from DNS packet: ${e.message}",
                e
            )
            return null
        }
    }



    fun createNxDomainResponse(requestPacket: ByteArray, requestLength: Int): ByteArray? {
        try {
            val version = (requestPacket[0].toInt() and 0xF0) shr 4
            if (version == 6) return createNxDomainResponseIPv6(requestPacket, requestLength)

            val ihl = (requestPacket[0].toInt() and 0x0F) * 4
            val dnsOffset = ihl + 8

            val response = ByteArray(requestLength)
            System.arraycopy(requestPacket, 0, response, 0, requestLength)

            // Swap IP addresses
            for (i in 0..3) {
                val tmp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = tmp
            }

            // Swap UDP ports
            val sp1 = response[ihl]
            val sp2 = response[ihl + 1]
            response[ihl] = response[ihl + 2]
            response[ihl + 1] = response[ihl + 3]
            response[ihl + 2] = sp1
            response[ihl + 3] = sp2

            // UDP checksum optional in IPv4
            response[ihl + 6] = 0
            response[ihl + 7] = 0

            // Modify DNS Flags to Standard Response with NXDOMAIN (RCODE = 3)
            // 0x8183 -> QR=1, Opcode=0, AA=0, TC=0, RD=1, RA=1, Z=0, RCODE=3
            response[dnsOffset + 2] = 0x81.toByte()
            response[dnsOffset + 3] = 0x83.toByte()

            // Answer RRs = 0
            response[dnsOffset + 6] = 0
            response[dnsOffset + 7] = 0

            computeIpChecksum(response, ihl)

            return response
        } catch (e: Exception) {
            nxdomainCreationErrors.incrementAndGet()
            GrayzoneLogger.e(
                LogComponent.DNS,
                "Failed to create NXDOMAIN response (IPv4): ${e.message}",
                e
            )
            return null
        }
    }

    private fun computeIpChecksum(packet: ByteArray, ihl: Int) {
        packet[10] = 0
        packet[11] = 0
        var sum = 0L
        for (i in 0 until ihl step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toInt()
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }

    fun wrapDnsResponse(requestPacket: ByteArray, dnsPayload: ByteArray, dnsLength: Int): ByteArray? {
        try {
            val version = (requestPacket[0].toInt() and 0xF0) shr 4
            if (version == 6) return wrapDnsResponseIPv6(requestPacket, dnsPayload, dnsLength)

            val ihl = (requestPacket[0].toInt() and 0x0F) * 4
            val newTotalLength = ihl + 8 + dnsLength
            val response = ByteArray(newTotalLength)

            // Copy IP header
            System.arraycopy(requestPacket, 0, response, 0, ihl)
            
            // Set new total length in IP header
            response[2] = (newTotalLength shr 8).toByte()
            response[3] = (newTotalLength and 0xFF).toByte()

            // Swap IP addresses
            for (i in 0..3) {
                val tmp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = tmp
            }

            // Copy UDP header
            System.arraycopy(requestPacket, ihl, response, ihl, 8)
            
            // Set new UDP length
            val udpLength = 8 + dnsLength
            response[ihl + 4] = (udpLength shr 8).toByte()
            response[ihl + 5] = (udpLength and 0xFF).toByte()

            // Swap UDP ports
            val sp1 = response[ihl]
            val sp2 = response[ihl + 1]
            response[ihl] = response[ihl + 2]
            response[ihl + 1] = response[ihl + 3]
            response[ihl + 2] = sp1
            response[ihl + 3] = sp2

            // UDP checksum optional in IPv4
            response[ihl + 6] = 0
            response[ihl + 7] = 0

            // Copy DNS payload and preserve the transaction ID expected by this request.
            System.arraycopy(dnsPayload, 0, response, ihl + 8, dnsLength)
            val requestDnsOffset = getDnsPayloadOffset(requestPacket, requestPacket.size) ?: return null
            response[ihl + 8] = requestPacket[requestDnsOffset]
            response[ihl + 9] = requestPacket[requestDnsOffset + 1]

            computeIpChecksum(response, ihl)
            return response
        } catch (e: Exception) {
            responseWrapErrors.incrementAndGet()
            GrayzoneLogger.e(
                LogComponent.DNS,
                "Failed to wrap DNS response (IPv4): ${e.message}",
                e
            )
            return null
        }
    }

    private fun createNxDomainResponseIPv6(requestPacket: ByteArray, requestLength: Int): ByteArray? {
        try {
            val dnsOffset = getDnsPayloadOffset(requestPacket, requestLength) ?: return null
            val udpOffset = dnsOffset - 8
            val response = ByteArray(requestLength)
            System.arraycopy(requestPacket, 0, response, 0, requestLength)

            for (i in 0..15) {
                val tmp = response[8 + i]
                response[8 + i] = response[24 + i]
                response[24 + i] = tmp
            }

            val sp1 = response[udpOffset]
            val sp2 = response[udpOffset + 1]
            response[udpOffset] = response[udpOffset + 2]
            response[udpOffset + 1] = response[udpOffset + 3]
            response[udpOffset + 2] = sp1
            response[udpOffset + 3] = sp2

            response[udpOffset + 6] = 0
            response[udpOffset + 7] = 0

            response[dnsOffset + 2] = 0x81.toByte()
            response[dnsOffset + 3] = 0x83.toByte()
            response[dnsOffset + 6] = 0
            response[dnsOffset + 7] = 0

            computeUdpChecksumIPv6(response, udpOffset)
            return response
        } catch (e: Exception) {
            nxdomainCreationErrors.incrementAndGet()
            GrayzoneLogger.e(
                LogComponent.DNS,
                "Failed to create NXDOMAIN response (IPv6): ${e.message}",
                e
            )
            return null
        }
    }

    private fun wrapDnsResponseIPv6(requestPacket: ByteArray, dnsPayload: ByteArray, dnsLength: Int): ByteArray? {
        try {
            val requestDnsOffset = getDnsPayloadOffset(requestPacket, requestPacket.size) ?: return null
            val requestUdpOffset = requestDnsOffset - 8
            
            val newTotalLength = 40 + 8 + dnsLength
            val response = ByteArray(newTotalLength)

            System.arraycopy(requestPacket, 0, response, 0, 40)
            
            // We strip extension headers, so set Next Header to UDP (17)
            response[6] = 17
            
            val payloadLength = 8 + dnsLength
            response[4] = (payloadLength shr 8).toByte()
            response[5] = (payloadLength and 0xFF).toByte()

            for (i in 0..15) {
                val tmp = response[8 + i]
                response[8 + i] = response[24 + i]
                response[24 + i] = tmp
            }

            System.arraycopy(requestPacket, requestUdpOffset, response, 40, 8)
            
            response[44] = (payloadLength shr 8).toByte()
            response[45] = (payloadLength and 0xFF).toByte()

            val sp1 = response[40]
            val sp2 = response[41]
            response[40] = response[42]
            response[41] = response[43]
            response[42] = sp1
            response[43] = sp2

            response[46] = 0
            response[47] = 0

            System.arraycopy(dnsPayload, 0, response, 48, dnsLength)
            
            response[48] = requestPacket[requestDnsOffset]
            response[49] = requestPacket[requestDnsOffset + 1]

            computeUdpChecksumIPv6(response, 40)
            return response
        } catch (e: Exception) {
            responseWrapErrors.incrementAndGet()
            GrayzoneLogger.e(
                LogComponent.DNS,
                "Failed to wrap DNS response (IPv6): ${e.message}",
                e
            )
            return null
        }
    }

    private fun computeUdpChecksumIPv6(packet: ByteArray, udpOffset: Int) {
        val payloadLength = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
        
        var sum = 0L
        for (i in 8 until 40 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += payloadLength
        sum += 17

        for (i in udpOffset until udpOffset + payloadLength step 2) {
            if (i == udpOffset + payloadLength - 1) {
                sum += (packet[i].toInt() and 0xFF) shl 8
            } else {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        sum = sum.inv() and 0xFFFF
        if (sum == 0L) sum = 0xFFFF
        
        packet[udpOffset + 6] = (sum shr 8).toByte()
        packet[udpOffset + 7] = (sum and 0xFF).toByte()
    }
}
