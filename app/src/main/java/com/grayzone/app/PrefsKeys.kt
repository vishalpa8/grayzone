package com.grayzone.app

/** Single source of truth for all SharedPreferences keys. */
object PrefsKeys {
    const val PREFS_NAME         = "GrayzonePrefs"

    // Feature flags
    const val GRAYZONE_ENABLED   = "grayzone_enabled"
    const val GRAYSCALE_ENABLED  = "grayscale_enabled"

    // Timing settings (global defaults)
    const val WAIT_SECONDS       = "wait_seconds"
    const val SESSION_MINUTES    = "session_minutes"
    const val LOCKOUT_MINUTES    = "lockout_minutes"

    // Per-app state (append packageName)
    const val ACTIVE_UNTIL       = "active_until_"
    const val LOCKED_UNTIL       = "locked_until_"
    const val REMAINING_MILLIS   = "remaining_millis_"

    // App list
    const val MONITORED_APPS     = "monitored_apps"

    // ── Per-App Custom Limits (append packageName) ─────────────────────────
    const val PER_APP_WAIT_SECONDS    = "per_app_wait_"
    const val PER_APP_SESSION_MINUTES = "per_app_session_"
    const val PER_APP_LOCKOUT_MINUTES = "per_app_lockout_"
    const val PER_APP_HAS_CUSTOM      = "per_app_custom_"  // boolean flag

    // ── Daily Budget (append packageName) ──────────────────────────────────
    const val DAILY_BUDGET_MINUTES    = "daily_budget_"     // 0 = disabled
    const val DAILY_USED_MILLIS       = "daily_used_"
    const val DAILY_RESET_DATE        = "daily_reset_date_" // "yyyy-MM-dd"

    // ── Schedule / Focus Mode ──────────────────────────────────────────────
    const val SCHEDULE_RULES_JSON     = "schedule_rules_json"
    const val FOCUS_MODE_ACTIVE       = "focus_mode_active"
    const val FOCUS_MODE_UNTIL        = "focus_mode_until"

    // ── Custom Reflection Prompts ──────────────────────────────────────────
    const val CUSTOM_PROMPTS_JSON     = "custom_prompts_json"
    const val USE_CUSTOM_PROMPTS_ONLY = "use_custom_prompts_only"

    // ── Streaks & Gamification ─────────────────────────────────────────────
    const val CURRENT_STREAK          = "current_streak"
    const val LONGEST_STREAK          = "longest_streak"
    const val STREAK_LAST_DATE        = "streak_last_date"
    const val TOTAL_SESSIONS_BLOCKED  = "total_sessions_blocked"
    const val TOTAL_TIME_SAVED_MINS   = "total_time_saved_mins"
    const val ACHIEVEMENTS_JSON       = "achievements_json"

    // ── VPN ────────────────────────────────────────────────────────────────
    /** True when the user has started AdBlockVpnService and not explicitly stopped it. */
    const val VPN_ENABLED             = "vpn_enabled"
}

/** Overlay display modes sent via broadcast intent extra "overlay_mode". */
object OverlayMode {
    const val FRICTION     = 1  // Friction/pause screen before session starts
    const val LOCK         = 2  // Hard-lock screen (session expired)
    const val TINT         = 3  // Grayscale tint only (active session)
    const val REMOVE_TINT  = 4  // Remove tint (leaving monitored app)
    const val BUDGET_LOCK  = 5  // Daily budget exhausted lock
    const val SCHEDULE_LOCK = 6 // Schedule-based lock
}
