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
 * Without this, both [OverlayService] (which monitors app usage and enforces
 * overlays) and [AdBlockVpnService] (which filters DNS) are dead after every
 * reboot — the user's rules are silently unenforced until they manually open
 * the app again.
 *
 * Registered for:
 *   android.intent.action.BOOT_COMPLETED       — normal boot
 *   android.intent.action.QUICKBOOT_POWERON    — HTC / OnePlus fast-boot
 *   com.htc.intent.action.QUICKBOOT_POWERON    — HTC variant
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action ?: return
        if (receivedAction !in BOOT_ACTIONS) return

        Log.d(TAG, "Boot detected ($receivedAction) — restarting Grayzone services")

        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        // Always restart OverlayService — it is the core monitoring service.
        try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
            Log.d(TAG, "OverlayService restart requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService: ${e.message}")
        }

        // Only restart AdBlockVpnService if the VPN was active when the device shut down.
        // We infer this from a persisted preference toggled by the UI — avoids requesting
        // VPN permission unexpectedly on fresh installs that never enabled the VPN.
        if (prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)) {
            try {
                val vpnIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_START
                }
                context.startForegroundService(vpnIntent)
                Log.d(TAG, "AdBlockVpnService restart requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AdBlockVpnService: ${e.message}")
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
