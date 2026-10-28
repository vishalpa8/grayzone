package com.grayzone.app.service

import com.grayzone.app.service.UsageForegroundTracker.Companion.ACTIVITY_PAUSED
import com.grayzone.app.service.UsageForegroundTracker.Companion.ACTIVITY_RESUMED
import com.grayzone.app.service.UsageForegroundTracker.Companion.ACTIVITY_STOPPED
import com.grayzone.app.service.UsageForegroundTracker.Companion.DEVICE_STARTUP
import com.grayzone.app.service.UsageForegroundTracker.Companion.OTHER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageForegroundTrackerTest {

    // incallui, permission controller and the share sheet have no launcher entry.
    private val systemScreens = setOf("com.android.incallui", "com.google.android.permissioncontroller", "android")
    private val tracker = UsageForegroundTracker { it !in systemScreens }
    private var t = 1_000L

    private fun ev(type: Int, pkg: String, cls: String = "Main") = tracker.onEvent(type, pkg, cls, t++)

    private fun open(pkg: String, cls: String = "Main") = ev(ACTIVITY_RESUMED, pkg, cls)

    /** A full-screen switch: the old app pauses, the new one resumes, then the old one stops. */
    private fun switch(from: String, to: String) {
        ev(ACTIVITY_PAUSED, from)
        open(to)
        ev(ACTIVITY_STOPPED, from)
    }

    @Test
    fun `nothing known yet`() {
        assertNull(tracker.foreground())
        assertTrue(tracker.visiblePackages().isEmpty())
    }

    @Test
    fun `opening an app makes it the foreground`() {
        open("ig")
        assertEquals("ig", tracker.foreground())
        assertEquals(listOf("ig"), tracker.visiblePackages())
    }

    @Test
    fun `switching apps moves the foreground and drops the old app once stopped`() {
        open("ig")
        ev(ACTIVITY_PAUSED, "ig")
        open("yt")
        // Mid-animation the old app is still on screen.
        assertEquals("yt", tracker.foreground())
        assertEquals(listOf("yt", "ig"), tracker.visiblePackages())
        ev(ACTIVITY_STOPPED, "ig")
        assertEquals(listOf("yt"), tracker.visiblePackages())
    }

    @Test
    fun `going home is a switch to the launcher`() {
        open("ig")
        switch("ig", "launcher")
        assertEquals("launcher", tracker.foreground())
    }

    @Test
    fun `navigating inside an app keeps it in front`() {
        open("ig", "Feed")
        ev(ACTIVITY_PAUSED, "ig", "Feed")
        open("ig", "Story")
        ev(ACTIVITY_STOPPED, "ig", "Feed")
        assertEquals("ig", tracker.foreground())
        assertEquals(listOf("ig"), tracker.visiblePackages())
    }

    @Test
    fun `a call over the app is a switch away`() {
        open("ig")
        switch("ig", "com.android.incallui")
        assertEquals(OTHER, tracker.foreground())
        assertTrue(tracker.visiblePackages().isEmpty())
    }

    @Test
    fun `a translucent system dialog over the app pauses it but keeps it visible`() {
        open("ig")
        ev(ACTIVITY_PAUSED, "ig")
        open("com.google.android.permissioncontroller")
        assertEquals(OTHER, tracker.foreground())
        assertEquals(listOf("ig"), tracker.visiblePackages())
        // Dialog closed: back in the app.
        ev(ACTIVITY_PAUSED, "com.google.android.permissioncontroller")
        open("ig")
        ev(ACTIVITY_STOPPED, "com.google.android.permissioncontroller")
        assertEquals("ig", tracker.foreground())
    }

    @Test
    fun `split screen keeps both apps visible with the last touched in front`() {
        open("ig")
        open("yt") // multi-resume: both resumed
        assertEquals("yt", tracker.foreground())
        assertEquals(listOf("yt", "ig").toSet(), tracker.visiblePackages().toSet())
    }

    @Test
    fun `a system screen beside a resumed user app keeps the user in that app`() {
        open("ig")
        open("com.google.android.permissioncontroller")
        assertEquals("ig", tracker.foreground())
    }

    @Test
    fun `picture in picture stays visible while another app is in front`() {
        open("yt")
        ev(ACTIVITY_PAUSED, "yt") // entering PiP pauses but does not stop
        open("launcher")
        assertEquals("launcher", tracker.foreground())
        assertTrue("yt" in tracker.visiblePackages())
    }

    @Test
    fun `between a pause and the next resume the last app is still reported`() {
        open("ig")
        ev(ACTIVITY_PAUSED, "ig")
        assertEquals("ig", tracker.foreground())
    }

    @Test
    fun `screen lock stops the app`() {
        open("ig")
        ev(ACTIVITY_PAUSED, "ig")
        ev(ACTIVITY_STOPPED, "ig")
        assertNull(tracker.foreground())
        assertTrue(tracker.visiblePackages().isEmpty())
    }

    @Test
    fun `a reboot clears state`() {
        open("ig")
        ev(DEVICE_STARTUP, "android")
        assertNull(tracker.foreground())
    }

    @Test
    fun `events without a class name still pair up`() {
        tracker.onEvent(ACTIVITY_RESUMED, "ig", null, t++)
        tracker.onEvent(ACTIVITY_STOPPED, "ig", null, t++)
        assertNull(tracker.foreground())
    }

    @Test
    fun `lost stop events cannot grow state without bound`() {
        repeat(500) { i -> open("app$i") }
        assertEquals("app499", tracker.foreground())
        assertTrue(tracker.visiblePackages().size <= 64)
    }
}
