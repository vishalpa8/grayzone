package com.grayzone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the settings-bypass guards that read live per-app state from
 * SharedPreferences:
 *   - [isAnyAppLocked]      → prevents disabling monitoring while locked/paused.
 *   - [stayedUnderAllBudgets] → gates streak / reward logic.
 *   - [getNormalizedRemainingMillis] → stale-pause expiry.
 *
 * These are the true "leak" surfaces: a wrong boolean here lets a user escape a
 * lock, so every boundary is pinned.
 */
class LockGuardLogicTest {

    private val now = 1_000_000L
    private val pkg = "com.test.app"
    private val apps = setOf(pkg)
    private val today = "2026-07-21"

    private fun prefs(vararg pairs: Pair<String, Any?>) =
        FakeSharedPreferences(mapOf(*pairs))

    // ─── isAnyAppLocked ──────────────────────────────────────────────────────

    @Test
    fun `no monitored apps is never locked`() {
        assertFalse(isAnyAppLocked(prefs(), emptySet(), now))
    }

    @Test
    fun `clean state is not locked`() {
        assertFalse(isAnyAppLocked(prefs(), apps, now))
    }

    @Test
    fun `active session counts as locked`() {
        val p = prefs(PrefsKeys.ACTIVE_UNTIL + pkg to now + 1_000L)
        assertTrue(isAnyAppLocked(p, apps, now))
    }

    @Test
    fun `active boundary at exactly now is not locked`() {
        val p = prefs(PrefsKeys.ACTIVE_UNTIL + pkg to now)
        assertFalse(isAnyAppLocked(p, apps, now))
    }

    @Test
    fun `penalty-box lockout counts as locked`() {
        val p = prefs(
            PrefsKeys.ACTIVE_UNTIL + pkg to now - 5_000L,
            PrefsKeys.LOCKED_UNTIL + pkg to now + 5_000L
        )
        assertTrue(isAnyAppLocked(p, apps, now))
    }

    @Test
    fun `lockout is inclusive of the lockedUntil instant`() {
        val p = prefs(
            PrefsKeys.ACTIVE_UNTIL + pkg to now - 5_000L,
            PrefsKeys.LOCKED_UNTIL + pkg to now
        )
        assertTrue(isAnyAppLocked(p, apps, now))
    }

    @Test
    fun `one ms past lockout is unlocked`() {
        val p = prefs(
            PrefsKeys.ACTIVE_UNTIL + pkg to now - 5_000L,
            PrefsKeys.LOCKED_UNTIL + pkg to now - 1L
        )
        assertFalse(isAnyAppLocked(p, apps, now))
    }

    @Test
    fun `paused remainder counts as locked`() {
        val p = prefs(PrefsKeys.REMAINING_MILLIS + pkg to 5_000L)
        assertTrue(isAnyAppLocked(p, apps, now))
    }

    @Test
    fun `stale paused remainder beyond 24h is treated as expired`() {
        val p = prefs(PrefsKeys.REMAINING_MILLIS + pkg to 24L * 60 * 60 * 1000L)
        assertFalse(isAnyAppLocked(p, apps, now))
    }

    @Test
    fun `any single locked app locks the whole set`() {
        val locked = "com.locked.app"
        val p = prefs(PrefsKeys.ACTIVE_UNTIL + locked to now + 10_000L)
        assertTrue(isAnyAppLocked(p, setOf(pkg, locked), now))
    }

    // ─── stayedUnderAllBudgets ───────────────────────────────────────────────

    @Test
    fun `apps without a budget always count as under budget`() {
        assertTrue(stayedUnderAllBudgets(prefs(), apps, today))
    }

    @Test
    fun `usage below today's budget is under`() {
        val p = prefs(
            PrefsKeys.DAILY_BUDGET_MINUTES + pkg to 30,
            PrefsKeys.DAILY_RESET_DATE + pkg to today,
            PrefsKeys.DAILY_USED_MILLIS + pkg to 10 * 60 * 1000L
        )
        assertTrue(stayedUnderAllBudgets(p, apps, today))
    }

    @Test
    fun `usage exactly at budget is not under`() {
        val p = prefs(
            PrefsKeys.DAILY_BUDGET_MINUTES + pkg to 30,
            PrefsKeys.DAILY_RESET_DATE + pkg to today,
            PrefsKeys.DAILY_USED_MILLIS + pkg to 30 * 60 * 1000L
        )
        assertFalse(stayedUnderAllBudgets(p, apps, today))
    }

    @Test
    fun `usage recorded on a previous day is ignored (fresh day)`() {
        val p = prefs(
            PrefsKeys.DAILY_BUDGET_MINUTES + pkg to 30,
            PrefsKeys.DAILY_RESET_DATE + pkg to "2026-07-20",
            PrefsKeys.DAILY_USED_MILLIS + pkg to 99 * 60 * 1000L
        )
        assertTrue(stayedUnderAllBudgets(p, apps, today))
    }

    @Test
    fun `one over-budget app fails the whole set`() {
        val other = "com.other.app"
        val p = prefs(
            PrefsKeys.DAILY_BUDGET_MINUTES + pkg to 30,
            PrefsKeys.DAILY_RESET_DATE + pkg to today,
            PrefsKeys.DAILY_USED_MILLIS + pkg to 5 * 60 * 1000L,
            PrefsKeys.DAILY_BUDGET_MINUTES + other to 30,
            PrefsKeys.DAILY_RESET_DATE + other to today,
            PrefsKeys.DAILY_USED_MILLIS + other to 40 * 60 * 1000L
        )
        assertFalse(stayedUnderAllBudgets(p, setOf(pkg, other), today))
    }

    // ─── getNormalizedRemainingMillis ────────────────────────────────────────

    @Test
    fun `normalized remainder clamps the full boundary range`() {
        val max = 24L * 60 * 60 * 1000L
        assertTrue(getNormalizedRemainingMillis(0L) == 0L)
        assertTrue(getNormalizedRemainingMillis(-100L) == 0L)
        assertTrue(getNormalizedRemainingMillis(1L) == 1L)
        assertTrue(getNormalizedRemainingMillis(max - 1L) == max - 1L)
        assertTrue(getNormalizedRemainingMillis(max) == 0L)
        assertTrue(getNormalizedRemainingMillis(max + 1L) == 0L)
    }
}
