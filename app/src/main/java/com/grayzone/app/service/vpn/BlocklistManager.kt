package com.grayzone.app.service.vpn

import android.content.Context
import android.util.Log
import com.grayzone.app.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object BlocklistManager {
    private const val TAG = "BlocklistManager"

    // Bloom filters replace the old 120 MB HashSet<String> approach.
    // Shipped filters are ~6 MB (ads) and ~4 MB (adult) on disk/RAM, targeting a
    // ~0.1% false-positive rate. See scripts/blocklist_tools.ps1 for generation.
    private var adFilter:    GrayzoneBloomFilter? = null
    private var adultFilter: GrayzoneBloomFilter? = null

    /**
     * Hardcoded set of known DNS-over-HTTPS (DoH) and DNS-over-TLS (DoT)
     * resolver endpoints. Blocking these forces apps like Chrome / Samsung Browser
     * back to plain DNS-53, which our VPN tunnel can intercept and filter.
     *
     * NOTE: Android's built-in "Private DNS" (Settings → Network → Private DNS) uses
     * DoT on port 853 and routes outside our VPN tunnel entirely. There is no reliable
     * way to block that at the DNS layer — it must be surfaced as a UI warning instead
     * (see HomeScreen). The domains here cover in-app DoH/DoT libraries only.
     *
     * Last verified: 2026-07-21
     */
    private val dohBypassDomains: Set<String> = hashSetOf(
        // ── Google ────────────────────────────────────────────────────────
        "dns.google",           // primary canonical name
        "dns.google.com",       // alternate used by some apps
        "8888.google",
        // ── Cloudflare ───────────────────────────────────────────────────
        "cloudflare-dns.com",
        "1dot1dot1dot1.cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "family.cloudflare-dns.com",
        "security.cloudflare-dns.com",
        // ── Quad9 ────────────────────────────────────────────────────────
        "dns.quad9.net",
        "dns9.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        // ── AdGuard ──────────────────────────────────────────────────────
        "dns.adguard.com",
        "dns-family.adguard.com",
        "dns-unfiltered.adguard.com",
        // ── NextDNS ──────────────────────────────────────────────────────
        "dns.nextdns.io",
        // ── OpenDNS / Cisco Umbrella ──────────────────────────────────────
        "doh.opendns.com",
        "doh.familyshield.opendns.com",
        "resolver1.opendns.com",
        "resolver2.opendns.com",
        // ── Comcast / Xfinity ────────────────────────────────────────────
        "doh.xfinity.com",
        // ── Alibaba / AliDNS ─────────────────────────────────────────────
        "dns.alidns.com",
        // ── Tencent DNSPod ────────────────────────────────────────────────
        "doh.pub",
        // ── CleanBrowsing ─────────────────────────────────────────────────
        "doh.cleanbrowsing.org",
        "security-filter-dns.cleanbrowsing.org",
        "family-filter-dns.cleanbrowsing.org",
        // ── Control D ────────────────────────────────────────────────────
        "freedns.controld.com",
        // ── Mullvad ──────────────────────────────────────────────────────
        "dns.mullvad.net",
        "adblock.dns.mullvad.net",
        // ── Firefox canary (already handled separately, but belt+braces) ─
        "use-application-dns.net"
    )

    private val loadStarted = AtomicBoolean(false)
    private val ipv4LiteralRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    // Bounded LRU cache to avoid unbounded memory growth from per-request additions.
    // Access guarded by cacheLock for simple thread-safety.
    private val cacheLock = Any()
    private val normalizedDomainCache: MutableMap<String, String?> = object : java.util.LinkedHashMap<String, String?>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean {
            return size > 4000
        }
    }

    // High-confidence fallback domains for immediate blocking before bloom filters
    // finish loading or when a specific domain is known to be a strong ad/tracker
    // or adult-content endpoint.
    private val highConfidenceAdDomains: Set<String> = hashSetOf(
        "adservice.google.com",
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "googletagmanager.com",
        "googletagservices.com",
        "google-analytics.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "amazon-adsystem.com",
        "scorecardresearch.com",
        "quantserve.com",
        "criteo.com",
        "pubmatic.com",
        "openx.net",
        "rubiconproject.com",
        "lijit.com",
        "demdex.net",
        "yieldmo.com",
        "tapad.com",
        "teads.tv",
        "media.net",
        "adform.net",
        "advertising.com"
    )

    private val highConfidenceAdultDomains: Set<String> = hashSetOf(
        "pornhub.com",
        "redtube.com",
        "xvideos.com",
        "xhamster.com",
        "youporn.com",
        "tube8.com",
        "xnxx.com",
        "spankbang.com",
        "livejasmin.com",
        "chaturbate.com",
        "cam4.com",
        "myfreecams.com",
        "onlyfans.com"
    )

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
                adFilter = null
                adultFilter = null
                isLoaded = false
                loadStarted.set(false)
                Log.e(TAG, "Error loading bloom filters", e)
            }
        }.start()
    }

    /** Normalize a domain before matching: lowercase, trim whitespace, remove a trailing dot. */
    fun normalizeDomain(domain: String): String? {
        try {
            // Fast path: return cached value when present (including explicit nulls).
            synchronized(cacheLock) {
                if (normalizedDomainCache.containsKey(domain)) return normalizedDomainCache[domain]
            }

            val trimmed = domain.trim().lowercase(Locale.US)
            val withoutTrailingDot = trimmed.removeSuffix(".")

            if (withoutTrailingDot.isEmpty() || withoutTrailingDot.contains(" ")) {
                synchronized(cacheLock) { normalizedDomainCache[domain] = null }
                return null
            }

            // IPv4 literal (e.g. 8.8.8.8) or IPv6 (contains ':') → reject
            if (ipv4LiteralRegex.matches(withoutTrailingDot) || withoutTrailingDot.contains(':')) {
                synchronized(cacheLock) { normalizedDomainCache[domain] = null }
                return null
            }

            // Require at least one dot (skip single-label hosts like "localhost")
            if (!withoutTrailingDot.contains('.')) {
                synchronized(cacheLock) { normalizedDomainCache[domain] = null }
                return null
            }

            synchronized(cacheLock) { normalizedDomainCache[domain] = withoutTrailingDot }
            return withoutTrailingDot
        } catch (e: Exception) {
            // Defensive: any unexpected parsing error should not crash the caller.
            synchronized(cacheLock) { normalizedDomainCache[domain] = null }
            return null
        }
    }

    /** True if domain is a known DoH/DoT bypass endpoint (always checked). */
    fun isDoHBypass(domain: String): Boolean {
        val normalized = normalizeDomain(domain) ?: return false
        return dohBypassDomains.contains(normalized)
    }

    /**
     * Master check. Returns true if the domain should be blocked for any
     * reason: ad, adult content, or known DoH bypass endpoint.
     *
     * DoH bypass is checked first and works even before loading completes.
     */
    fun isBlocked(domain: String): Boolean {
        if (isDoHBypass(domain)) return true
        val normalized = normalizeDomain(domain) ?: return false
        if (matchesHighConfidence(normalized, highConfidenceAdDomains) ||
            matchesHighConfidence(normalized, highConfidenceAdultDomains)) return true
        if (!isLoaded) return false
        val ad    = adFilter
        val adult = adultFilter
        return (ad    != null && matchesFilter(normalized, ad)) ||
               (adult != null && matchesFilter(normalized, adult))
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
     *
     * TLD guard: parent-domain walk stops before single-label names (bare TLDs
     * like "com", "net", "org"). A Bloom-filter hash collision on a TLD entry
     * would otherwise block every domain under that TLD. We require at least
     * one dot in the candidate string before querying the filter for a parent.
     */
    private fun matchesHighConfidence(domain: String, matchSet: Set<String>): Boolean =
        DomainZoneMatcher.anyMatch(domain) { matchSet.contains(it) }

    private fun matchesFilter(domain: String, filter: GrayzoneBloomFilter): Boolean =
        DomainZoneMatcher.anyMatch(domain) { filter.mightContain(it) }
}
