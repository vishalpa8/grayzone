package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRequirementsTest {

    @Test
    fun `nothing missing when both granted`() {
        assertEquals(emptyList<SetupRequirement>(), SetupRequirements.missing(true, true))
    }

    @Test
    fun `overlay alone is reported when accessibility is granted`() {
        val missing = SetupRequirements.missing(accessibilityEnabled = true, overlayEnabled = false)

        assertEquals(listOf(SetupRequirement.OVERLAY), missing)
        assertEquals("Display over other apps off", SetupRequirements.headline(missing))
        assertEquals("Tap below to allow.", SetupRequirements.body(missing))
    }

    @Test
    fun `accessibility alone is reported when overlay is granted`() {
        val missing = SetupRequirements.missing(accessibilityEnabled = false, overlayEnabled = true)

        assertEquals(listOf(SetupRequirement.ACCESSIBILITY), missing)
        assertEquals("Accessibility off", SetupRequirements.headline(missing))
    }

    @Test
    fun `accessibility is offered first when both are missing`() {
        val missing = SetupRequirements.missing(accessibilityEnabled = false, overlayEnabled = false)

        assertEquals(listOf(SetupRequirement.ACCESSIBILITY, SetupRequirement.OVERLAY), missing)
        assertEquals("2 permissions off", SetupRequirements.headline(missing))
        assertEquals("Enable Accessibility", SetupRequirements.actionLabel(missing))
    }

    @Test
    fun `granted state has no action or body copy`() {
        val missing = SetupRequirements.missing(true, true)

        assertEquals("", SetupRequirements.body(missing))
        assertEquals("", SetupRequirements.actionLabel(missing))
    }

    @Test
    fun `notifications off is reported as a missing requirement`() {
        val missing = SetupRequirements.missing(
            accessibilityEnabled = true,
            overlayEnabled = true,
            notificationsEnabled = false,
        )

        assertEquals(listOf(SetupRequirement.NOTIFICATIONS), missing)
        assertEquals("Notifications off", SetupRequirements.headline(missing))
    }

    @Test
    fun `degrading permissions are reported without claiming protection ready`() {
        val missing = SetupRequirements.missing(
            accessibilityEnabled = true,
            overlayEnabled = true,
            notificationsEnabled = false,
            batteryExempt = false,
        )

        assertEquals(
            listOf(SetupRequirement.NOTIFICATIONS, SetupRequirement.BATTERY),
            missing
        )
        assertNotEquals("Protection ready", SetupRequirements.headline(missing))
        assertEquals("Setup incomplete", SetupRequirements.headline(missing))
    }

    @Test
    fun `battery follows core permissions in ordering`() {
        val missing = SetupRequirements.missing(
            accessibilityEnabled = false,
            overlayEnabled = true,
            notificationsEnabled = true,
            batteryExempt = false,
        )

        assertEquals(
            listOf(SetupRequirement.ACCESSIBILITY, SetupRequirement.BATTERY),
            missing
        )
    }

    @Test
    fun `restricted settings hint only for accessibility on API 33+`() {
        assertTrue(
            SetupRequirements.needsRestrictedSettingsHint(33, SetupRequirement.ACCESSIBILITY)
        )
        assertFalse(
            SetupRequirements.needsRestrictedSettingsHint(32, SetupRequirement.ACCESSIBILITY)
        )
        assertFalse(
            SetupRequirements.needsRestrictedSettingsHint(34, SetupRequirement.OVERLAY)
        )
    }
}
