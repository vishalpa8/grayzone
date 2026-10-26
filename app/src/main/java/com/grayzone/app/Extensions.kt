package com.grayzone.app

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Global shared Gson instance to avoid redundant instantiations. */
val globalGson = Gson()

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
): Boolean {
    val nowElapsed = android.os.SystemClock.elapsedRealtime()
    return monitoredApps.any { pkg ->
        val activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
        val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L)
        val activeStartedElapsedMs = prefs.getLong(PrefsKeys.ACTIVE_STARTED_ELAPSED + pkg, 0L)
        val activeDurationMs = prefs.getLong(PrefsKeys.ACTIVE_DURATION_MS + pkg, 0L)
        val lockedStartedElapsedMs = prefs.getLong(PrefsKeys.LOCKED_STARTED_ELAPSED + pkg, 0L)
        val lockedDurationMs = prefs.getLong(PrefsKeys.LOCKED_DURATION_MS + pkg, 0L)
        val remaining = getNormalizedRemainingMillis(prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L))
        
        val isSessionActive = if (activeStartedElapsedMs > 0L) {
            com.grayzone.app.policy.MonotonicDeadline.isPending(activeUntil, activeStartedElapsedMs, activeDurationMs, now, nowElapsed)
        } else {
            activeUntil > 0L && now < activeUntil
        }
        
        val isLocked = if (lockedStartedElapsedMs > 0L) {
            com.grayzone.app.policy.MonotonicDeadline.isPending(lockedUntil, lockedStartedElapsedMs, lockedDurationMs, now, nowElapsed)
        } else {
            lockedUntil > 0L && now <= lockedUntil
        }
        
        isSessionActive || isLocked || remaining > 0
    }
}

/** True when [pkg] has a daily budget and it is already used up today. */
fun isBudgetExhausted(prefs: SharedPreferences, pkg: String): Boolean {
    val budgetMins = prefs.getInt(PrefsKeys.DAILY_BUDGET_MINUTES + pkg, 0)
    if (budgetMins <= 0) return false
    return com.grayzone.app.data.DailyBudget.remainingMs(
        budgetMins = budgetMins,
        dateKey = DateUtils.getCurrentDateKey(),
        lastResetDateKey = prefs.getString(PrefsKeys.DAILY_RESET_DATE + pkg, ""),
        usedMs = prefs.getLong(PrefsKeys.DAILY_USED_MILLIS + pkg, 0L)
    ) <= 0L
}

/**
 * True while any restriction is in force that the user must not be able to loosen from the UI:
 * a per-app session, pause or lockout ([isAnyAppLocked]), a used-up daily budget, or an active
 * schedule / Deep Work window. Settings, limits and the Monitoring switch are frozen while true.
 */
fun isRestrictionActive(
    context: Context,
    prefs: SharedPreferences,
    monitoredApps: Set<String>,
    now: Long
): Boolean =
    isAnyAppLocked(prefs, monitoredApps, now) ||
        monitoredApps.any { isBudgetExhausted(prefs, it) } ||
        (monitoredApps.isNotEmpty() && com.grayzone.app.data.ScheduleManager(context).isCurrentlyScheduled())

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

    /**
     * Parse stored custom-prompts JSON.
     * - `null` json → empty list (legitimate "no prompts yet")
     * - valid JSON → the list (null Gson result treated as empty)
     * - malformed JSON → `null` so writers can refuse to overwrite (F-148)
     */
    fun parseCustom(json: String?, gson: Gson = globalGson): List<String>? {
        if (json == null) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            null
        }
    }

    fun mayOverwrite(parsed: List<String>?): Boolean = parsed != null
}
