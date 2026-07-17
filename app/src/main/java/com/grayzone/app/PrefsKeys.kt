package com.grayzone.app

/** Single source of truth for all SharedPreferences keys. */
object PrefsKeys {
    const val PREFS_NAME         = "GrayzonePrefs"

    // Feature flags
    const val GRAYZONE_ENABLED   = "grayzone_enabled"
    const val GRAYSCALE_ENABLED  = "grayscale_enabled"

    // Timing settings
    const val WAIT_SECONDS       = "wait_seconds"
    const val SESSION_MINUTES    = "session_minutes"
    const val LOCKOUT_MINUTES    = "lockout_minutes"

    // Per-app state (append packageName)
    const val ACTIVE_UNTIL       = "active_until_"
    const val LOCKED_UNTIL       = "locked_until_"

    // App list
    const val MONITORED_APPS     = "monitored_apps"
}

/** Overlay display modes sent via broadcast intent extra "overlay_mode". */
object OverlayMode {
    const val FRICTION     = 1  // Friction/pause screen before session starts
    const val LOCK         = 2  // Hard-lock screen (session expired)
    const val TINT         = 3  // Grayscale tint only (active session)
    const val REMOVE_TINT  = 4  // Remove tint (leaving monitored app)
}
