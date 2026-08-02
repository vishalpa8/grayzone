package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionNotificationStateTest {

    @Test
    fun `healthy permissions keep active title`() {
        val copy = ProtectionNotificationState.resolve(
            accessibilityEnabled = true,
            overlayEnabled = true
        )
        assertFalse(copy.isWarning)
        assertEquals("Grayzone is active", copy.title)
    }

    @Test
    fun `accessibility off surfaces warning title`() {
        val copy = ProtectionNotificationState.resolve(
            accessibilityEnabled = false,
            overlayEnabled = true
        )
        assertTrue(copy.isWarning)
        assertEquals("Protection is OFF", copy.title)
        assertEquals(SetupRequirement.ACCESSIBILITY, copy.fixRequirement)
    }

    @Test
    fun `overlay off surfaces warning title`() {
        val copy = ProtectionNotificationState.resolve(
            accessibilityEnabled = true,
            overlayEnabled = false
        )
        assertTrue(copy.isWarning)
        assertEquals("Protection is OFF", copy.title)
        assertEquals(SetupRequirement.OVERLAY, copy.fixRequirement)
    }
}
