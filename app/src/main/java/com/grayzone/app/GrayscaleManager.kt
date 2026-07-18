package com.grayzone.app

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Toggles Android's hardware Daltonizer for true system-wide grayscale.
 * Falls back to a semi-transparent overlay when [WRITE_SECURE_SETTINGS] is not granted.
 */
object GrayscaleManager {

    private const val TAG = "GrayscaleManager"
    /** [Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER] value for monochromacy. */
    private const val DALTONIZER_MONOCHROMACY = 0

    private var enabledByApp = false

    /** Returns true if Daltonizer was enabled successfully. */
    fun enable(context: Context): Boolean {
        return try {
            Settings.Secure.putString(
                context.contentResolver,
                "accessibility_display_daltonizer_enabled",
                "1"
            )
            Settings.Secure.putString(
                context.contentResolver,
                "accessibility_display_daltonizer",
                DALTONIZER_MONOCHROMACY.toString()
            )
            enabledByApp = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Daltonizer unavailable — grant WRITE_SECURE_SETTINGS via ADB")
            false
        }
    }

    /** Disables Daltonizer only if this app enabled it. */
    fun disable(context: Context) {
        if (!enabledByApp) return
        try {
            Settings.Secure.putString(
                context.contentResolver,
                "accessibility_display_daltonizer_enabled",
                "0"
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not disable Daltonizer: ${e.message}")
        }
        enabledByApp = false
    }

    fun isEnabledByApp(): Boolean = enabledByApp

    /** ADB command shown in Settings when hardware grayscale is unavailable. */
    fun adbGrantCommand(packageName: String): String =
        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
}
