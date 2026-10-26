package com.grayzone.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent

/**
 * Receives the Doze-safe session-expiry alarm (see [SessionExpiryScheduler]) and hands off to
 * [OverlayService], which owns the window/notification needed to clamp the countdown and, when
 * appropriate, show the lock. Delivering through a manifest-registered receiver means the alarm still
 * fires after process death; the AlarmManager while-idle exemption permits starting the foreground
 * service from here.
 */
class SessionExpiryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SESSION_EXPIRED) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        if (!com.grayzone.app.isMonitoringEnabled(context)) return
        try {
            val serviceIntent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SESSION_EXPIRED
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            GrayzoneLogger.e(
                LogComponent.OVERLAY,
                "Failed to deliver session expiry for $packageName",
                e
            )
        }
    }

    companion object {
        const val ACTION_SESSION_EXPIRED = "com.grayzone.app.ACTION_SESSION_EXPIRED"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
