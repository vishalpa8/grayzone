package com.grayzone.app.data

import androidx.room.*

/**
 * Data Access Object for usage tracking queries.
 */
@Dao
interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: UsageEvent)

    // ── Daily Summaries ────────────────────────────────────────────────────

    /** Total usage time per app for a given date. */
    @Query("SELECT packageName, appName, SUM(durationMillis) as totalMillis, COUNT(*) as sessionCount FROM usage_events WHERE dateKey = :dateKey GROUP BY packageName ORDER BY totalMillis DESC")
    suspend fun getDailySummary(dateKey: String): List<DailySummaryRow>

    /** Total usage time for a specific app on a given date. */
    @Query("SELECT SUM(durationMillis) FROM usage_events WHERE packageName = :packageName AND dateKey = :dateKey")
    suspend fun getDailyUsageForApp(packageName: String, dateKey: String): Long?

    // ── Weekly / Aggregate Stats ───────────────────────────────────────────

    /** Total usage per day for the last N days. */
    @Query("SELECT dateKey, SUM(durationMillis) as totalMillis FROM usage_events WHERE dateKey >= :fromDate GROUP BY dateKey ORDER BY dateKey ASC")
    suspend fun getWeeklyTotals(fromDate: String): List<DateTotalRow>

    /** Total sessions blocked (friction shown and user left). */
    @Query("SELECT COUNT(*) FROM usage_events WHERE wasBlocked = 1")
    suspend fun getTotalSessionsBlocked(): Int

    /** Total duration of sessions that ended in a block (user resisted). */
    @Query("SELECT SUM(durationMillis) FROM usage_events WHERE wasBlocked = 1")
    suspend fun getTotalBlockedDurationMillis(): Long?

    /** Total sessions across all time. */
    @Query("SELECT COUNT(*) FROM usage_events")
    suspend fun getTotalSessions(): Int

    /** All events for a given date, ordered by time. */
    @Query("SELECT * FROM usage_events WHERE dateKey = :dateKey ORDER BY startTime DESC")
    suspend fun getEventsForDate(dateKey: String): List<UsageEvent>

    /** All events for advanced analytics. */
    @Query("SELECT * FROM usage_events")
    suspend fun getAllEvents(): List<UsageEvent>

    /**
     * Returns total durationMillis per startTime bucket for peak-hour detection.
     * We intentionally avoid SQLite strftime/datetime functions — they are not
     * available on all Android versions inside Room. Kotlin does the hour extraction.
     */
    @Query("SELECT startTime, SUM(durationMillis) as totalMillis FROM usage_events GROUP BY startTime ORDER BY totalMillis DESC")
    suspend fun getStartTimeTotals(): List<StartTimeTotalRow>

    /** Total number of events ever recorded — used for empty-state detection. */
    @Query("SELECT COUNT(*) FROM usage_events")
    suspend fun getTotalEventCount(): Int

    /** Per-app total usage for the last N days. */
    @Query("SELECT packageName, appName, SUM(durationMillis) as totalMillis, COUNT(*) as sessionCount FROM usage_events WHERE dateKey >= :fromDate GROUP BY packageName ORDER BY totalMillis DESC")
    suspend fun getAppUsageSince(fromDate: String): List<DailySummaryRow>

    // ── Data Retention & Cleanup ───────────────────────────────────────────

    /** Delete events older than the specified date (for 90-day retention policy). */
    @Query("DELETE FROM usage_events WHERE dateKey < :cutoffDate")
    suspend fun deleteEventsOlderThan(cutoffDate: String): Int

    /** Get total number of events in database (for cleanup monitoring). */
    @Query("SELECT COUNT(*) FROM usage_events")
    suspend fun getEventCount(): Int
}

/** Row returned by daily summary queries. */
data class DailySummaryRow(
    val packageName: String,
    val appName: String,
    val totalMillis: Long,
    val sessionCount: Int
)

/** Row returned by weekly total queries. */
data class DateTotalRow(
    val dateKey: String,
    val totalMillis: Long
)

/** Row returned by start-time totals query. */
data class StartTimeTotalRow(
    val startTime: Long,
    val totalMillis: Long
)
