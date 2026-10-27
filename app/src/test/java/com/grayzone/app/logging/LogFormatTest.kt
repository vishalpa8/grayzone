package com.grayzone.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {

    @Test
    fun `line contains level process thread component and message`() {
        val line = LogFormat.line(0L, 'I', "main", "worker-1", "SESSION", "Session started")
        assertTrue(line.contains(" I/main [worker-1] [SESSION] Session started"))
    }

    @Test
    fun `throwable stack trace follows the line`() {
        val line = LogFormat.line(0L, 'E', "a11y", "main", "VPN", "boom", IllegalStateException("bad"))
        assertTrue(line.contains("[VPN] boom\njava.lang.IllegalStateException: bad"))
        assertTrue(line.contains("\tat "))
    }

    @Test
    fun `third party package names are masked`() {
        assertEquals("Session started for <app> (10 min)", LogFormat.redact("Session started for com.instagram.android (10 min)"))
    }

    @Test
    fun `two part package names are masked`() {
        assertEquals("lock for <app> and <app>", LogFormat.redact("lock for com.whatsapp and in.startv"))
    }

    @Test
    fun `exception messages are masked but frames are kept`() {
        val line = LogFormat.line(0L, 'E', "main", "t", "OVERLAY", "fail", RuntimeException("Unable to start com.whatsapp"))
        assertFalse(line.contains("com.whatsapp"))
        assertTrue(line.contains("RuntimeException: Unable to start <app>"))
        assertTrue(line.contains("at com.grayzone.app.logging.LogFormatTest"))
    }

    @Test
    fun `commonly limited apps from android packages are masked`() {
        assertEquals("<app> and <app> and <app>", LogFormat.redact("com.android.chrome and com.android.vending and kik.android"))
    }

    @Test
    fun `library exception class names stay readable in caused by lines`() {
        val cause = IllegalArgumentException("bad json from com.whatsapp")
        val line = LogFormat.line(0L, 'E', "main", "t", "PREFS", "parse", RuntimeException("wrap", cause))
        assertTrue(line.contains("Caused by: java.lang.IllegalArgumentException: bad json from <app>"))
    }

    @Test
    fun `continuation lines of multi line messages are fully masked`() {
        val e = RuntimeException("first line\ncom.whatsapp.Main: crashed in com.whatsapp")
        val line = LogFormat.line(0L, 'E', "main", "t", "UI", "fail", e)
        assertFalse(line.contains("com.whatsapp"))
        assertTrue(line.contains("java.lang.RuntimeException: first line"))
    }

    @Test
    fun `grayzone and system names stay readable`() {
        val msg = "from com.grayzone.app to com.android.launcher3 via android.intent.action.MAIN"
        assertEquals(msg, LogFormat.redact(msg))
    }

    @Test
    fun `short host names, code names and numbers are untouched`() {
        val msg = "socket.close failed for example.com in 1.5 s, version 1.0.0, VpnService.prepare"
        assertEquals(msg, LogFormat.redact(msg))
    }

    @Test
    fun `long host names are masked`() {
        assertFalse(LogFormat.redact("blocked ads.tracker.example.net").contains("tracker"))
    }
}
