package com.grayzone.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records every app session — when the user opened a monitored app,
 * how long they used it, and what happened (blocked, completed, etc.).
 */
@Entity(tableName = "usage_events")
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
