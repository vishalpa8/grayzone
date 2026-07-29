package com.grayzone.app.service.overlay

/** Friction / pause screen shown before a session starts. */
object FrictionPresentation : OverlayPresentation {
    override val title = "Pause."
    override val skipButtonLabel = "← I'll Skip This"
    override val showsProgress = true
    override val countdownTextSizeSp = 72f

    override fun bodyText(prompt: String) = "\u201C$prompt\u201D"
    override fun countdownText(secondsRemaining: Int) = "$secondsRemaining"
    override fun subText(secondsRemaining: Int) = "Opening in $secondsRemaining seconds…"
    override fun initialSeconds(inputs: SecondsInputs) = inputs.waitSeconds

    override fun onCountdownFinished(ctx: CountdownFinishContext) {
        ctx.markAppUnlocked()
        ctx.dismissOverlay()
    }

    override fun onSkip(ctx: SkipContext) {
        ctx.recordFrictionSkip(ctx.packageName, ctx.waitedMs)
    }
}
