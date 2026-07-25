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
import java.util.Calendar

/**
 * Regression test for D4: [UsageDao.getStartTimeTotals] must aggregate usage by
 * hour-of-day, not by raw millisecond timestamp.
 *
 * The historical bug aliased the computed hour column as `startTime`, colliding
 * with the real `startTime` column so `GROUP BY startTime` grouped per event
 * (one row each) instead of per hour. This test inserts several events across two
 * local hours (some sharing an hour on different days) and asserts the result is
 * bucketed by hour with correct sums and peak ordering.
 *
 * Requires a device/emulator (real SQLite for strftime 'localtime').
 */
@RunWith(AndroidJUnit4::class)
class PeakHourAggregationTest {

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
    fun tearDown() {
        db.close()
    }

    /** Local-time millis for a specific day/hour so buckets are timezone-independent. */
    private fun localMillis(day: Int, hour: Int): Long {
        val c = Calendar.getInstance()
        c.set(2024, Calendar.JANUARY, day, hour, 30, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun event(day: Int, hour: Int, durationMs: Long) = UsageEvent(
        packageName = "com.test.app",
        appName = "Test",
        startTime = localMillis(day, hour),
        endTime = localMillis(day, hour) + durationMs,
        durationMillis = durationMs,
        wasBlocked = false,
        dateKey = "2024-01-%02d".format(day)
    )

    @Test
    fun peakHourTotalsAreBucketedByHour() = runBlocking {
        // Hour 9: three events across two different days -> must collapse to ONE bucket.
        dao.insertEvent(event(day = 1, hour = 9, durationMs = 60_000))
        dao.insertEvent(event(day = 1, hour = 9, durationMs = 120_000))
        dao.insertEvent(event(day = 2, hour = 9, durationMs = 30_000))
        // Hour 14: two events, the largest total -> the peak bucket.
        dao.insertEvent(event(day = 1, hour = 14, durationMs = 500_000))
        dao.insertEvent(event(day = 2, hour = 14, durationMs = 500_000))

        val totals = dao.getStartTimeTotals()

        // Bucketed by hour, so 5 events collapse to exactly 2 rows (the D4 bug
        // would return 5 rows — one per distinct millisecond timestamp).
        assertEquals(2, totals.size)

        val byHour = totals.associate { it.hourOfDay to it.totalMillis }
        assertEquals(210_000L, byHour[9])
        assertEquals(1_000_000L, byHour[14])

        // Ordered by totalMillis DESC -> peak hour first.
        assertEquals(14L, totals.first().hourOfDay)
    }
}
