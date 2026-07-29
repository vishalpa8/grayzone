package com.grayzone.app

import org.junit.Assert.assertEquals
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
        assertEquals("Display over other apps is off", SetupRequirements.headline(missing))
        assertTrue(SetupRequirements.body(missing).contains("display over other apps"))
    }

    @Test
    fun `accessibility alone is reported when overlay is granted`() {
        val missing = SetupRequirements.missing(accessibilityEnabled = false, overlayEnabled = true)

        assertEquals(listOf(SetupRequirement.ACCESSIBILITY), missing)
        assertEquals("Accessibility is off", SetupRequirements.headline(missing))
    }

    @Test
    fun `accessibility is offered first when both are missing`() {
        val missing = SetupRequirements.missing(accessibilityEnabled = false, overlayEnabled = false)

        assertEquals(listOf(SetupRequirement.ACCESSIBILITY, SetupRequirement.OVERLAY), missing)
        assertEquals("2 permissions are off", SetupRequirements.headline(missing))
        assertEquals("Turn on Accessibility", SetupRequirements.actionLabel(missing))
    }

    @Test
    fun `granted state has no action or body copy`() {
        val missing = SetupRequirements.missing(true, true)

        assertEquals("", SetupRequirements.body(missing))
        assertEquals("", SetupRequirements.actionLabel(missing))
    }
}
