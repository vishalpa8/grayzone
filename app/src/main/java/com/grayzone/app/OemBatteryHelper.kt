package com.grayzone.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * OEM-specific battery optimization and auto-start helpers.
 * 
 * Problem: Xiaomi, Oppo, Vivo, Realme, OnePlus aggressively kill background services
 * even when battery optimization is disabled.
 * 
 * Solution: Detect OEM and provide intent to manufacturer-specific settings.
 */
object OemBatteryHelper {
    
    /**
     * Check if this device is from an OEM known for aggressive battery optimization.
     */
    fun needsOemOptimization(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer in listOf(
            "xiaomi", "oppo", "vivo", "realme", "oneplus", "huawei", "honor", "samsung"
        )
    }
    
    /**
     * Get the manufacturer name for display.
     */
    fun getManufacturerName(): String {
        return Build.MANUFACTURER.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase() else it.toString() 
        }
    }
    
    /**
     * Get OEM-specific instructions for the user.
     */
    fun getOptimizationInstructions(): String {
        return when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> "Enable 'Autostart' and set Battery Saver to 'No restrictions' in MIUI settings"
            "oppo" -> "Enable 'Startup Manager' and disable battery optimization in ColorOS settings"
            "vivo" -> "Enable 'Auto-start' in iManager and disable battery restrictions"
            "realme" -> "Enable 'Auto-start' and set to 'No restrictions' in battery settings"
            "oneplus" -> "Enable 'Auto-start' and disable battery optimization in OxygenOS settings"
            "huawei" -> "Enable 'Auto-launch' in Phone Manager and add to protected apps"
            "honor" -> "Enable 'Auto-launch' and add to protected apps in settings"
            "samsung" -> "Disable 'Put app to sleep' in Battery settings and add to Never sleeping apps"
            else -> "Disable battery optimization for this app in system settings"
        }
    }
    
    /**
     * Try to open OEM-specific battery/autostart settings.
     * Returns true if successful, false if intent not available.
     */
    fun openBatterySettings(context: Context): Boolean {
        val intent = getBatterySettingsIntent(context) ?: return false
        
        return try {
            context.startActivity(intent)
            GrayzoneLogger.i(
                LogComponent.BOOT,
                "Opened OEM battery settings for ${getManufacturerName()}"
            )
            true
        } catch (e: Exception) {
            GrayzoneLogger.w(
                LogComponent.BOOT,
                "Failed to open OEM battery settings",
                e
            )
            false
        }
    }
    
    /**
     * Get the appropriate intent for OEM-specific battery/autostart settings.
     */
    private fun getBatterySettingsIntent(context: Context): Intent? {
        val packageName = context.packageName
        
        return when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> {
                // Try MIUI autostart manager
                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            "oppo" -> {
                // Try ColorOS startup manager
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            "vivo" -> {
                // Try iManager autostart
                Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            "realme" -> {
                // Similar to Oppo (ColorOS-based)
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            "oneplus" -> {
                // OxygenOS battery optimization
                Intent().apply {
                    component = ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            "huawei", "honor" -> {
                // EMUI/Magic UI phone manager
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            "samsung" -> {
                // Samsung battery settings (One UI)
                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = android.net.Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            else -> {
                // Fall back to standard battery optimization settings
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent().apply {
                        action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    null
                }
            }
        }
    }
    
    /**
     * Check if we can verify the OEM settings were successful.
     * For most OEMs we can't programmatically verify this.
     */
    fun canVerifyOptimization(): Boolean {
        // Only standard Android battery optimization can be verified
        return Build.MANUFACTURER.lowercase() !in listOf(
            "xiaomi", "oppo", "vivo", "realme", "oneplus", "huawei", "honor"
        )
    }
}
