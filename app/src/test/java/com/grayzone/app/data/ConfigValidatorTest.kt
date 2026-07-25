package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the config validation/repair policy (report §3.7). Corrupt or
 * out-of-range preference values must clamp to safe defaults so the wellbeing
 * engine can never be weakened (e.g. a 0-second wait or 9999-minute session).
 */
class ConfigValidatorTest {

    @Test
    fun `in-range values are preserved`() {
        assertEquals(5, ConfigValidator.repairWaitSeconds(5))
        assertEquals(25, ConfigValidator.repairSessionMinutes(25))
        assertEquals(120, ConfigValidator.repairLockoutMinutes(120))
    }

    @Test
    fun `boundary values are accepted`() {
        assertTrue(ConfigValidator.isWaitSecondsValid(3))
        assertTrue(ConfigValidator.isWaitSecondsValid(30))
        assertTrue(ConfigValidator.isSessionMinutesValid(1))
        assertTrue(ConfigValidator.isSessionMinutesValid(60))
        assertTrue(ConfigValidator.isLockoutMinutesValid(15))
        assertTrue(ConfigValidator.isLockoutMinutesValid(300))
    }

    @Test
    fun `below-range values repair to defaults`() {
        assertFalse(ConfigValidator.isWaitSecondsValid(2))
        assertEquals(ConfigValidator.DEFAULT_WAIT_SECONDS, ConfigValidator.repairWaitSeconds(2))
        assertEquals(ConfigValidator.DEFAULT_WAIT_SECONDS, ConfigValidator.repairWaitSeconds(0))
        assertEquals(ConfigValidator.DEFAULT_WAIT_SECONDS, ConfigValidator.repairWaitSeconds(-99))

        assertEquals(ConfigValidator.DEFAULT_SESSION_MINUTES, ConfigValidator.repairSessionMinutes(0))
        assertEquals(ConfigValidator.DEFAULT_LOCKOUT_MINUTES, ConfigValidator.repairLockoutMinutes(14))
    }

    @Test
    fun `above-range values repair to defaults`() {
        assertEquals(ConfigValidator.DEFAULT_WAIT_SECONDS, ConfigValidator.repairWaitSeconds(31))
        assertEquals(ConfigValidator.DEFAULT_SESSION_MINUTES, ConfigValidator.repairSessionMinutes(61))
        assertEquals(ConfigValidator.DEFAULT_SESSION_MINUTES, ConfigValidator.repairSessionMinutes(9999))
        assertEquals(ConfigValidator.DEFAULT_LOCKOUT_MINUTES, ConfigValidator.repairLockoutMinutes(301))
    }
}
