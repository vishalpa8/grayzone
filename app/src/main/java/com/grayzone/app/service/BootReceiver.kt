package com.grayzone.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.grayzone.app.PrefsKeys
import com.grayzone.app.service.vpn.AdBlockVpnService

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

        // Always restart OverlayService; it is the core monitoring service.
        try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
            Log.d(TAG, "OverlayService restart requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService: ${e.message}")
        }

        // Only restart AdBlockVpnService if the user previously enabled the VPN.
        // This flag represents restore intent, so failures below must not clear it.
        if (prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)) {
            try {
                val vpnIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_START
                }
                context.startForegroundService(vpnIntent)
                Log.d(TAG, "AdBlockVpnService restart requested")
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
