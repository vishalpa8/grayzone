package com.grayzone.app.data

import com.grayzone.app.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Retention-boundary coverage for [CleanupWorker.cutoffDateKey]. The cutoff key
 * is what the delete query compares against, so an off-by-N here would silently
 * delete recent data or never reclaim space.
 */
class CleanupWorkerTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Test
    fun `zero retention resolves to today`() {
        val now = System.currentTimeMillis()
        assertEquals(DateUtils.getCurrentDateKey(), CleanupWorker.cutoffDateKey(now, 0))
    }

    @Test
    fun `default retention is exactly 90 calendar days before today`() {
        val now = System.currentTimeMillis()
        val today = CleanupWorker.cutoffDateKey(now, 0)
        val cutoff = CleanupWorker.cutoffDateKey(now) // default RETENTION_DAYS

        // Round the millisecond delta to whole days so a DST transition inside the
        // window (a ±1h shift) can't turn 90 into 89 or 91.
        val days = Math.round(
            (fmt.parse(today)!!.time - fmt.parse(cutoff)!!.time) / 86_400_000.0
        )
        assertEquals(CleanupWorker.RETENTION_DAYS, days)
    }

    @Test
    fun `cutoff moves backward as retention grows`() {
        val now = System.currentTimeMillis()
        val shorter = fmt.parse(CleanupWorker.cutoffDateKey(now, 30))!!.time
        val longer = fmt.parse(CleanupWorker.cutoffDateKey(now, 120))!!.time
        assertEquals(true, longer < shorter)
    }
}
