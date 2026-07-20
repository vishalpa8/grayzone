package com.grayzone.app

import com.grayzone.app.service.vpn.BlocklistManager
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistManagerTest {
    @Test
    fun `explicit high-confidence domains are blocked before bloom filters load`() {
        assertTrue(BlocklistManager.isBlocked("doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("adservice.google.com"))
        assertTrue(BlocklistManager.isBlocked("pornhub.com"))
        assertTrue(BlocklistManager.isBlocked("xvideos.com"))
    }
}
