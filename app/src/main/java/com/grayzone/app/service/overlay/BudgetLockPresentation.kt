package com.grayzone.app.service.overlay

/** Daily-budget-exhausted lock screen (no countdown; resets at midnight). */
object BudgetLockPresentation : OverlayPresentation {
    override val title = "Locked."
    override val skipButtonLabel = "← Leave App"
    override val showsProgress = false
    override val countdownTextSizeSp = 24f // F-097: 72sp clipped lock "—" in landscape / large font

    override fun bodyText(prompt: String) = "Daily usage limit reached."
    override fun countdownText(secondsRemaining: Int) = "—"
    override fun subText(secondsRemaining: Int) = "Resets tomorrow"
    override fun initialSeconds(inputs: SecondsInputs) = 0

    override fun onCountdownFinished(ctx: CountdownFinishContext) {
        // No countdown; the screen stays up until the user leaves the app.
    }

    override fun onSkip(ctx: SkipContext) {
        // Leaving a lock screen records no friction skip.
    }
}
