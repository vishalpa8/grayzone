package com.grayzone.app

import java.text.SimpleDateFormat
import java.util.*

/**
 * Optimized date formatting utilities with ThreadLocal caching.
 * 
 * Problem: SimpleDateFormat is expensive to create (~1ms per instance)
 * and was being created repeatedly in hot paths (every app switch).
 * 
 * Solution: ThreadLocal cached formatters reused across calls.
 * Performance: ~100x faster than creating new instances.
 */
object DateUtils {
    
    // ThreadLocal formatters are thread-safe and reusable
    private val dateKeyFormatterCache = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    /**
     * A SimpleDateFormat pins the zone it was created in, so a cached instance would keep
     * stamping date keys in the old zone after the user travels or changes the time zone.
     */
    private fun dateKeyFormatter(): SimpleDateFormat =
        dateKeyFormatterCache.get()!!.apply { timeZone = TimeZone.getDefault() }
    
    // Cache for current date key and its expiry, held in a single object for atomicity.
    private data class DateCache(val key: String, val expiry: Long, val zoneId: String)
    @Volatile
    private var cache: DateCache? = null

    private const val CACHE_VALIDITY_MS = 60_000L  // 1 minute

    /**
     * Get current date key in "yyyy-MM-dd" format.
     *
     * Cached for up to 1 minute to avoid repeated formatting, but the cache
     * expiry is clamped to the next local midnight so an event logged in the
     * first minute of a new day can never be stamped with the previous day's
     * key (which would corrupt daily budgets and the runtime reset boundary).
     *
     * Used for daily budget tracking and usage event logging.
     */
    fun getCurrentDateKey(): String {
        val now = System.currentTimeMillis()
        val zoneId = TimeZone.getDefault().id

        // Return cached value if still valid (not past midnight, same time zone).
        val currentCache = cache
        if (currentCache != null && now < currentCache.expiry && currentCache.zoneId == zoneId) {
            return currentCache.key
        }

        // Format new date key
        val newKey = dateKeyFormatter().format(Date(now))
        cache = DateCache(newKey, minOf(now + CACHE_VALIDITY_MS, startOfNextDayMillis(now)), zoneId)

        return newKey
    }

    /** Local midnight at the start of the day after [now]. */
    private fun startOfNextDayMillis(now: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    /**
     * Format a timestamp in "yyyy-MM-dd" format.
     * Uses ThreadLocal cached formatter.
     */
    fun formatDateKey(timestamp: Long): String {
        return dateKeyFormatter().format(Date(timestamp))
    }
    
    fun formatDateKey(date: Date): String {
        return dateKeyFormatter().format(date)
    }

    fun parseDateKey(dateKey: String): Date? {
        return try {
            dateKeyFormatter().parse(dateKey)
        } catch (e: Exception) {
            null
        }
    }

    fun getYesterdayDateKey(): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        return dateKeyFormatter().format(calendar.time)
    }
    
    /**
     * Get milliseconds until next midnight (for scheduling daily resets).
     */
    fun millisUntilMidnight(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis - System.currentTimeMillis()
    }
    
    /**
     * Check if two timestamps are on the same calendar day.
     */
    fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        return formatDateKey(timestamp1) == formatDateKey(timestamp2)
    }

    /**
     * Returns true when the runtime session state should be reset for a new day.
     * Reset occurs once per day at midnight local time, keeping stats intact.
     */
    fun shouldResetDailyRuntimeState(now: Long, lastResetDateKey: String): Boolean {
        val currentDateKey = dateKeyFormatter().format(Date(now))
        return lastResetDateKey != currentDateKey
    }
}
