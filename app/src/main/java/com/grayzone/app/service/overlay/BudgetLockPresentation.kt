package com.grayzone.app.service.overlay

/** Daily-budget-exhausted lock screen (no countdown; resets at midnight). */
object BudgetLockPresentation : OverlayPresentation {
    override val title = "Locked."
    // Arrow / dash reproduce the exact (mojibake) bytes the previous inline code compiled to.
    override val skipButtonLabel = "\u00E2\u2020\u0090 Leave App"
    override val showsProgress = false
    override val countdownTextSizeSp = 72f

    override fun bodyText(prompt: String) = "Daily usage limit reached."
    override fun countdownText(secondsRemaining: Int) = "\u00E2\u20AC\u201D"
    override fun subText(secondsRemaining: Int) = "Resets tomorrow"
    override fun initialSeconds(inputs: SecondsInputs) = 0

    override fun onCountdownFinished(ctx: CountdownFinishContext) {
        // No countdown; the screen stays up until the user leaves the app.
    }

    override fun onSkip(ctx: SkipContext) {
        // Leaving a lock screen records no friction skip.
    }
}
