package com.grayzone.app.service.overlay

/** Schedule / focus-mode lock screen (no countdown; ends when the schedule window ends). */
object ScheduleLockPresentation : OverlayPresentation {
    override val title = "Locked."
    override val skipButtonLabel = "← Leave App"
    override val showsProgress = false
    override val countdownTextSizeSp = 72f

    override fun bodyText(prompt: String) = "Focus mode / schedule is active."
    override fun countdownText(secondsRemaining: Int) = "—"
    override fun subText(secondsRemaining: Int) = "Try again later"
    override fun initialSeconds(inputs: SecondsInputs) = 0

    override fun onCountdownFinished(ctx: CountdownFinishContext) {
        // No countdown; the screen stays up until the user leaves the app.
    }

    override fun onSkip(ctx: SkipContext) {
        // Leaving a lock screen records no friction skip.
    }
}
