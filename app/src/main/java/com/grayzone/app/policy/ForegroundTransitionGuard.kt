package com.grayzone.app.policy

/**
 * Decides whether a package-focus change should pause the previous app's session (F-230).
 *
 * In split-screen the unfocused pane often stays [STILL_RESUMED]; treating that as a background
 * transition pauses protection while the monitored app remains interactive.
 */
enum class AppVisibilityHint {
    /** UsageStats (or equivalent) says the package is still resumed. */
    STILL_RESUMED,
    /** Last signal was pause/stop — treat as left foreground. */
    PAUSED_OR_LEFT,
    /** No signal — keep legacy "any other package focused ⇒ backgrounded" behaviour. */
    UNKNOWN
}

object ForegroundTransitionGuard {
    fun shouldTreatAsBackgrounded(hint: AppVisibilityHint): Boolean = when (hint) {
        AppVisibilityHint.STILL_RESUMED -> false
        AppVisibilityHint.PAUSED_OR_LEFT, AppVisibilityHint.UNKNOWN -> true
    }
}
