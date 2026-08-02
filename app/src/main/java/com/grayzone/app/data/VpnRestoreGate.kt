package com.grayzone.app.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ensures the system VPN consent dialog is offered at most once per process after a backup restore.
 * Without this, MainScreen composition + ON_RESUME stacked 2–3 identical prompts.
 */
object VpnRestoreGate {
    private val consentPromptClaimed = AtomicBoolean(false)

    /** @return true only for the first caller in this process. */
    fun tryClaimConsentPrompt(): Boolean = consentPromptClaimed.compareAndSet(false, true)
}
