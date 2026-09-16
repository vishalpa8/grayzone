package com.grayzone.app

import android.content.Context
import com.grayzone.app.service.vpn.BlocklistManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class BlocklistManagerTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var context: Context

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(prefs)
        // Reset state
        BlocklistManager.reloadCustomRules(context)
    }

    @Test
    fun `explicit high-confidence domains are blocked before bloom filters load`() {
        assertTrue(BlocklistManager.isBlocked("doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("adservice.google.com"))
        assertTrue(BlocklistManager.isBlocked("pornhub.com"))
        assertTrue(BlocklistManager.isBlocked("xvideos.com"))
    }

    @Test
    fun `TC-1_1 conflicting rules - allow list takes precedence over deny list`() {
        prefs.edit()
            .putStringSet(PrefsKeys.VPN_CUSTOM_ALLOWLIST, setOf("example.com"))
            .putStringSet(PrefsKeys.VPN_CUSTOM_DENYLIST, setOf("example.com"))
            .commit()
            
        BlocklistManager.reloadCustomRules(context)
        
        assertFalse("Domain in both Allow and Deny list MUST be allowed", BlocklistManager.isBlocked("example.com"))
    }

    @Test
    fun `TC-1_2 subdomain escaping - deny list matches subdomains but not malicious siblings`() {
        prefs.edit()
            .putStringSet(PrefsKeys.VPN_CUSTOM_DENYLIST, setOf("example.com"))
            .commit()
            
        BlocklistManager.reloadCustomRules(context)
        
        assertTrue("Subdomains of denied base domain should be blocked", BlocklistManager.isBlocked("api.example.com"))
        assertTrue("Deep subdomains should be blocked", BlocklistManager.isBlocked("v1.api.example.com"))
        assertFalse("Malicious siblings must NOT be blocked", BlocklistManager.isBlocked("example.com.malicious.net"))
        assertFalse("Prefixes must NOT be blocked", BlocklistManager.isBlocked("myexample.com"))
    }

    @Test
    fun `TC-1_3 malformed inputs - normalize domain handles weird inputs gracefully`() {
        // This tests BlocklistManager.normalizeDomain()
        // It should strip https://, paths, and trim whitespace.
        val normal1 = BlocklistManager.normalizeDomain("  https://example.com/path  ")
        val normal2 = BlocklistManager.normalizeDomain("http://test.com")
        val normal3 = BlocklistManager.normalizeDomain("xn--bcher-kva.example") // IDN

        assert(normal1 == "example.com")
        assert(normal2 == "test.com")
        assert(normal3 == "xn--bcher-kva.example") // Assumes normalizer returns punycode unmodified for now
    }

    @Test
    fun `TC-1_5 bypass bloom filter - allow list overrides hardcoded ad domains`() {
        assertTrue("doubleclick.net is blocked by default", BlocklistManager.isBlocked("doubleclick.net"))
        
        prefs.edit()
            .putStringSet(PrefsKeys.VPN_CUSTOM_ALLOWLIST, setOf("doubleclick.net"))
            .commit()
            
        BlocklistManager.reloadCustomRules(context)
        
        assertFalse("Allow list MUST override hardcoded and bloom filter blocking", BlocklistManager.isBlocked("doubleclick.net"))
    }

    @Test
    fun `TC-4_1 telemetry blocker works dynamically`() {
        assertFalse("Telemetry should be allowed by default", BlocklistManager.isBlocked("tracking.miui.com"))
        
        prefs.edit()
            .putBoolean(PrefsKeys.VPN_BLOCK_TELEMETRY, true)
            .commit()
            
        BlocklistManager.reloadCustomRules(context)
        
        assertTrue("Telemetry must be blocked when toggled ON", BlocklistManager.isBlocked("tracking.miui.com"))
        assertTrue("Telemetry must be blocked when toggled ON", BlocklistManager.isBlocked("samsungqbe.com"))
        assertFalse("Standard domains should not be affected by telemetry blocker", BlocklistManager.isBlocked("safe-site.com"))
    }
}
