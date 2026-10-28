package com.grayzone.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
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

        GrayzoneLogger.i(LogComponent.BOOT, "Boot detected ($receivedAction); restoring services")

        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        // Always restart OverlayService; it is the core monitoring service. Its onCreate re-arms the
        // session-expiry alarms for any in-flight session, so a session that spans the reboot still
        // stops its countdown at 00:00 and locks at expiry.
        // Only when the user switched monitoring on; it is off by default.
        if (com.grayzone.app.isMonitoringEnabled(context)) {
            try {
                context.startForegroundService(Intent(context, OverlayService::class.java))
                GrayzoneLogger.i(LogComponent.BOOT, "Monitoring service restart requested")
            } catch (e: Exception) {
                GrayzoneLogger.e(LogComponent.BOOT, "Failed to restart the monitoring service after boot", e)
            }
        }

        // F-010: without usage access the monitoring service can't tell which app is open; surface it.
        if (!com.grayzone.app.hasUsageAccess(context)) {
            com.grayzone.app.data.ProtectionHealthRepository.updateUsageAccessStatus(false)
            GrayzoneLogger.w(LogComponent.BOOT, "Usage access is off after boot; monitoring cannot run until it is allowed again")
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
                GrayzoneLogger.i(LogComponent.VPN, "VPN 24h hold expired across reboot; VPN left off")
                return
            }
            // F-012: re-arm hold expiry for every still-active hold outcome (consent missing /
            // start failure included). Alarms do not survive reboot.
            if (VpnHoldLock.isHoldActive(holdState, nowWall, nowElapsed)) {
                VpnHoldExpiryScheduler.rearmFromPrefs(context)
            }
            // VpnService.prepare() returns non-null when consent is missing (e.g. after a reinstall).
            // Starting the tunnel then would fail inside establish(); skip and let the next app launch
            // resume it once consent is present, rather than churning a failing foreground service.
            // F-212: prepare() itself can throw on some OEMs — preserve VPN intent on throw.
            val consentNeeded = try {
                android.net.VpnService.prepare(context) != null
            } catch (e: Exception) {
                GrayzoneLogger.e(LogComponent.VPN, "VpnService.prepare failed after boot; VPN will resume on next app launch", e)
                return
            }
            if (consentNeeded) {
                GrayzoneLogger.w(LogComponent.VPN, "VPN consent missing after boot; VPN will resume once consent is granted")
                return
            }
            try {
                val vpnIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_START
                }
                context.startForegroundService(vpnIntent)
                GrayzoneLogger.i(LogComponent.VPN, "VPN restart requested after boot")
            } catch (e: Exception) {
                GrayzoneLogger.e(LogComponent.VPN, "Failed to restart the VPN after boot; it will retry on next app launch", e)
            }
        }
    }

    companion object {
        private val BOOT_ACTIONS = setOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
