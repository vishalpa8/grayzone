package com.grayzone.app.data

import com.grayzone.app.FakeSharedPreferences
import com.grayzone.app.PrefsKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class LockoutFormatMigrationTest {

    private val pkg = "com.instagram.android"
    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a lockout that also covers session time is cleared`() {
        // Old format: 10m session + 120m lockout stored as one 130m window.
        val prefs = FakeSharedPreferences(
            mutableMapOf<String, Any?>(
                PrefsKeys.LOCKOUT_MINUTES to 120,
                PrefsKeys.LOCKED_UNTIL + pkg to now + 130 * minute,
                PrefsKeys.LOCKED_STARTED_ELAPSED + pkg to 1_000L,
                PrefsKeys.LOCKED_DURATION_MS + pkg to 130 * minute
            )
        )

        assertEquals(1, LockoutFormatMigration.run(prefs))
        assertEquals(0L, prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L))
        assertEquals(0L, prefs.getLong(PrefsKeys.LOCKED_DURATION_MS + pkg, 0L))
    }

    @Test
    fun `a correctly sized lockout is left running`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf<String, Any?>(
                PrefsKeys.LOCKOUT_MINUTES to 120,
                PrefsKeys.LOCKED_UNTIL + pkg to now + 120 * minute,
                PrefsKeys.LOCKED_STARTED_ELAPSED + pkg to 1_000L,
                PrefsKeys.LOCKED_DURATION_MS + pkg to 120 * minute
            )
        )

        assertEquals(0, LockoutFormatMigration.run(prefs))
        assertEquals(now + 120 * minute, prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L))
    }

    @Test
    fun `per-app lockout overrides the global when sizing the check`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf<String, Any?>(
                PrefsKeys.LOCKOUT_MINUTES to 60,
                PrefsKeys.PER_APP_HAS_CUSTOM + pkg to true,
                PrefsKeys.PER_APP_LOCKOUT_MINUTES + pkg to 240,
                PrefsKeys.LOCKED_UNTIL + pkg to now + 240 * minute,
                PrefsKeys.LOCKED_STARTED_ELAPSED + pkg to 1_000L,
                PrefsKeys.LOCKED_DURATION_MS + pkg to 240 * minute
            )
        )

        assertEquals(0, LockoutFormatMigration.run(prefs))
    }

    @Test
    fun `migration only runs once`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf<String, Any?>(
                PrefsKeys.LOCKOUT_MINUTES to 120,
                PrefsKeys.LOCKED_UNTIL + pkg to now + 130 * minute,
                PrefsKeys.LOCKED_DURATION_MS + pkg to 130 * minute
            )
        )

        assertEquals(1, LockoutFormatMigration.run(prefs))
        assertEquals(0, LockoutFormatMigration.run(prefs))
    }
}
