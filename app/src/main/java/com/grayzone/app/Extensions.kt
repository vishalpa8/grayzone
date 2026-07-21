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

private const val MAX_PAUSED_SESSION_MILLIS = 24L * 60 * 60 * 1000L

/**
 * Returns a pause duration only while it is still within the supported 24-hour window.
 * Any stale or oversized values are treated as expired and reset to zero.
 */
fun getNormalizedRemainingMillis(remainingMillis: Long): Long = when {
    remainingMillis <= 0L -> 0L
    remainingMillis >= MAX_PAUSED_SESSION_MILLIS -> 0L
    else -> remainingMillis
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
    val remaining   = getNormalizedRemainingMillis(prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L))
    (now < activeUntil) || (now in (activeUntil + 1)..lockedUntil) || (remaining > 0)
}

/**
 * Returns true when every monitored app with a daily budget is still under its limit today.
 * Apps with budget 0 (unlimited) are ignored.
 */
fun stayedUnderAllBudgets(
    prefs: SharedPreferences,
    monitoredApps: Set<String>,
    dateKey: String
): Boolean = monitoredApps.all { pkg ->
    val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
    if (budgetMins <= 0) return@all true
    val lastReset = prefs.getString(PrefsKeys.DAILY_RESET_DATE + pkg, "")
    val usedMs = if (lastReset == dateKey) prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L) else 0L
    usedMs < budgetMins * 60 * 1000L
}

/** Milliseconds until local midnight (start of next calendar day). */
fun millisUntilMidnight(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
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

    /**
     * Resolves the pool of reflection prompts the pause screen picks from.
     *  - [useCustomOnly] = false → defaults + custom prompts.
     *  - [useCustomOnly] = true  → custom prompts only.
     *
     * Falls back to [DEFAULT] whenever the result would otherwise be empty
     * (e.g. custom-only enabled but no custom prompts saved yet) so the pause
     * screen is never blank.
     */
    fun resolvePool(customPrompts: List<String>, useCustomOnly: Boolean): List<String> {
        val pool = mutableListOf<String>()
        if (!useCustomOnly) pool.addAll(DEFAULT)
        pool.addAll(customPrompts)
        if (pool.isEmpty()) pool.addAll(DEFAULT)
        return pool
    }
}
