package com.grayzone.app.policy

import org.junit.Assert.assertTrue
import org.junit.Test

/** Documents that leaving a monitored app emits DismissOverlay (F-005 service must honour). */
class DismissOverlayContractTest {

    private val engine = SessionPolicyEngine()
    private val now = 1_000_000L

    @Test
    fun `non monitored foreground emits dismiss overlay`() {
        val commands = engine.evaluate(
            AppEvent.AppForegrounded("com.other.app"),
            state(isMonitored = false),
            now,
            "Other"
        )
        assertTrue(commands.any { it is SessionCommand.DismissOverlay })
    }

    @Test
    fun `active session foreground emits dismiss overlay`() {
        val commands = engine.evaluate(
            AppEvent.AppForegrounded(PKG),
            state(activeUntil = now + 60_000L),
            now,
            APP
        )
        assertTrue(commands.any { it is SessionCommand.DismissOverlay })
    }
}
