package com.grayzone.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleButUnfocusedSessionTest {

    @Test
    fun `still resumed is not treated as backgrounded`() {
        assertFalse(
            ForegroundTransitionGuard.shouldTreatAsBackgrounded(AppVisibilityHint.STILL_RESUMED)
        )
    }

    @Test
    fun `paused is treated as backgrounded`() {
        assertTrue(
            ForegroundTransitionGuard.shouldTreatAsBackgrounded(AppVisibilityHint.PAUSED_OR_LEFT)
        )
    }

    @Test
    fun `unknown falls back to backgrounded`() {
        assertTrue(
            ForegroundTransitionGuard.shouldTreatAsBackgrounded(AppVisibilityHint.UNKNOWN)
        )
    }
}
