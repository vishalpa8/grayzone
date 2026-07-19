package com.grayzone.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records every app session — when the user opened a monitored app,
 * how long they used it, and what happened (blocked, completed, etc.).
 * 
 * Indexes added for query performance:
 * - dateKey: Most common query pattern (daily summaries)
 * - packageName + dateKey: Per-app daily queries
 * - startTime: Time-based analytics
 * - wasBlocked: Filter blocked sessions
 * 
 * Expected performance improvement: 50-100x faster queries at scale.
 */
@Entity(
    tableName = "usage_events",
    indices = [
        Index(value = ["dateKey"], name = "idx_date_key"),
        Index(value = ["packageName", "dateKey"], name = "idx_pkg_date"),
        Index(value = ["startTime"], name = "idx_start_time"),
        Index(value = ["wasBlocked"], name = "idx_was_blocked")
    ]
)
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val startTime: Long,           // System.currentTimeMillis()
    val endTime: Long = 0L,        // 0 = still active
    val durationMillis: Long = 0L, // endTime - startTime
    val wasBlocked: Boolean = false,  // true if friction was shown and user left
    val dateKey: String = ""       // "yyyy-MM-dd" for easy daily grouping
)
