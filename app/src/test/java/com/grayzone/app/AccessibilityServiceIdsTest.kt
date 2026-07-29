package com.grayzone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceIdsTest {

    private val pkg = "com.grayzone.app"
    private val fullClass = "com.grayzone.app.service.GrayzoneAccessibilityService"
    private val relativeClass = ".service.GrayzoneAccessibilityService"

    @Test
    fun `null or blank setting is disabled`() {
        assertFalse(AccessibilityServiceIds.isServiceEnabled(null, pkg, fullClass))
        assertFalse(AccessibilityServiceIds.isServiceEnabled("", pkg, fullClass))
        assertFalse(AccessibilityServiceIds.isServiceEnabled("   ", pkg, fullClass))
    }

    @Test
    fun `full component name matches`() {
        val setting = "$pkg/$fullClass"
        assertTrue(AccessibilityServiceIds.isServiceEnabled(setting, pkg, fullClass))
    }

    @Test
    fun `relative short component name matches`() {
        val setting = "$pkg/$relativeClass"
        assertTrue(AccessibilityServiceIds.isServiceEnabled(setting, pkg, fullClass))
    }

    @Test
    fun `colon-separated list finds our service among others`() {
        val setting =
            "com.other.app/com.other.Service:$pkg/$fullClass:com.foo/.Bar"
        assertTrue(AccessibilityServiceIds.isServiceEnabled(setting, pkg, fullClass))
    }

    @Test
    fun `substring lookalike does not false-positive`() {
        // Old .contains(pkg + "/" + fullClass) would also be fine here, but a looser
        // contains could match a longer class name that shares our prefix.
        val impostor = "$pkg/${fullClass}Extra"
        assertFalse(AccessibilityServiceIds.isServiceEnabled(impostor, pkg, fullClass))
    }

    @Test
    fun `different package with same class suffix does not match`() {
        val setting = "com.evil.app/$fullClass"
        assertFalse(AccessibilityServiceIds.isServiceEnabled(setting, pkg, fullClass))
    }

    @Test
    fun `malformed entries are ignored`() {
        assertFalse(AccessibilityServiceIds.isServiceEnabled("noneslash", pkg, fullClass))
        assertFalse(AccessibilityServiceIds.isServiceEnabled("/onlyclass", pkg, fullClass))
        assertFalse(AccessibilityServiceIds.isServiceEnabled("onlypkg/", pkg, fullClass))
        assertTrue(
            AccessibilityServiceIds.isServiceEnabled(
                "noneslash:$pkg/$relativeClass",
                pkg,
                fullClass
            )
        )
    }
}
