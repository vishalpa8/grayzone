package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PerAppLimitsTest {

    @Test
    fun `custom overrides beat global`() {
        assertEquals(25, PerAppLimits.sessionMinutes(true, 25, 10))
        assertEquals(45, PerAppLimits.lockoutMinutes(true, 45, 60))
        assertEquals(12, PerAppLimits.waitSeconds(true, 12, 5))
    }

    @Test
    fun `global used when custom flag off`() {
        assertEquals(10, PerAppLimits.sessionMinutes(false, 99, 10))
        assertEquals(60, PerAppLimits.lockoutMinutes(false, 15, 60))
        assertEquals(5, PerAppLimits.waitSeconds(false, 30, 5))
    }

    @Test
    fun `session minutes clamp to ConfigValidator range`() {
        assertEquals(ConfigValidator.SESSION_MINUTES_RANGE.first, PerAppLimits.sessionMinutes(true, 0, 10))
        assertEquals(ConfigValidator.SESSION_MINUTES_RANGE.first, PerAppLimits.sessionMinutes(true, -5, 10))
        assertEquals(ConfigValidator.SESSION_MINUTES_RANGE.last, PerAppLimits.sessionMinutes(true, 99_999, 10))
    }

    @Test
    fun `lockout minutes clamp to per-app ConfigValidator range`() {
        assertEquals(ConfigValidator.PER_APP_LOCKOUT_MINUTES_RANGE.first, PerAppLimits.lockoutMinutes(true, 1, 60))
        assertEquals(ConfigValidator.PER_APP_LOCKOUT_MINUTES_RANGE.first, PerAppLimits.lockoutMinutes(true, 14, 60))
        assertEquals(ConfigValidator.PER_APP_LOCKOUT_MINUTES_RANGE.last, PerAppLimits.lockoutMinutes(true, 99_999, 60))
    }

    @Test
    fun `wait seconds clamp to ConfigValidator range`() {
        assertEquals(ConfigValidator.WAIT_SECONDS_RANGE.first, PerAppLimits.waitSeconds(true, 0, 5))
        assertEquals(ConfigValidator.WAIT_SECONDS_RANGE.last, PerAppLimits.waitSeconds(true, 120, 5))
    }
}
