package com.grayzone.app.service

import com.grayzone.app.service.ForegroundResolver.AppWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundResolverTest {

    private val full = 1_000_000L
    private val minArea = full / 5

    private fun app(pkg: String, active: Boolean = false, area: Long = full) =
        AppWindow(pkg, area, active, isPip = false, isUserApp = true, isOwnOverlay = false)

    @Test
    fun `active user app is the foreground`() {
        val r = ForegroundResolver.resolve(listOf(app("ig", active = true)), minArea, null)
        assertEquals("ig", r.foreground)
        assertEquals(listOf("ig"), r.packages)
    }

    @Test
    fun `grayzone overlay over the app keeps the app in front`() {
        val overlay = AppWindow("com.grayzone.app", full, isActive = true, isPip = false, isUserApp = true, isOwnOverlay = true)
        val r = ForegroundResolver.resolve(listOf(overlay, app("ig")), minArea, "ig")
        assertEquals("ig", r.foreground)
        assertFalse("com.grayzone.app" in r.packages)
    }

    @Test
    fun `small floating windows are ignored`() {
        val sidebar = app("com.vivo.sidebar", active = false, area = minArea - 1)
        val r = ForegroundResolver.resolve(listOf(sidebar, app("ig")), minArea, "ig")
        assertEquals("ig", r.foreground)
        assertEquals(listOf("ig"), r.packages)
    }

    @Test
    fun `small focused pop-up window still counts as the app in use`() {
        val popup = app("ig", active = true, area = minArea / 3)
        val r = ForegroundResolver.resolve(listOf(popup, app("launcher")), minArea, "launcher")
        assertEquals("ig", r.foreground)
        assertTrue("ig" in r.packages)
    }

    @Test
    fun `small focused system panel is not a switch away`() {
        val panel = AppWindow("com.vivo.sidebar", minArea / 3, isActive = true, isPip = false, isUserApp = false, isOwnOverlay = false)
        val r = ForegroundResolver.resolve(listOf(panel, app("ig")), minArea, "ig")
        assertEquals("ig", r.foreground)
    }

    @Test
    fun `full screen non launchable window such as a call is a switch away`() {
        val call = AppWindow("com.android.incallui", full, isActive = true, isPip = false, isUserApp = false, isOwnOverlay = false)
        val r = ForegroundResolver.resolve(listOf(call, app("ig")), minArea, "ig")
        assertEquals(ForegroundResolver.OTHER, r.foreground)
    }

    @Test
    fun `inactive system panel does not change the foreground`() {
        val panel = AppWindow("com.vivo.systemuiplugin", full, isActive = false, isPip = false, isUserApp = false, isOwnOverlay = false)
        val r = ForegroundResolver.resolve(listOf(panel, app("ig")), minArea, "ig")
        assertEquals("ig", r.foreground)
    }

    @Test
    fun `picture in picture counts as on screen but never as foreground`() {
        val pip = AppWindow("yt", minArea / 4, isActive = false, isPip = true, isUserApp = true, isOwnOverlay = false)
        val r = ForegroundResolver.resolve(listOf(pip, app("launcher", active = true)), minArea, "yt")
        assertEquals("launcher", r.foreground)
        assertTrue("yt" in r.packages)
    }

    @Test
    fun `split screen lists both panes and follows the active one`() {
        val r = ForegroundResolver.resolve(listOf(app("a", area = full / 2), app("b", active = true, area = full / 2)), minArea, "a")
        assertEquals("b", r.foreground)
        assertEquals(setOf("a", "b"), r.packages.toSet())
    }

    @Test
    fun `without focus the previous app wins over a larger one`() {
        val r = ForegroundResolver.resolve(listOf(app("big"), app("prev", area = full / 2)), minArea, "prev")
        assertEquals("prev", r.foreground)
    }

    @Test
    fun `without focus or history the largest app wins`() {
        val r = ForegroundResolver.resolve(listOf(app("small", area = full / 2), app("big")), minArea, null)
        assertEquals("big", r.foreground)
    }

    @Test
    fun `empty window list has no foreground`() {
        val r = ForegroundResolver.resolve(emptyList(), minArea, "ig")
        assertEquals(null, r.foreground)
        assertTrue(r.packages.isEmpty())
    }
}
