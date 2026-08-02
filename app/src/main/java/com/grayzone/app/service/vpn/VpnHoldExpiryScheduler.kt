package com.grayzone.app.service.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent

/**
 * Arms a Doze-safe alarm for automatic VPN disable when the 24h hold expires.
 */
object VpnHoldExpiryScheduler {

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, VpnHoldExpiryReceiver::class.java).apply {
            action = VpnHoldExpiryReceiver.ACTION_HOLD_EXPIRED
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun arm(context: Context, expiresAtWallMs: Long) {
        if (expiresAtWallMs <= System.currentTimeMillis()) return
        val am = alarmManager(context)
        val pi = pendingIntent(context)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiresAtWallMs, pi)
            } else {
                // F-035: inexact path — Doze may defer automatic VPN disable.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiresAtWallMs, pi)
                GrayzoneLogger.w(
                    LogComponent.VPN,
                    "Exact alarms unavailable; VPN hold expiry may be delayed under Doze"
                )
            }
        } catch (e: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiresAtWallMs, pi)
                GrayzoneLogger.w(
                    LogComponent.VPN,
                    "Exact alarms unavailable; VPN hold expiry may be delayed under Doze"
                )
            } catch (e2: Exception) {
                GrayzoneLogger.e(LogComponent.VPN, "Failed to arm VPN hold expiry alarm", e2)
            }
        } catch (e: Exception) {
            GrayzoneLogger.e(LogComponent.VPN, "Failed to arm VPN hold expiry alarm", e)
        }
    }

    fun cancel(context: Context) {
        try {
            alarmManager(context).cancel(pendingIntent(context))
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.VPN, "Failed to cancel VPN hold expiry alarm: ${e.message}")
        }
    }

    /** Re-arm from persisted hold state (service restart / boot). */
    fun rearmFromPrefs(context: Context) {
        val state = VpnHoldLockStore.read(context)
        if (!state.isTracked) {
            cancel(context)
            return
        }
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (!VpnHoldLock.isHoldActive(state, nowWall, nowElapsed)) {
            // Already expired — let the service/receiver stop path handle it.
            return
        }
        arm(context, VpnHoldLock.expiresAtWallMs(state))
    }
}

/**
 * Fired when the 24h VPN hold ends — stops the VPN (authorized expiry path).
 */
class VpnHoldExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_HOLD_EXPIRED) return
        GrayzoneLogger.i(LogComponent.VPN, "VPN 24h hold expired — auto-disabling")
        val stop = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_HOLD_EXPIRED
        }
        try {
            context.startService(stop)
        } catch (e: Exception) {
            GrayzoneLogger.e(LogComponent.VPN, "Failed to deliver VPN hold expiry stop", e)
        }
    }

    companion object {
        const val ACTION_HOLD_EXPIRED = "com.grayzone.app.VPN_HOLD_EXPIRED"
    }
}
