package com.grayzone.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coverage for the usage-stats aggregation queries (report §3.7): daily/weekly
 * summaries, blocked totals, per-app usage, and the 90-day retention delete.
 *
 * Requires a device/emulator (real SQLite for SUM/GROUP BY).
 */
@RunWith(AndroidJUnit4::class)
class UsageDaoStatsTest {

    private lateinit var db: UsageDatabase
    private lateinit var dao: UsageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UsageDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.usageDao()
    }

    @After
    fun tearDown() = db.close()

    private fun event(
        pkg: String,
        date: String,
        durationMs: Long,
        blocked: Boolean = false,
        start: Long = 1_000L
    ) = UsageEvent(
        packageName = pkg,
        appName = pkg.substringAfterLast('.'),
        startTime = start,
        endTime = start + durationMs,
        durationMillis = durationMs,
        wasBlocked = blocked,
        dateKey = date
    )

    @Test
    fun dailySummarySumsAndCountsPerApp() = runBlocking {
        dao.insertEvent(event("com.a", "2024-05-01", 1000))
        dao.insertEvent(event("com.a", "2024-05-01", 2000))
        dao.insertEvent(event("com.b", "2024-05-01", 500))
        dao.insertEvent(event("com.a", "2024-05-02", 9999)) // different day, excluded

        val summary = dao.getDailySummary("2024-05-01").associateBy { it.packageName }
        assertEquals(2, summary.size)
        assertEquals(3000L, summary.getValue("com.a").totalMillis)
        assertEquals(2, summary.getValue("com.a").sessionCount)
        assertEquals(500L, summary.getValue("com.b").totalMillis)

        assertEquals(3000L, dao.getDailyUsageForApp("com.a", "2024-05-01"))
    }

    @Test
    fun weeklyTotalsAreGroupedByDateAscending() = runBlocking {
        dao.insertEvent(event("com.a", "2024-05-01", 1000))
        dao.insertEvent(event("com.b", "2024-05-01", 1000))
        dao.insertEvent(event("com.a", "2024-05-03", 5000))
        dao.insertEvent(event("com.a", "2024-04-01", 7777)) // before the window

        val totals = dao.getWeeklyTotals("2024-05-01")
        assertEquals(2, totals.size)
        assertEquals("2024-05-01", totals[0].dateKey)
        assertEquals(2000L, totals[0].totalMillis)
        assertEquals("2024-05-03", totals[1].dateKey)
        assertEquals(5000L, totals[1].totalMillis)
    }

    @Test
    fun blockedTotalsCountOnlyBlockedSessions() = runBlocking {
        dao.insertEvent(event("com.a", "2024-05-01", 1000, blocked = true))
        dao.insertEvent(event("com.a", "2024-05-01", 3000, blocked = true))
        dao.insertEvent(event("com.a", "2024-05-01", 9000, blocked = false))

        assertEquals(2, dao.getTotalSessionsBlocked())
        assertEquals(4000L, dao.getTotalBlockedDurationMillis())
        assertEquals(3, dao.getTotalSessions())
    }

    @Test
    fun cleanupDeletesOnlyEventsBeforeCutoff() = runBlocking {
        dao.insertEvent(event("com.a", "2024-01-01", 1000)) // old
        dao.insertEvent(event("com.a", "2024-01-10", 1000)) // old
        dao.insertEvent(event("com.a", "2024-03-01", 1000)) // kept (== cutoff, not < )
        dao.insertEvent(event("com.a", "2024-03-05", 1000)) // kept

        val deleted = dao.deleteEventsOlderThan("2024-03-01")
        assertEquals(2, deleted)
        assertEquals(2, dao.getEventCount())
    }
}
