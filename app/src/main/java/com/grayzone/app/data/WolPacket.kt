package com.grayzone.app.data

/**
 * Pure Wake-on-LAN magic-packet builder. Separated from [WolSender] so MAC
 * parsing and packet layout can be unit-tested without a live DatagramSocket.
 */
object WolPacket {

    /** Parses `AA:BB:CC:DD:EE:FF` or `AA-BB-CC-DD-EE-FF` into 6 bytes, or null if invalid. */
    fun parseMac(macAddress: String): ByteArray? {
        return try {
            val macBytes = macAddress.replace("[:\\-]".toRegex(), "")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
            if (macBytes.size != 6) null else macBytes
        } catch (_: Exception) {
            null
        }
    }

    /** Builds the classic magic packet: 6×0xFF followed by the MAC repeated 16 times. */
    fun buildMagicPacket(macBytes: ByteArray): ByteArray {
        require(macBytes.size == 6) { "MAC must be 6 bytes" }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0..5) packet[i] = 0xFF.toByte()
        for (i in 0..15) {
            System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
        }
        return packet
    }
}
