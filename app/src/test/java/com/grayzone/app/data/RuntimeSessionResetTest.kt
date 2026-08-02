package com.grayzone.app.data

import com.grayzone.app.FakeSharedPreferences
import com.grayzone.app.PrefsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionResetTest {

    @Test
    fun timezoneJumpAhead_doesNotClearLockouts() {
        val prefs = FakeSharedPreferences(
            mapOf(
                PrefsKeys.MONITORED_APPS to setOf("com.example.app"),
                PrefsKeys.LOCKED_UNTIL + "com.example.app" to 9_999_999_999L,
                PrefsKeys.DAILY_RESET_DATE + "runtime" to "2026-08-02",
                PrefsKeys.DAILY_RESET_ZONE_ID to "America/Los_Angeles"
            )
        )

        val cleared = RuntimeSessionReset.resetIfNeeded(
            prefs,
            now = 1_754_128_800_000L,
            zoneId = "Pacific/Kiritimati"
        )

        assertFalse(cleared)
        assertEquals(
            9_999_999_999L,
            prefs.getLong(PrefsKeys.LOCKED_UNTIL + "com.example.app", 0L)
        )
        assertEquals("Pacific/Kiritimati", prefs.getString(PrefsKeys.DAILY_RESET_ZONE_ID, null))
    }

    @Test
    fun sameZoneMidnight_clearsLockouts() {
        val prefs = FakeSharedPreferences(
            mapOf(
                PrefsKeys.MONITORED_APPS to setOf("com.example.app"),
                PrefsKeys.LOCKED_UNTIL + "com.example.app" to 9_999_999_999L,
                PrefsKeys.DAILY_RESET_DATE + "runtime" to "2026-08-01",
                PrefsKeys.DAILY_RESET_ZONE_ID to "UTC"
            )
        )

        val cleared = RuntimeSessionReset.resetIfNeeded(
            prefs,
            now = 1_754_128_800_000L,
            zoneId = "UTC"
        )

        assertTrue(cleared)
        assertEquals(0L, prefs.getLong(PrefsKeys.LOCKED_UNTIL + "com.example.app", 0L))
    }

    @Test
    fun sameZoneMidnight_clearsLockoutsForUnmonitoredPackages() {
        val prefs = FakeSharedPreferences(
            mapOf(
                PrefsKeys.MONITORED_APPS to emptySet<String>(),
                PrefsKeys.LOCKED_UNTIL + "com.unmonitored.app" to 9_999_999_999L,
                PrefsKeys.ACTIVE_UNTIL + "com.unmonitored.app" to 8_888_888_888L,
                PrefsKeys.DAILY_RESET_DATE + "runtime" to "2026-08-01",
                PrefsKeys.DAILY_RESET_ZONE_ID to "UTC"
            )
        )

        val cleared = RuntimeSessionReset.resetIfNeeded(
            prefs,
            now = 1_754_128_800_000L,
            zoneId = "UTC"
        )

        assertTrue(cleared)
        assertEquals(0L, prefs.getLong(PrefsKeys.LOCKED_UNTIL + "com.unmonitored.app", 0L))
        assertEquals(0L, prefs.getLong(PrefsKeys.ACTIVE_UNTIL + "com.unmonitored.app", 0L))
    }
}
