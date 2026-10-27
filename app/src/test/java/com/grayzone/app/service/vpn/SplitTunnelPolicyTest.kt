package com.grayzone.app.service.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelPolicyTest {

    @Test
    fun `changed list with a running tunnel reloads`() {
        assertTrue(SplitTunnelPolicy.shouldReload(setOf("a"), setOf("a", "b"), tunnelRunning = true))
    }

    @Test
    fun `toggling an app on and off again does not reload`() {
        assertFalse(SplitTunnelPolicy.shouldReload(setOf("a"), setOf("a"), tunnelRunning = true))
    }

    @Test
    fun `no reload while the tunnel is down - it reads the list when it starts`() {
        assertFalse(SplitTunnelPolicy.shouldReload(emptySet(), setOf("bank"), tunnelRunning = false))
    }
}
