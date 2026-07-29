package com.grayzone.app.service.overlay

import com.grayzone.app.formatDuration

/** Hard-lock screen shown when a session has expired (lockout countdown). */
object LockPresentation : OverlayPresentation {
    override val title = "Locked."
    override val skipButtonLabel = "← Leave App"
    override val showsProgress = false
    override val countdownTextSizeSp = 42f

    override fun bodyText(prompt: String) = "You have reached your session limit."
    override fun countdownText(secondsRemaining: Int) = formatDuration(secondsRemaining)
    override fun subText(secondsRemaining: Int) = "Remaining lockout time"

    override fun initialSeconds(inputs: SecondsInputs): Int =
        ((inputs.lockedUntil - inputs.now) / 1000).coerceAtLeast(0).toInt()

    override fun onCountdownFinished(ctx: CountdownFinishContext) {
        // Lockout expired while the lock screen was showing. Don't grant silent, untracked
        // access: fall back to the friction wait screen if the user is still in this app.
        ctx.dismissOverlay()
        if (ctx.isStillForeground()) {
            ctx.showFriction()
        }
    }

    override fun onSkip(ctx: SkipContext) {
        // Leaving a lock screen records no friction skip.
    }
}
