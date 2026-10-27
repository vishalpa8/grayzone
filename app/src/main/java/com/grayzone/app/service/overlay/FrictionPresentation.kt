package com.grayzone.app.service.overlay

import com.grayzone.app.data.ConfigValidator

/** Friction / pause screen shown before a session starts. */
object FrictionPresentation : OverlayPresentation {
    override val title = "Pause."
    override val skipButtonLabel = "I'll Skip This"
    override val showsProgress = true
    override val countdownTextSizeSp = 72f

    override fun bodyText(prompt: String) = "\u201C$prompt\u201D"
    override fun countdownText(secondsRemaining: Int) = "$secondsRemaining"
    override fun subText(secondsRemaining: Int) = "Opening in $secondsRemaining seconds…"
    // F-100: never allow 0/negative wait to skip friction via prefs type drift.
    override fun initialSeconds(inputs: SecondsInputs) =
        inputs.waitSeconds.coerceIn(ConfigValidator.WAIT_SECONDS_RANGE)

    override fun onCountdownFinished(ctx: CountdownFinishContext) {
        // F-095: never grant a session if the user already left (or screen went off).
        if (!ctx.isStillForeground()) {
            com.grayzone.app.GrayzoneLogger.i(
                com.grayzone.app.LogComponent.OVERLAY,
                "Wait finished but ${ctx.packageName} is no longer in front; no session granted"
            )
            ctx.dismissOverlay()
            return
        }
        ctx.markAppUnlocked()
        ctx.dismissOverlay()
    }

    override fun onSkip(ctx: SkipContext) {
        ctx.recordFrictionSkip(ctx.packageName, ctx.waitedMs)
    }
}
