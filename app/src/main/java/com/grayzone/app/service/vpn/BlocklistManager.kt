package com.grayzone.app.service.vpn

import android.content.Context
import android.util.Log
import com.grayzone.app.R
import java.util.concurrent.atomic.AtomicBoolean

object BlocklistManager {
    private const val TAG = "BlocklistManager"

    // Bloom filters replace the old 120 MB HashSet<String> approach.
    // Each filter is ~1.5 MB RAM at 0.1% false-positive rate.
    private var adFilter:    GrayzoneBloomFilter? = null
    private var adultFilter: GrayzoneBloomFilter? = null

    /**
     * Hardcoded set of known DNS-over-HTTPS (DoH) and DNS-over-TLS (DoT)
     * resolver endpoints — confirmed alive via probe_urls.ps1 (2026-07-18).
     *
     * Blocking these forces apps like Chrome/Samsung Browser back to plain
     * DNS-53 which our VPN tunnel can intercept and filter.
     */
    private val dohBypassDomains: Set<String> = hashSetOf(
        // Google
        "dns.google",
        "8888.google",
        // Cloudflare
        "cloudflare-dns.com",
        "1dot1dot1dot1.cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        // Quad9
        "dns.quad9.net",
        "dns9.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        // AdGuard
        "dns.adguard.com",
        "dns-family.adguard.com",
        // NextDNS
        "dns.nextdns.io",
        // OpenDNS
        "doh.opendns.com",
        // Comcast
        "doh.xfinity.com",
        // Alibaba
        "dns.alidns.com",
        // Samsung / misc
        "dns.google.com"
    )

    private val loadStarted = AtomicBoolean(false)

    @Volatile var isLoaded = false
        private set

    // ─── public API ────────────────────────────────────────────────────────

    fun load(context: Context) {
        if (!loadStarted.compareAndSet(false, true)) return
        Thread {
            try {
                adFilter    = loadFilter(context, R.raw.adblock_bloom, "ads")
                adultFilter = loadFilter(context, R.raw.adult_bloom,   "adult")
                isLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bloom filters", e)
            }
        }.start()
    }

    /** True if domain is a known DoH/DoT bypass endpoint (always checked). */
    fun isDoHBypass(domain: String): Boolean =
        dohBypassDomains.contains(domain.lowercase())

    /** True if domain matches the ad/tracking bloom filter. */
    fun isAdBlocked(domain: String): Boolean {
        if (!isLoaded) return false
        val filter = adFilter ?: return false
        return matchesFilter(domain, filter)
    }

    /** True if domain matches the adult-content bloom filter. */
    fun isAdultBlocked(domain: String): Boolean {
        if (!isLoaded) return false
        val filter = adultFilter ?: return false
        return matchesFilter(domain, filter)
    }

    /**
     * Master check. Returns true if the domain should be blocked for any
     * reason: ad, adult content, or known DoH bypass endpoint.
     *
     * DoH bypass is checked first and works even before loading completes.
     */
    fun isBlocked(domain: String): Boolean {
        if (isDoHBypass(domain)) return true
        if (!isLoaded) return false
        val lower = domain.lowercase()
        val ad    = adFilter
        val adult = adultFilter
        return (ad    != null && matchesFilter(lower, ad))    ||
               (adult != null && matchesFilter(lower, adult))
    }

    // ─── private helpers ───────────────────────────────────────────────────

    private fun loadFilter(context: Context, resId: Int, label: String): GrayzoneBloomFilter {
        val filter = context.resources.openRawResource(resId).use { stream ->
            GrayzoneBloomFilter.readFrom(stream)
        }
        Log.d(TAG, "Loaded $label bloom filter")
        return filter
    }

    /**
     * Check whether [domain] (already lowercased) or any of its parent zones
     * is in [filter].
     *
     * E.g. "sub.bad-ads.com" → checks "sub.bad-ads.com", then "bad-ads.com"
     */
    private fun matchesFilter(domain: String, filter: GrayzoneBloomFilter): Boolean {
        val lower = domain.lowercase()
        if (filter.mightContain(lower)) return true
        // Walk parent domains
        var dotIndex = lower.indexOf('.')
        while (dotIndex != -1 && dotIndex < lower.length - 1) {
            val parent = lower.substring(dotIndex + 1)
            if (filter.mightContain(parent)) return true
            dotIndex = lower.indexOf('.', dotIndex + 1)
        }
        return false
    }
}
