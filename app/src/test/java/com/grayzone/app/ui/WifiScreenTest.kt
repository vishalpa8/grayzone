package com.grayzone.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiScreenTest {

    @Test
    fun parseArpTable_extractsLiveHosts() {
        val arpOutput = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.1      0x1         0x2         00:11:22:33:44:55     *        wlan0
            192.168.1.102    0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0
            0.0.0.0          0x0         0x0         00:00:00:00:00:00     *        dummy0
        """.trimIndent()

        assertEquals(
            listOf("192.168.1.1", "192.168.1.102"),
            parseArpTable(arpOutput)
        )
    }
}
