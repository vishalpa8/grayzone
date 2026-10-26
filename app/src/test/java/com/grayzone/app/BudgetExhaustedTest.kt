package com.grayzone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isBudgetExhausted] freezes per-app limits and the app's switch once today's budget is spent,
 * so raising the budget can't be used to escape a budget lock.
 */
class BudgetExhaustedTest {

    private val pkg = "com.test.app"
    private val today get() = DateUtils.getCurrentDateKey()

    private fun prefs(vararg pairs: Pair<String, Any?>) = FakeSharedPreferences(mapOf(*pairs))

    @Test
    fun `no budget is never exhausted`() {
        assertFalse(isBudgetExhausted(prefs(PrefsKeys.DAILY_USED_MILLIS + pkg to 10_000_000L), pkg))
    }

    @Test
    fun `usage below budget is not exhausted`() {
        val p = prefs(
            PrefsKeys.DAILY_BUDGET_MINUTES + pkg to 30,
            PrefsKeys.DAILY_RESET_DATE + pkg to today,
            PrefsKeys.DAILY_USED_MILLIS + pkg to 29 * 60_000L
        )
        assertFalse(isBudgetExhausted(p, pkg))
    }

    @Test
    fun `usage at or over budget today is exhausted`() {
        val atLimit = prefs(
            PrefsKeys.DAILY_BUDGET_MINUTES + pkg to 30,
            PrefsKeys.DAILY_RESET_DATE + pkg to today,
            PrefsKeys.DAILY_USED_MILLIS + pkg to 30 * 60_000L
        )
        assertTrue(isBudgetExhausted(atLimit, pkg))
    }

    @Test
    fun `usage recorded on an earlier day does not count`() {
        val p = prefs(
            PrefsKeys.DAILY_BUDGET_MINUTES + pkg to 30,
            PrefsKeys.DAILY_RESET_DATE + pkg to "2000-01-01",
            PrefsKeys.DAILY_USED_MILLIS + pkg to 99 * 60_000L
        )
        assertFalse(isBudgetExhausted(p, pkg))
    }
}
