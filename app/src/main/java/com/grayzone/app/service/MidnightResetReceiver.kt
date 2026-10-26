package com.grayzone.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.grayzone.app.PrefsKeys

class MidnightResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (com.grayzone.app.data.RuntimeSessionReset.resetIfNeeded(prefs)) {
            com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.OVERLAY, "Midnight reset triggered by AlarmManager")
        }

        // Notify OverlayService if running to refresh UI
        if (com.grayzone.app.isMonitoringEnabled(context)) {
            try {
                val serviceIntent = Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_MIDNIGHT_RESET
                }
                context.startService(serviceIntent)
            } catch (e: Exception) {
                // Ignore if service is not running or can't be started
            }
        }
        
        // Re-arm for the next day
        MidnightResetScheduler.arm(context)
    }
}
