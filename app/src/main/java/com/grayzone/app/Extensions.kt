package com.grayzone.app

import android.content.Context
import android.content.SharedPreferences

// ─── App Name ─────────────────────────────────────────────────────────────────

/** Returns the user-facing app label for [pkg], or [pkg] itself on failure. */
fun Context.getAppName(pkg: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) { pkg }

// ─── Duration Formatting ───────────────────────────────────────────────────────

/**
 * Single source-of-truth for formatting a seconds value into a human-readable string.
 * Examples: 7265 → "2h 1m 5s",  185 → "3m 5s",  45 → "0m 45s"
 */
fun formatDuration(seconds: Int): String {
    val hrs  = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) String.format("%dh %dm %ds", hrs, mins, secs)
    else String.format("%dm %ds", mins, secs)
}

// ─── Lock-State Guard ─────────────────────────────────────────────────────────

/**
 * Single source-of-truth: returns true if ANY monitored app is currently in an
 * active session, paused mid-session, or locked out.
 *
 * Reads a one-shot snapshot from [prefs] keyed against [now] so callers can
 * cache the result inside `remember(monitoredApps, currentTime)`.
 */
fun isAnyAppLocked(
    prefs: SharedPreferences,
    monitoredApps: Set<String>,
    now: Long
): Boolean = monitoredApps.any { pkg ->
    val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL  + pkg, 0L)
    val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL  + pkg, 0L)
    val remaining   = prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
    (now < activeUntil) || (now in (activeUntil + 1)..lockedUntil) || (remaining > 0)
}

// ─── Reflection Prompts (Single Source of Truth) ──────────────────────────────

/**
 * All default friction / reflection prompts.
 * Previously duplicated in OverlayService.REFLECTIONS and CustomPromptsSheet.DEFAULT_PROMPTS —
 * both now reference this object.
 */
object Prompts {
    val DEFAULT = listOf(
        "What were you hoping to feel by opening this?",
        "Is this impulse — or intention?",
        "What's the one thing you should be doing right now?",
        "Will this matter in an hour?",
        "Are you bored, anxious, or genuinely curious?",
        "What triggered this urge — stress, habit, or boredom?",
        "Could you do something more meaningful right now?",
        "Who needs your attention more than this app does?",
        "Is this a want or a need right now?",
        "What would your most focused self do instead?",
        "Are you running away from something?",
        "Can this wait 10 more minutes?"
    )
}
