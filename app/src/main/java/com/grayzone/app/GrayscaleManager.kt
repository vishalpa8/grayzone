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

    /**
     * Must be called on OverlayService.onCreate() — before any session logic runs.
     *
     * Problem: [enabledByApp] is an in-memory flag. If the service process is killed by
     * the OS (common on MIUI / ColorOS), the flag resets to false while Daltonizer is
     * still turned ON in the global secure settings. The phone stays stuck in
     * black-and-white with no obvious recovery path.
     *
     * Fix: On start-up, read the actual system setting. If Daltonizer is currently ON
     * but no app has an active session right now, turn it off unconditionally.
     * If Daltonizer is ON and a session IS active we simply re-adopt the flag so that
     * the normal [disable] path will clean it up when the session ends.
     */
    fun reconcileOnStart(context: Context, hasActiveSession: Boolean) {
        val isCurrentlyOn = Settings.Secure.getString(
            context.contentResolver,
            "accessibility_display_daltonizer_enabled"
        ) == "1"

        if (!isCurrentlyOn) {
            // Nothing to do — system is already in the correct off state.
            enabledByApp = false
            return
        }

        if (hasActiveSession) {
            // Daltonizer is on and a session is legitimately running — re-adopt ownership
            // so the normal dismissTint() → disable() path will handle cleanup.
            enabledByApp = true
            Log.d(TAG, "reconcileOnStart: Daltonizer is ON, active session found — re-adopted ownership")
        } else {
            // Daltonizer is on but there is NO active session. This means the service was
            // killed mid-session and never got to call disable(). Turn it off now.
            Log.w(TAG, "reconcileOnStart: Daltonizer stuck ON with no active session — force-disabling")
            enabledByApp = true   // temporarily so disable() won't short-circuit
            disable(context)
        }
    }

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
