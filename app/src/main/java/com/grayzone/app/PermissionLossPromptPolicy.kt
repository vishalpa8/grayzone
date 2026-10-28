package com.grayzone.app

/**
 * Pure prompt-once-per-loss policy for the core permissions (usage access, display over other
 * apps). Prevents the revoke → re-prompt loop: the user is prompted at most once per contiguous
 * period during which a permission is off, and the persisted flag resets when they are observed
 * granted again (so a future genuine loss prompts exactly once more).
 */
object PermissionLossPromptPolicy {

    /** Outcome of observing the current permission state. */
    data class Decision(
        /** Whether the UI should prompt the user this cycle. */
        val shouldPrompt: Boolean,
        /** New value to persist for the "already prompted for the current loss" flag. */
        val promptedForLoss: Boolean
    )

    /**
     * @param previouslyPromptedForLoss the persisted flag from a prior observation
     * @param isEnabled the freshly observed permission state (granted & enabled)
     */
    fun onObserved(previouslyPromptedForLoss: Boolean, isEnabled: Boolean): Decision =
        if (isEnabled) {
            // Granted (again): reset so a future genuine loss prompts exactly once.
            Decision(shouldPrompt = false, promptedForLoss = false)
        } else {
            // Disabled: prompt only if we have not already prompted for this loss.
            Decision(shouldPrompt = !previouslyPromptedForLoss, promptedForLoss = true)
        }
}
