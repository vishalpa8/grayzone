package com.grayzone.app.service.vpn

import java.nio.charset.StandardCharsets

object DnsPacketHelper {

    fun isDnsQuery(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false

        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version == 4) {
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) return false

            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (length < ihl + 8) return false

            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            return dstPort == 53
        } else if (version == 6) {
            if (length < 48) return false
            val nextHeader = packet[6].toInt() and 0xFF
            if (nextHeader != 17) return false

            val dstPort = ((packet[42].toInt() and 0xFF) shl 8) or (packet[43].toInt() and 0xFF)
            return dstPort == 53
        }
        return false
    }

    fun getDomainName(packet: ByteArray, length: Int): String? {
        try {
            val version = (packet[0].toInt() and 0xF0) shr 4
            val dnsOffset: Int
            if (version == 4) {
                val ihl = (packet[0].toInt() and 0x0F) * 4
                dnsOffset = ihl + 8
            } else if (version == 6) {
                dnsOffset = 48
            } else {
                return null
            }

            val qdCount = ((packet[dnsOffset + 4].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 5].toInt() and 0xFF)
            if (qdCount == 0) return null

            var offset = dnsOffset + 12
            val sb = java.lang.StringBuilder()

            while (offset < length) {
                val len = packet[offset].toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) break
                offset++
                if (offset + len > length) return null
                val label = String(packet, offset, len, StandardCharsets.US_ASCII)
                if (sb.isNotEmpty()) sb.append(".")
                sb.append(label)
                offset += len
            }
            return sb.toString()
        } catch (e: Exception) {
            return null
        }
    }

    fun createSinkholeResponse(requestPacket: ByteArray, requestLength: Int): ByteArray? {
        try {
            val version = (requestPacket[0].toInt() and 0xF0) shr 4
            if (version == 6) return createSinkholeResponseIPv6(requestPacket, requestLength)

            val ihl = (requestPacket[0].toInt() and 0x0F) * 4
            val dnsOffset = ihl + 8

            val newTotalLength = requestLength + 16
            val response = ByteArray(newTotalLength)
            System.arraycopy(requestPacket, 0, response, 0, requestLength)

            // Update IP Total Length
            response[2] = (newTotalLength shr 8).toByte()
            response[3] = (newTotalLength and 0xFF).toByte()

            // Swap IP addresses
            for (i in 0..3) {
                val tmp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = tmp
            }

            // Update UDP Length
            val newUdpLength = requestLength - ihl + 16
            response[ihl + 4] = (newUdpLength shr 8).toByte()
            response[ihl + 5] = (newUdpLength and 0xFF).toByte()

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

            // Modify DNS Flags to Standard Response (No error)
            response[dnsOffset + 2] = 0x81.toByte()
            response[dnsOffset + 3] = 0x80.toByte()

            // Answer RRs = 1
            response[dnsOffset + 6] = 0
            response[dnsOffset + 7] = 1

            // Append Answer Record (A record pointing to 0.0.0.0)
            var offset = requestLength
            // Name pointer to offset 12 in DNS header
            response[offset++] = 0xC0.toByte()
            response[offset++] = 0x0C.toByte()
            // Type A (1)
            response[offset++] = 0
            response[offset++] = 1
            // Class IN (1)
            response[offset++] = 0
            response[offset++] = 1
            // TTL 60
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 60
            // Data length 4
            response[offset++] = 0
            response[offset++] = 4
            // IP 0.0.0.0
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 0

            computeIpChecksum(response, ihl)

            return response
        } catch (e: Exception) {
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

    fun wrapDnsResponse(requestPacket: ByteArray, requestLength: Int, dnsPayload: ByteArray, dnsLength: Int): ByteArray? {
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

            // Copy DNS payload
            System.arraycopy(dnsPayload, 0, response, ihl + 8, dnsLength)

            computeIpChecksum(response, ihl)
            return response
        } catch (e: Exception) {
            return null
        }
    }

    private fun createSinkholeResponseIPv6(requestPacket: ByteArray, requestLength: Int): ByteArray? {
        try {
            val dnsOffset = 48
            val newTotalLength = requestLength + 16
            val response = ByteArray(newTotalLength)
            System.arraycopy(requestPacket, 0, response, 0, requestLength)

            val payloadLength = newTotalLength - 40
            response[4] = (payloadLength shr 8).toByte()
            response[5] = (payloadLength and 0xFF).toByte()

            for (i in 0..15) {
                val tmp = response[8 + i]
                response[8 + i] = response[24 + i]
                response[24 + i] = tmp
            }

            val newUdpLength = payloadLength
            response[44] = (newUdpLength shr 8).toByte()
            response[45] = (newUdpLength and 0xFF).toByte()

            val sp1 = response[40]
            val sp2 = response[41]
            response[40] = response[42]
            response[41] = response[43]
            response[42] = sp1
            response[43] = sp2

            response[46] = 0
            response[47] = 0

            response[dnsOffset + 2] = 0x81.toByte()
            response[dnsOffset + 3] = 0x80.toByte()
            response[dnsOffset + 6] = 0
            response[dnsOffset + 7] = 1

            var offset = requestLength
            response[offset++] = 0xC0.toByte()
            response[offset++] = 0x0C.toByte()
            response[offset++] = 0
            response[offset++] = 1
            response[offset++] = 0
            response[offset++] = 1
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 60
            response[offset++] = 0
            response[offset++] = 4
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 0

            computeUdpChecksumIPv6(response)
            return response
        } catch (e: Exception) {
            return null
        }
    }

    private fun createNxDomainResponseIPv6(requestPacket: ByteArray, requestLength: Int): ByteArray? {
        try {
            val dnsOffset = 48
            val response = ByteArray(requestLength)
            System.arraycopy(requestPacket, 0, response, 0, requestLength)

            for (i in 0..15) {
                val tmp = response[8 + i]
                response[8 + i] = response[24 + i]
                response[24 + i] = tmp
            }

            val sp1 = response[40]
            val sp2 = response[41]
            response[40] = response[42]
            response[41] = response[43]
            response[42] = sp1
            response[43] = sp2

            response[46] = 0
            response[47] = 0

            response[dnsOffset + 2] = 0x81.toByte()
            response[dnsOffset + 3] = 0x83.toByte()
            response[dnsOffset + 6] = 0
            response[dnsOffset + 7] = 0

            computeUdpChecksumIPv6(response)
            return response
        } catch (e: Exception) {
            return null
        }
    }

    private fun wrapDnsResponseIPv6(requestPacket: ByteArray, dnsPayload: ByteArray, dnsLength: Int): ByteArray? {
        try {
            val newTotalLength = 48 + dnsLength
            val response = ByteArray(newTotalLength)

            System.arraycopy(requestPacket, 0, response, 0, 40)
            
            val payloadLength = 8 + dnsLength
            response[4] = (payloadLength shr 8).toByte()
            response[5] = (payloadLength and 0xFF).toByte()

            for (i in 0..15) {
                val tmp = response[8 + i]
                response[8 + i] = response[24 + i]
                response[24 + i] = tmp
            }

            System.arraycopy(requestPacket, 40, response, 40, 8)
            
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

            computeUdpChecksumIPv6(response)
            return response
        } catch (e: Exception) {
            return null
        }
    }

    private fun computeUdpChecksumIPv6(packet: ByteArray) {
        val payloadLength = ((packet[44].toInt() and 0xFF) shl 8) or (packet[45].toInt() and 0xFF)
        
        var sum = 0L
        for (i in 8 until 40 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += payloadLength
        sum += 17

        for (i in 40 until 40 + payloadLength step 2) {
            if (i == 40 + payloadLength - 1) {
                sum += (packet[i].toInt() and 0xFF) shl 8
            } else {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toInt()
        val finalChecksum = if (checksum == 0) 0xFFFF else checksum
        packet[46] = (finalChecksum shr 8).toByte()
        packet[47] = (finalChecksum and 0xFF).toByte()
    }
}
