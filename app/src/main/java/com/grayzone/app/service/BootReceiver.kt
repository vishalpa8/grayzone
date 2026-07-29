package com.grayzone.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.grayzone.app.PrefsKeys
import com.grayzone.app.service.vpn.AdBlockVpnService
import com.grayzone.app.service.vpn.VpnHoldExpiryScheduler
import com.grayzone.app.service.vpn.VpnHoldLock
import com.grayzone.app.service.vpn.VpnHoldLockStore

/**
 * Restarts Grayzone's foreground services after the device boots.
 *
 * If restart fails, the persisted user intent is left unchanged so a later app
 * launch or recovery path can retry instead of silently forgetting that
 * protection should be active.
 *
 * Registered for:
 *   android.intent.action.BOOT_COMPLETED       - normal boot
 *   android.intent.action.QUICKBOOT_POWERON    - HTC / OnePlus fast-boot
 *   com.htc.intent.action.QUICKBOOT_POWERON    - HTC variant
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action ?: return
        if (receivedAction !in BOOT_ACTIONS) return

        Log.d(TAG, "Boot detected ($receivedAction); restarting Grayzone services")

        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        // Always restart OverlayService; it is the core monitoring service. Its onCreate re-arms the
        // session-expiry alarms for any in-flight session, so a session that spans the reboot still
        // stops its countdown at 00:00 and locks at expiry.
        try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
            Log.d(TAG, "OverlayService restart requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService: ${e.message}")
        }

        // Only restart AdBlockVpnService if the user previously enabled the VPN.
        // If a 24h hold was tracked but already expired across the reboot, clear intent and skip.
        if (prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)) {
            val holdState = VpnHoldLockStore.read(prefs)
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            if (holdState.isTracked && !VpnHoldLock.isHoldActive(holdState, nowWall, nowElapsed)) {
                prefs.edit().putBoolean(PrefsKeys.VPN_ENABLED, false).apply()
                VpnHoldLockStore.clear(prefs)
                Log.d(TAG, "VPN hold expired across reboot — cleared enable flag, not restarting")
                return
            }
            try {
                val vpnIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_START
                }
                context.startForegroundService(vpnIntent)
                Log.d(TAG, "AdBlockVpnService restart requested")
                if (VpnHoldLock.isHoldActive(holdState, nowWall, nowElapsed)) {
                    VpnHoldExpiryScheduler.rearmFromPrefs(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AdBlockVpnService; restore intent preserved: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "GrayzoneBootReceiver"

        private val BOOT_ACTIONS = setOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
