package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiHelpersTest {

    @Test
    fun `intToIp decodes little-endian wifi ints`() {
        // 192.168.1.10 → bytes 10,1,168,192 packed LE
        val packed = (10) or (1 shl 8) or (168 shl 16) or (192 shl 24)
        assertEquals("10.1.168.192", intToIp(packed))
        assertEquals("0.0.0.0", intToIp(0))
    }

    @Test
    fun `formatBytes picks the right unit`() {
        assertEquals("500 B", formatBytes(500))
        assertTrue(formatBytes(1_500).endsWith("KB"))
        assertTrue(formatBytes(2_500_000).endsWith("MB"))
        assertTrue(formatBytes(3_500_000_000L).endsWith("GB"))
    }

    @Test
    fun `signal bars band and channel from WifiNetworkInfo`() {
        assertEquals(4, WifiNetworkInfo(rssi = -40, isConnected = true).signalBars)
        assertEquals(2, WifiNetworkInfo(rssi = -70, isConnected = true).signalBars)
        assertEquals(0, WifiNetworkInfo(rssi = -90, isConnected = true).signalBars)

        assertEquals("—", WifiNetworkInfo(frequency = 5200, isConnected = false).band)
        assertEquals("2.4 GHz", WifiNetworkInfo(frequency = 2412, isConnected = true).band)
        assertEquals("5 GHz", WifiNetworkInfo(frequency = 5200, isConnected = true).band)
        assertEquals("6 GHz", WifiNetworkInfo(frequency = 6200, isConnected = true).band)

        assertEquals(1, WifiNetworkInfo(frequency = 2412).channel)
        assertEquals(36, WifiNetworkInfo(frequency = 5180).channel)
    }

    @Test
    fun `infer display name prefers hostname then gateway heuristics`() {
        assertEquals("Router / Gateway", inferDeviceDisplayName("192.168.1.1", "x", true))
        assertEquals("pixel-6", inferDeviceDisplayName("192.168.1.20", "pixel-6.local", false))
        assertEquals("Router / Gateway", inferDeviceDisplayName("192.168.1.1", "", false))
        assertEquals("Device 192.168.1.44", inferDeviceDisplayName("192.168.1.44", "", false))
    }

    @Test
    fun `infer device type from hostname keywords`() {
        assertEquals(DeviceType.ROUTER, inferDeviceType("1.1.1.1", "x", true))
        assertEquals(DeviceType.PHONE, inferDeviceType("1.1.1.2", "android-phone", false))
        assertEquals(DeviceType.LAPTOP, inferDeviceType("1.1.1.2", "thinkpad-x1", false))
        assertEquals(DeviceType.TABLET, inferDeviceType("1.1.1.2", "ipad-pro", false))
        assertEquals(DeviceType.TV, inferDeviceType("1.1.1.2", "livingroom-roku", false))
        assertEquals(DeviceType.SMART_HOME, inferDeviceType("1.1.1.2", "nest-hub", false))
        assertEquals(DeviceType.UNKNOWN, inferDeviceType("1.1.1.2", "mystery", false))
    }

    @Test
    fun `parseArpTable skips incomplete and zero mac rows`() {
        val arp = """
            IP address       HW type     Flags       HW address            Mask     Device
            10.0.0.1         0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0
            10.0.0.2         0x1         0x0         00:00:00:00:00:00     *        wlan0
            garbage line
            not-an-ip        0x1         0x2         11:22:33:44:55:66     *        wlan0
        """.trimIndent()
        assertEquals(listOf("10.0.0.1"), parseArpTable(arp))
    }
}
