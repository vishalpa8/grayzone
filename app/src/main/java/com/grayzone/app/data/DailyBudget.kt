package com.grayzone.app.data

/**
 * Pure daily-budget math shared by OverlayService (session state + usage logging).
 * Extracted so midnight rollover, exhaustion, and mid-break behavior can be unit-tested
 * without standing up the full service.
 */
object DailyBudget {

    /** Remaining budget in ms. Unlimited (budgetMins <= 0) → [Long.MAX_VALUE]. */
    fun remainingMs(
        budgetMins: Int,
        dateKey: String,
        lastResetDateKey: String?,
        usedMs: Long
    ): Long {
        if (budgetMins <= 0) return Long.MAX_VALUE
        val effectiveUsed = if (lastResetDateKey == dateKey) usedMs.coerceAtLeast(0L) else 0L
        return (budgetMins * 60 * 1000L) - effectiveUsed
    }

    /**
     * Accumulates usage for today. If [lastResetDateKey] is a different day,
     * today's usage starts fresh at [durationMs].
     */
    fun accumulateUsedMs(
        dateKey: String,
        lastResetDateKey: String?,
        previousUsedMs: Long,
        durationMs: Long
    ): Long {
        if (durationMs <= 0L) {
            return if (lastResetDateKey == dateKey) previousUsedMs.coerceAtLeast(0L) else 0L
        }
        return if (lastResetDateKey == dateKey) {
            previousUsedMs.coerceAtLeast(0L) + durationMs
        } else {
            durationMs
        }
    }

    /**
     * The session length that should actually be granted, never exceeding the
     * remaining daily budget. For unlimited budgets [budgetRemainingMs] is
     * [Long.MAX_VALUE], so the full requested [sessionMs] is returned.
     *
     * Prevents a fresh full-length session from overshooting a nearly-exhausted
     * daily budget (e.g. 20-min budget, 10-min sessions: with 3 min left the
     * grant is clamped to 3 min instead of handing out another 10).
     */
    fun grantedSessionMs(sessionMs: Long, budgetRemainingMs: Long): Long =
        if (budgetRemainingMs <= 0L) sessionMs else minOf(sessionMs, budgetRemainingMs)

    /** True when the budget lock overlay should be shown immediately. */
    fun shouldShowBudgetLock(
        usedMs: Long,
        budgetMins: Int,
        isStillForeground: Boolean,
        isOnBreak: Boolean
    ): Boolean {
        if (budgetMins <= 0) return false
        if (!isStillForeground) return false
        if (isOnBreak) return false
        return usedMs >= budgetMins * 60 * 1000L
    }
}
