package com.grayzone.app

import com.grayzone.app.SetupRequirement.USAGE_ACCESS
import com.grayzone.app.SetupRequirement.BATTERY
import com.grayzone.app.SetupRequirement.NOTIFICATIONS
import com.grayzone.app.SetupRequirement.OVERLAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionWalkTest {

    private val all = listOf(NOTIFICATIONS, USAGE_ACCESS, OVERLAY, BATTERY)

    @Test
    fun `nothing missing starts no walk`() {
        assertNull(PermissionWalk.start(emptyList()))
    }

    @Test
    fun `steps follow setup order regardless of input order`() {
        val walk = PermissionWalk.start(listOf(BATTERY, OVERLAY, NOTIFICATIONS, USAGE_ACCESS))!!
        assertEquals(all, walk.steps)
        assertEquals(NOTIFICATIONS, walk.current(all))
    }

    @Test
    fun `declining the first moves to the second instead of ending`() {
        val walk = PermissionWalk.start(all)!!.decline(NOTIFICATIONS)
        assertEquals(USAGE_ACCESS, walk.current(all))
        assertFalse(walk.isFinished(all))
    }

    @Test
    fun `declining every step ends the walk without success`() {
        var walk = PermissionWalk.start(all)!!
        all.forEach { walk = walk.decline(it) }
        assertTrue(walk.isFinished(all))
        assertFalse(walk.succeeded(all))
    }

    @Test
    fun `granting each step in turn ends the walk with success`() {
        val walk = PermissionWalk.start(all)!!
        val missing = all.toMutableList()
        for (expected in all) {
            assertEquals(expected, walk.current(missing))
            missing.remove(expected)
        }
        assertTrue(walk.isFinished(missing))
        assertTrue(walk.succeeded(missing))
    }

    @Test
    fun `coming back from settings without granting keeps the same popup`() {
        val walk = PermissionWalk.start(all)!!
        val missing = all.filter { it != NOTIFICATIONS }
        assertEquals(USAGE_ACCESS, walk.current(missing))
        // Returned from Usage access settings, still off:
        assertEquals(USAGE_ACCESS, walk.current(missing))
    }

    @Test
    fun `a step granted elsewhere mid-walk is skipped`() {
        val walk = PermissionWalk.start(all)!!
        // Overlay was granted while the user was on the notifications popup.
        assertEquals(BATTERY, walk.current(listOf(BATTERY)))
    }

    @Test
    fun `decline then allow the rest still fails because one permission is off`() {
        val walk = PermissionWalk.start(all)!!.decline(NOTIFICATIONS)
        val stillMissing = listOf(NOTIFICATIONS)
        assertTrue(walk.isFinished(stillMissing))
        assertFalse(walk.succeeded(stillMissing))
    }

    @Test
    fun `allow some and decline others in mixed order`() {
        var walk = PermissionWalk.start(all)!!
        val missing = all.toMutableList()
        missing.remove(NOTIFICATIONS)            // allowed
        walk = walk.decline(USAGE_ACCESS)       // declined
        assertEquals(OVERLAY, walk.current(missing))
        missing.remove(OVERLAY)                  // allowed
        assertEquals(BATTERY, walk.current(missing))
        walk = walk.decline(BATTERY)             // declined
        assertTrue(walk.isFinished(missing))
        assertFalse(walk.succeeded(missing))
    }

    @Test
    fun `only already-missing permissions are asked and numbered from one`() {
        val walk = PermissionWalk.start(listOf(OVERLAY, BATTERY))!!
        assertEquals(listOf(OVERLAY, BATTERY), walk.steps)
        assertEquals(1 to 2, walk.position(OVERLAY))
        assertEquals(2 to 2, walk.position(BATTERY))
    }

    @Test
    fun `first run excludes permissions declined in an earlier launch`() {
        val walk = PermissionWalk.start(all, exclude = setOf(NOTIFICATIONS, BATTERY))!!
        assertEquals(listOf(USAGE_ACCESS, OVERLAY), walk.steps)
    }

    @Test
    fun `first run with everything already declined starts no walk`() {
        assertNull(PermissionWalk.start(all, exclude = all.toSet()))
    }

    @Test
    fun `a permission revoked again after granting is asked again in the same walk`() {
        val walk = PermissionWalk.start(all)!!
        // Notifications granted, then turned off again before the walk ended.
        assertEquals(NOTIFICATIONS, walk.current(all))
    }
}
