package com.grayzone.app

/** Single source of truth for all SharedPreferences keys. */
object PrefsKeys {
    const val PREFS_NAME         = "GrayzonePrefs"

    // Feature flags
    const val GRAYZONE_ENABLED   = "grayzone_enabled"
    /** Appearance: [com.grayzone.app.ui.theme.ThemeMode] — system | light | dark. */
    const val THEME_MODE         = "theme_mode"

    /**
     * True once the user has been prompted for the *current* Accessibility-permission loss; reset to
     * false when the permission is next observed enabled. Enforces prompt-once-per-loss so the app
     * does not repeatedly nag when the OS revokes the service.
     */
    const val ACCESSIBILITY_PROMPTED_FOR_LOSS = "accessibility_prompted_for_loss"

    /**
     * True once first-run setup has been left behind. While false, onboarding stays on screen until
     * every required permission is granted, so a partially granted setup cannot drop the user into a
     * main UI that silently does nothing.
     */
    const val ONBOARDING_COMPLETED = "onboarding_completed"

    /** True once we've sent the user to the All-files-access screen for the hidden state backup. */
    const val ALL_FILES_PROMPTED = "all_files_prompted"

    // Timing settings (global defaults)
    const val WAIT_SECONDS       = "wait_seconds"
    const val SESSION_MINUTES    = "session_minutes"
    const val LOCKOUT_MINUTES    = "lockout_minutes"

    // Per-app state (append packageName)
    const val ACTIVE_UNTIL       = "active_until_"
    const val LOCKED_UNTIL       = "locked_until_"
    const val REMAINING_MILLIS   = "remaining_millis_"
    /** elapsedRealtime when the current active-until window was set (0 = wall-only). */
    const val ACTIVE_STARTED_ELAPSED = "active_started_elapsed_"
    /** Duration ms of the active window from ACTIVE_STARTED_ELAPSED. */
    const val ACTIVE_DURATION_MS = "active_duration_ms_"
    /** elapsedRealtime when the current lockout was set (0 = wall-only). */
    const val LOCKED_STARTED_ELAPSED = "locked_started_elapsed_"
    /** Duration ms of the lockout from LOCKED_STARTED_ELAPSED. */
    const val LOCKED_DURATION_MS = "locked_duration_ms_"

    // App list
    const val MONITORED_APPS     = "monitored_apps"
    /** True after one-shot Instagram/YouTube default seed has run (even if none were installed). */
    const val DEFAULT_APPS_SEEDED = "default_apps_seeded"

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
    const val FOCUS_STARTED_ELAPSED   = "focus_started_elapsed"
    const val FOCUS_DURATION_MS       = "focus_duration_ms"

    // ── Daily Break (1 hour/day where nothing is locked) ──────────────────
    const val BREAK_UNTIL             = "break_until"
    const val BREAK_USED_DATE         = "break_used_date"   // "yyyy-MM-dd", once per day
    const val BREAK_STARTED_ELAPSED   = "break_started_elapsed"
    const val BREAK_DURATION_MS_KEY   = "break_duration_ms"

    /** Zone ID last used for daily date-key resets; TZ jump must not clear lockouts. */
    const val DAILY_RESET_ZONE_ID     = "daily_reset_zone_id"

    // ── VPN app bypass ─────────────────────────────────────────────────────
    /** Extra packages the user excluded from the VPN tunnel. */
    const val VPN_BYPASS_APPS         = "vpn_bypass_apps"

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
    /** Wall-clock epoch when the current VPN hold started (0 = none). */
    const val VPN_HOLD_STARTED_WALL   = "vpn_hold_started_wall"
    /** elapsedRealtime when the current VPN hold started (0 = none). */
    const val VPN_HOLD_STARTED_ELAPSED = "vpn_hold_started_elapsed"
}

/** Overlay display modes sent via broadcast intent extra "overlay_mode". */
object OverlayMode {
    const val FRICTION     = 1  // Friction/pause screen before session starts
    const val LOCK         = 2  // Hard-lock screen (session expired)
    const val BUDGET_LOCK  = 5  // Daily budget exhausted lock
    const val SCHEDULE_LOCK = 6 // Schedule-based lock
}
