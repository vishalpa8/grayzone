package com.grayzone.app.service.overlay

import com.grayzone.app.OverlayMode
import com.grayzone.app.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the OverlayMode -> strategy mapping and each strategy's presentation output.
 *
 * The glyphs are asserted via `\u` escapes so this test fails loudly if the overlay copy ever
 * regresses back to mojibake, independently of how this test file itself is encoded.
 */
class OverlayPresentationTest {

    private val arrow = "\u2190"
    private val dash = "\u2014"
    private val ellipsis = "\u2026"

    @Test
    fun `resolve maps each mode to its strategy`() {
        assertSame(FrictionPresentation, OverlayPresentation.resolve(OverlayMode.FRICTION))
        assertSame(LockPresentation, OverlayPresentation.resolve(OverlayMode.LOCK))
        assertSame(BudgetLockPresentation, OverlayPresentation.resolve(OverlayMode.BUDGET_LOCK))
        assertSame(ScheduleLockPresentation, OverlayPresentation.resolve(OverlayMode.SCHEDULE_LOCK))
    }

    @Test
    fun `resolve defaults unknown modes to friction`() {
        assertSame(FrictionPresentation, OverlayPresentation.resolve(3))
        assertSame(FrictionPresentation, OverlayPresentation.resolve(4))
        assertSame(FrictionPresentation, OverlayPresentation.resolve(9999))
    }

    @Test
    fun `friction presentation matches prior output`() {
        val p = FrictionPresentation
        assertEquals("Pause.", p.title)
        assertEquals("$arrow I'll Skip This", p.skipButtonLabel)
        assertTrue(p.showsProgress)
        assertEquals(72f, p.countdownTextSizeSp)
        assertEquals("\u201Cfocus\u201D", p.bodyText("focus"))
        assertEquals("5", p.countdownText(5))
        assertEquals("Opening in 5 seconds$ellipsis", p.subText(5))
        assertEquals(8, p.initialSeconds(SecondsInputs(now = 0L, lockedUntil = 0L, waitSeconds = 8)))
    }

    @Test
    fun `lock presentation matches prior output`() {
        val p = LockPresentation
        assertEquals("Locked.", p.title)
        assertEquals("$arrow Leave App", p.skipButtonLabel)
        assertFalse(p.showsProgress)
        assertEquals(42f, p.countdownTextSizeSp)
        assertEquals("You have reached your session limit.", p.bodyText("ignored"))
        assertEquals(formatDuration(90), p.countdownText(90))
        assertEquals("Remaining lockout time", p.subText(90))
        // (lockedUntil - now) / 1000, coerced at >= 0.
        assertEquals(60, p.initialSeconds(SecondsInputs(now = 1_000L, lockedUntil = 61_000L, waitSeconds = 8)))
        assertEquals(0, p.initialSeconds(SecondsInputs(now = 5_000L, lockedUntil = 1_000L, waitSeconds = 8)))
    }

    @Test
    fun `budget lock presentation matches prior output`() {
        val p = BudgetLockPresentation
        assertEquals("Locked.", p.title)
        assertEquals("$arrow Leave App", p.skipButtonLabel)
        assertFalse(p.showsProgress)
        assertEquals(72f, p.countdownTextSizeSp)
        assertEquals("Daily usage limit reached.", p.bodyText("ignored"))
        assertEquals(dash, p.countdownText(123))
        assertEquals("Resets tomorrow", p.subText(123))
        assertEquals(0, p.initialSeconds(SecondsInputs(now = 1_000L, lockedUntil = 9_000L, waitSeconds = 8)))
    }

    @Test
    fun `schedule lock presentation matches prior output`() {
        val p = ScheduleLockPresentation
        assertEquals("Locked.", p.title)
        assertEquals("$arrow Leave App", p.skipButtonLabel)
        assertFalse(p.showsProgress)
        assertEquals(72f, p.countdownTextSizeSp)
        assertEquals("Focus mode / schedule is active.", p.bodyText("ignored"))
        assertEquals(dash, p.countdownText(123))
        assertEquals("Try again later", p.subText(123))
        assertEquals(0, p.initialSeconds(SecondsInputs(now = 1_000L, lockedUntil = 9_000L, waitSeconds = 8)))
    }

    @Test
    fun `friction countdown finish unlocks and dismisses`() {
        var unlocked = false
        var dismissed = false
        FrictionPresentation.onCountdownFinished(
            CountdownFinishContext(
                packageName = "pkg",
                appName = "App",
                markAppUnlocked = { unlocked = true },
                dismissOverlay = { dismissed = true },
                isStillForeground = { true },
                showFriction = { throw AssertionError("friction should not re-show itself") }
            )
        )
        assertTrue(unlocked)
        assertTrue(dismissed)
    }

    @Test
    fun `lock countdown finish dismisses and re-shows friction only when still foreground`() {
        var dismissedCount = 0
        var frictionShown = 0
        LockPresentation.onCountdownFinished(
            CountdownFinishContext("pkg", "App", { fail() }, { dismissedCount++ }, { true }, { frictionShown++ })
        )
        LockPresentation.onCountdownFinished(
            CountdownFinishContext("pkg", "App", { fail() }, { dismissedCount++ }, { false }, { frictionShown++ })
        )
        assertEquals(2, dismissedCount)
        assertEquals(1, frictionShown)
    }

    @Test
    fun `lock-type screens ignore skip while friction records skip`() {
        var frictionSkips = 0
        LockPresentation.onSkip(SkipContext("pkg", 1_000L) { _, _ -> frictionSkips++ })
        BudgetLockPresentation.onSkip(SkipContext("pkg", 1_000L) { _, _ -> frictionSkips++ })
        ScheduleLockPresentation.onSkip(SkipContext("pkg", 1_000L) { _, _ -> frictionSkips++ })
        assertEquals(0, frictionSkips)

        FrictionPresentation.onSkip(SkipContext("pkg", 1_000L) { _, _ -> frictionSkips++ })
        assertEquals(1, frictionSkips)
    }

    private fun fail(): Nothing = throw AssertionError("callback should not be invoked")
}
