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

    @Test
    fun sameZoneMidnight_doesNotClearPendingLockouts_whenElapsedRealtimeIsActive() {
        val prefs = FakeSharedPreferences(
            mapOf(
                PrefsKeys.MONITORED_APPS to emptySet<String>(),
                // The wall-clock deadline is tomorrow
                PrefsKeys.LOCKED_UNTIL + "com.pending.app" to 1_754_128_800_000L + 86400000L,
                PrefsKeys.LOCKED_STARTED_ELAPSED + "com.pending.app" to 100_000L,
                PrefsKeys.LOCKED_DURATION_MS + "com.pending.app" to 3_600_000L, // 1 hour
                PrefsKeys.DAILY_RESET_DATE + "runtime" to "2026-08-01",
                PrefsKeys.DAILY_RESET_ZONE_ID to "UTC"
            )
        )

        // The user fast-forwards the clock to tomorrow (to trigger the daily reset)
        // But the elapsed time has only advanced by 1000ms

        val cleared = RuntimeSessionReset.resetIfNeeded(
            prefs,
            now = 1_754_128_800_000L, // Wall-clock jumps 1 day ahead to trigger midnight reset
            nowElapsed = 101_000L, // Mocking elapsed time
            zoneId = "UTC"
        )

        // The reset *did* run
        assertTrue(cleared)
        
        // However, because the elapsedRealtime deadline is still pending, 
        // the lockout must NOT have been cleared!
        assertEquals(
            1_754_128_800_000L + 86400000L, 
            prefs.getLong(PrefsKeys.LOCKED_UNTIL + "com.pending.app", 0L)
        )
        assertEquals(
            100_000L,
            prefs.getLong(PrefsKeys.LOCKED_STARTED_ELAPSED + "com.pending.app", 0L)
        )
    }
}
