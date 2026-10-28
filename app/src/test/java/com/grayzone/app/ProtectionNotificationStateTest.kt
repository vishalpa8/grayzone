package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionNotificationStateTest {

    @Test
    fun `healthy permissions keep active title`() {
        val copy = ProtectionNotificationState.resolve(
            usageAccessEnabled = true,
            overlayEnabled = true
        )
        assertFalse(copy.isWarning)
        assertEquals("Grayzone is active", copy.title)
    }

    @Test
    fun `usage access off surfaces warning title`() {
        val copy = ProtectionNotificationState.resolve(
            usageAccessEnabled = false,
            overlayEnabled = true
        )
        assertTrue(copy.isWarning)
        assertEquals("Protection is OFF", copy.title)
        assertEquals(SetupRequirement.USAGE_ACCESS, copy.fixRequirement)
    }

    @Test
    fun `overlay off surfaces warning title`() {
        val copy = ProtectionNotificationState.resolve(
            usageAccessEnabled = true,
            overlayEnabled = false
        )
        assertTrue(copy.isWarning)
        assertEquals("Protection is OFF", copy.title)
        assertEquals(SetupRequirement.OVERLAY, copy.fixRequirement)
    }
}
