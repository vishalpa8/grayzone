package com.grayzone.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import java.text.SimpleDateFormat
import java.util.*

/**
 * Background worker that periodically cleans up old usage events.
 * 
 * Prevents unbounded database growth by deleting events older than 90 days.
 * Scheduled to run weekly via WorkManager.
 * 
 * Expected database size:
 * - Without cleanup: ~20MB after 10 years (182,500 events)
 * - With cleanup: ~2MB stable (18,250 events max)
 */
class CleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val RETENTION_DAYS = 90L
    }
    
    override suspend fun doWork(): Result {
        return try {
            val dao = UsageDatabase.getInstance(applicationContext).usageDao()
            
            // Calculate cutoff date (90 days ago)
            val cutoffDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                Date(System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000)
            )
            
            val deletedCount = dao.deleteEventsOlderThan(cutoffDate)
            val remainingCount = dao.getEventCount()
            
            GrayzoneLogger.i(
                LogComponent.CLEANUP,
                "Database cleanup completed: deleted $deletedCount events older than $cutoffDate, $remainingCount remaining"
            )
            
            Result.success()
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.CLEANUP,
                "Database cleanup failed",
                e
            )
            Result.retry()
        }
    }
}
