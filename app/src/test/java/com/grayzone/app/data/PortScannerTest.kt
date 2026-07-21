package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortScannerTest {

    @Test
    fun `well known ports map to expected service names`() {
        assertEquals("SSH", PortScanner.serviceNameFor(22))
        assertEquals("HTTP", PortScanner.serviceNameFor(80))
        assertEquals("HTTPS", PortScanner.serviceNameFor(443))
        assertEquals("RDP", PortScanner.serviceNameFor(3389))
        assertEquals("Plex", PortScanner.serviceNameFor(32400))
    }

    @Test
    fun `unknown ports fall back to Unknown`() {
        assertEquals("Unknown", PortScanner.serviceNameFor(12345))
        assertEquals("Unknown", PortScanner.serviceNameFor(0))
    }

    @Test
    fun `common port list is non-empty and includes https`() {
        val ports = PortScanner.commonPortList()
        assertTrue(ports.isNotEmpty())
        assertTrue(ports.contains(443))
        assertTrue(ports.contains(80))
    }
}
