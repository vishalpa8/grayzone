package com.grayzone.app.service.overlay

import com.grayzone.app.OverlayMode

/**
 * Inputs the service supplies so a strategy can compute its initial countdown seconds.
 */
data class SecondsInputs(
    val now: Long,
    val lockedUntil: Long,
    val waitSeconds: Int
)

/**
 * Side-effect callbacks the service supplies for the countdown-completion decision.
 */
class CountdownFinishContext(
    val packageName: String,
    val appName: String,
    val markAppUnlocked: () -> Unit,
    val dismissOverlay: () -> Unit,
    val isStillForeground: () -> Boolean,
    val showFriction: () -> Unit
)

/**
 * Side-effect callbacks the service supplies when the user taps the skip / leave button.
 */
class SkipContext(
    val packageName: String,
    val waitedMs: Long,
    val recordFrictionSkip: (String, Long) -> Unit
)

/**
 * Owns every per-[OverlayMode] presentation and completion decision that
 * [com.grayzone.app.service.OverlayService.showOverlay] previously re-derived inline via
 * `isLocked` / `isBudgetLock` / `isScheduleLock` branches.
 *
 * Adding a new overlay screen means adding an implementation and a [resolve] mapping entry —
 * never editing the call sites in `showOverlay`.
 */
interface OverlayPresentation {
    val title: String
    val skipButtonLabel: String
    val showsProgress: Boolean
    val countdownTextSizeSp: Float

    fun bodyText(prompt: String): String
    fun countdownText(secondsRemaining: Int): String
    fun subText(secondsRemaining: Int): String
    fun initialSeconds(inputs: SecondsInputs): Int
    fun onCountdownFinished(ctx: CountdownFinishContext)
    fun onSkip(ctx: SkipContext)

    companion object {
        /**
         * Resolves the strategy for an overlay [mode]. LOCK / BUDGET_LOCK / SCHEDULE_LOCK map to
         * their dedicated screens; every other value (FRICTION, and defensively anything else)
         * maps to the friction screen, matching the prior `else`-branch behavior. TINT /
         * REMOVE_TINT are not overlay screens and never reach this path.
         */
        fun resolve(mode: Int): OverlayPresentation = when (mode) {
            OverlayMode.LOCK -> LockPresentation
            OverlayMode.BUDGET_LOCK -> BudgetLockPresentation
            OverlayMode.SCHEDULE_LOCK -> ScheduleLockPresentation
            else -> FrictionPresentation
        }
    }
}
