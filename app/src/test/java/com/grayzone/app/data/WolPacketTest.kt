package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WolPacketTest {

    @Test
    fun `parses colon and dash mac formats`() {
        val a = WolPacket.parseMac("AA:BB:CC:DD:EE:FF")
        val b = WolPacket.parseMac("aa-bb-cc-dd-ee-ff")
        assertNotNull(a)
        assertNotNull(b)
        assertTrue(a!!.contentEquals(b!!))
        assertEquals(6, a.size)
        assertEquals(0xAA.toByte(), a[0])
        assertEquals(0xFF.toByte(), a[5])
    }

    @Test
    fun `rejects invalid mac strings`() {
        assertNull(WolPacket.parseMac(""))
        assertNull(WolPacket.parseMac("AA:BB:CC"))
        assertNull(WolPacket.parseMac("GG:HH:II:JJ:KK:LL"))
        assertNull(WolPacket.parseMac("AA:BB:CC:DD:EE:FF:11"))
    }

    @Test
    fun `magic packet is 102 bytes with sync stream and 16 mac repeats`() {
        val mac = WolPacket.parseMac("01:23:45:67:89:AB")!!
        val packet = WolPacket.buildMagicPacket(mac)
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        for (i in 0 until 16) {
            val offset = 6 + i * 6
            assertTrue(packet.copyOfRange(offset, offset + 6).contentEquals(mac))
        }
    }
}
