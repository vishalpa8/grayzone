package com.grayzone.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent

/**
 * Arms/cancels a Doze-safe, process-death-safe alarm that fires at a session's `activeUntil`.
 *
 * Replaces the old in-memory coroutine `delay()` lock path, which did not survive Doze/screen-off or
 * process death and left the countdown notification ticking into negative values. See
 * specs/002-fix-timeout-accessibility-bugs/research.md (R2).
 */
object SessionExpiryScheduler {

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context, packageName: String): PendingIntent {
        val intent = Intent(context, SessionExpiryReceiver::class.java).apply {
            action = SessionExpiryReceiver.ACTION_SESSION_EXPIRED
            putExtra(SessionExpiryReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Arm the expiry alarm for [packageName] to fire at [activeUntil] (epoch millis, matching
     * `System.currentTimeMillis()`). Uses an exact Doze-safe alarm when already permitted, otherwise
     * falls back to the inexact Doze-safe alarm (no new user-facing permission required).
     */
    fun arm(context: Context, packageName: String, activeUntil: Long) {
        if (activeUntil <= System.currentTimeMillis()) return // Already past; caller handles immediate expiry.
        val am = alarmManager(context)
        val pi = pendingIntent(context, packageName)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activeUntil, pi)
            } else {
                // F-003: inexact path is the default on API 31+ without exact-alarm permission.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activeUntil, pi)
                GrayzoneLogger.w(
                    LogComponent.OVERLAY,
                    "Exact alarms unavailable; session lock for $packageName may be delayed under Doze"
                )
            }
        } catch (e: SecurityException) {
            // Exact-alarm permission changed between check and call — fall back to the inexact path.
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activeUntil, pi)
                GrayzoneLogger.w(
                    LogComponent.OVERLAY,
                    "Exact alarms unavailable; session lock for $packageName may be delayed under Doze"
                )
            } catch (e2: Exception) {
                GrayzoneLogger.e(
                    LogComponent.OVERLAY,
                    "Failed to arm session expiry alarm for $packageName",
                    e2
                )
            }
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.OVERLAY,
                "Failed to arm session expiry alarm for $packageName",
                e
            )
        }
    }

    /** Cancel any pending expiry alarm for [packageName]. */
    fun cancel(context: Context, packageName: String) {
        try {
            alarmManager(context).cancel(pendingIntent(context, packageName))
        } catch (e: Exception) {
            GrayzoneLogger.w(
                LogComponent.OVERLAY,
                "Failed to cancel session expiry alarm for $packageName: ${e.message}"
            )
        }
    }
}
