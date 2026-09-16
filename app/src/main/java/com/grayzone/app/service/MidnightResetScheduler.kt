package com.grayzone.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.grayzone.app.DateUtils
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent

object MidnightResetScheduler {
    
    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MidnightResetReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun arm(context: Context) {
        val am = alarmManager(context)
        val pi = pendingIntent(context)
        val activeUntil = System.currentTimeMillis() + DateUtils.millisUntilMidnight() + 1000L
        
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activeUntil, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activeUntil, pi)
            }
        } catch (e: Exception) {
            GrayzoneLogger.e(LogComponent.OVERLAY, "Failed to arm midnight reset alarm", e)
        }
    }
}
