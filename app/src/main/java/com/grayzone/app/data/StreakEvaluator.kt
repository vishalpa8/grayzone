package com.grayzone.app.data

import android.content.Context
import com.grayzone.app.DateUtils
import com.grayzone.app.PrefsKeys

/**
 * Runs the daily streak catch-up from device state (SharedPreferences + the usage
 * database) so streak progress no longer depends on the Home screen being opened.
 *
 * Safe to call from multiple entry points — process start, the midnight reset,
 * and Home's ON_RESUME — because [StreakManager.evaluatePendingDays] is
 * idempotent per calendar day and never advances past yesterday.
 */
object StreakEvaluator {

    suspend fun run(context: Context) {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val monitored = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()

        // Only apps with an actual daily budget (>0) gate the "stayed under budget" day.
        val budgetMinsByPkg = monitored.mapNotNull { pkg ->
            val mins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
            if (mins > 0) pkg to mins else null
        }.toMap()

        val dao = UsageDatabase.getInstance(context).usageDao()
        StreakManager(context).evaluatePendingDays(
            yesterdayKey = DateUtils.getYesterdayDateKey(),
            budgetMinsByPkg = budgetMinsByPkg
        ) { dateKey ->
            dao.getDailySummary(dateKey).associate { it.packageName to it.totalMillis }
        }
    }
}
