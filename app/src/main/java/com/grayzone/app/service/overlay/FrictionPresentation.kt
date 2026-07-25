package com.grayzone.app.service.overlay

/**
 * Friction / pause screen shown before a session starts.
 *
 * Note: the leading arrow and trailing ellipsis reproduce, byte-for-byte, the exact (mojibake)
 * strings the previous inline code compiled to, preserving observable behavior. See the audit
 * report for the flagged, out-of-scope source-encoding follow-up.
 */
object FrictionPresentation : OverlayPresentation {
    override val title = "Pause."
    override val skipButtonLabel = "\u00E2\u2020\u0090 I'll Skip This"
    override val showsProgress = true
    override val countdownTextSizeSp = 72f

    override fun bodyText(prompt: String) = "\u201C$prompt\u201D"
    override fun countdownText(secondsRemaining: Int) = "$secondsRemaining"
    override fun subText(secondsRemaining: Int) = "Opening in $secondsRemaining seconds\u00E2\u20AC\u00A6"
    override fun initialSeconds(inputs: SecondsInputs) = inputs.waitSeconds

    override fun onCountdownFinished(ctx: CountdownFinishContext) {
        ctx.markAppUnlocked()
        ctx.dismissOverlay()
    }

    override fun onSkip(ctx: SkipContext) {
        ctx.recordFrictionSkip(ctx.packageName, ctx.waitedMs)
    }
}
