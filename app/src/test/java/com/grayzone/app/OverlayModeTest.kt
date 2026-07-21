package com.grayzone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pins overlay mode integer constants — wrong values would mis-route lock screens. */
class OverlayModeTest {

    @Test
    fun `modes are distinct stable integers`() {
        val modes = listOf(
            OverlayMode.FRICTION,
            OverlayMode.LOCK,
            OverlayMode.TINT,
            OverlayMode.REMOVE_TINT,
            OverlayMode.BUDGET_LOCK,
            OverlayMode.SCHEDULE_LOCK
        )
        assertEquals(modes.size, modes.toSet().size)
        assertEquals(1, OverlayMode.FRICTION)
        assertEquals(2, OverlayMode.LOCK)
        assertEquals(5, OverlayMode.BUDGET_LOCK)
        assertEquals(6, OverlayMode.SCHEDULE_LOCK)
        assertNotEquals(OverlayMode.LOCK, OverlayMode.BUDGET_LOCK)
    }
}
