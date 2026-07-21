package com.grayzone.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtectionStatusTest {

    @Before
    fun resetRepo() {
        ProtectionHealthRepository.updateVpnStatus(false)
        ProtectionHealthRepository.updateOverlayStatus(false)
        ProtectionHealthRepository.updateAccessibilityStatus(false)
    }

    @Test
    fun `fully protected only when all three active`() {
        assertFalse(ProtectionStatus().isFullyProtected)
        assertTrue(
            ProtectionStatus(vpnActive = true, overlayActive = true, accessibilityActive = true)
                .isFullyProtected
        )
    }

    @Test
    fun `degraded when some but not all protections are up`() {
        assertTrue(ProtectionStatus(vpnActive = true).isDegraded)
        assertTrue(ProtectionStatus(overlayActive = true, accessibilityActive = true).isDegraded)
        assertFalse(ProtectionStatus().isDegraded)
        assertFalse(
            ProtectionStatus(vpnActive = true, overlayActive = true, accessibilityActive = true)
                .isDegraded
        )
    }

    @Test
    fun `repository updates are reflected in status flow`() {
        ProtectionHealthRepository.updateVpnStatus(true)
        ProtectionHealthRepository.updateOverlayStatus(true)
        assertTrue(ProtectionHealthRepository.status.value.vpnActive)
        assertTrue(ProtectionHealthRepository.status.value.overlayActive)
        assertFalse(ProtectionHealthRepository.status.value.accessibilityActive)
        assertTrue(ProtectionHealthRepository.status.value.isDegraded)

        ProtectionHealthRepository.updateAccessibilityStatus(true)
        assertTrue(ProtectionHealthRepository.status.value.isFullyProtected)
    }
}
