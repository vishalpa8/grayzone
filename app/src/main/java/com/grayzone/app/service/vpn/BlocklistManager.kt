package com.grayzone.app.service.vpn

import android.content.Context
import com.grayzone.app.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object BlocklistManager {

    /** Why a domain is blocked. Threats are reported separately from ordinary list blocks. */
    enum class BlockReason { THREAT, LISTED }

    // Bloom filters replace the old 120 MB HashSet<String> approach. Shipped filters are
    // ~1.5 MB (ads), ~4 MB (adult) and ~6 MB (security: malware, phishing, scams), sized at
    // ~0.009% false positives. See scripts/blocklist_tools.ps1 for generation.
    @Volatile private var adFilter:       GrayzoneBloomFilter? = null
    @Volatile private var adultFilter:    GrayzoneBloomFilter? = null
    @Volatile private var securityFilter: GrayzoneBloomFilter? = null

    // Bare TLD blocks (".zip", ".mov", ".surf", ...) cannot go into a Bloom filter —
    // see AbusedTldList — so they are kept as an exact-match set alongside it.
    @Volatile private var abusedTlds: AbusedTldList = AbusedTldList.EMPTY

    // Names the shipped lists may never block (res/raw/critical_domains.txt). Guards against a
    // Bloom false positive, or a feed listing a whole platform, taking down an essential service.
    @Volatile private var criticalDomains: Set<String> = emptySet()

    // Custom Rules
    @Volatile private var customAllowlist: Set<String> = emptySet()
    @Volatile private var customDenylist: Set<String> = emptySet()
    @Volatile private var blockTelemetry: Boolean = false

    // Hardcoded allowlist for domains that are frequently flagged as false-positives 
    // by community blocklists, breaking core functionality of essential apps.
    private val falsePositiveAllowlist: Set<String> = hashSetOf(
        "rapido.bike",
        "rapido.com",
        "olacabs.com",
        "swiggy.in",
        "swiggy.com",
        "zomato.com",
        "uber.com",
        "ubereats.com",
        "juspay.in", // Common payment gateway
        "razorpay.com",
        "paytm.com",
        "paytm.in",
        "phonepe.com"
    )

    private val telemetryDomains: Set<String> = hashSetOf(
        "tracking.miui.com",
        "api.ad.xiaomi.com",
        "data.mistat.xiaomi.com",
        "samsungqbe.com",
        "samsungacr.com",
        "metrics.icloud.com",
        "app-measurement.com",
        "crashlytics.com",
        "device-metrics-us.amazon.com",
        // Not graph.facebook.com / graph.instagram.com: those are the apps' main APIs, and
        // blocking them stops Facebook and Instagram from loading at all.
        "telemetry.microsoft.com",
        "vortex.data.microsoft.com"
    )

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
    // Bounded LRU cache to avoid unbounded memory growth from per-request additions.
    // Access guarded by cacheLock for simple thread-safety.
    private val cacheLock = Any()
    private val normalizedDomainCache: MutableMap<String, String?> = object : java.util.LinkedHashMap<String, String?>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean {
            return size > 4000
        }
    }

    private fun isIpv4Literal(domain: String): Boolean {
        var dots = 0
        var digits = 0
        for (i in domain.indices) {
            val c = domain[i]
            if (c == '.') {
                if (digits == 0 || digits > 3) return false
                dots++
                digits = 0
            } else if (c in '0'..'9') {
                digits++
            } else {
                return false
            }
        }
        return dots == 3 && digits in 1..3
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

    fun reloadCustomRules(context: Context) {
        val prefs = context.getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(cacheLock) {
            customAllowlist = prefs.getStringSet(com.grayzone.app.PrefsKeys.VPN_CUSTOM_ALLOWLIST, emptySet())?.mapNotNull { normalizeDomain(it) }?.toSet() ?: emptySet()
            customDenylist = prefs.getStringSet(com.grayzone.app.PrefsKeys.VPN_CUSTOM_DENYLIST, emptySet())?.mapNotNull { normalizeDomain(it) }?.toSet() ?: emptySet()
        }
        blockTelemetry = prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_BLOCK_TELEMETRY, false)
        com.grayzone.app.GrayzoneLogger.i(com.grayzone.app.LogComponent.VPN, "Rules applied: ${customAllowlist.size} allowed, ${customDenylist.size} blocked, telemetry blocking ${if (blockTelemetry) "on" else "off"}")
    }

    fun load(context: Context) {
        reloadCustomRules(context)
        if (!loadStarted.compareAndSet(false, true)) return
        Thread {
            try {
                // Critical names first: the filters must never be live without their guard.
                criticalDomains = context.resources.openRawResource(R.raw.critical_domains)
                    .bufferedReader().use { parseDomainList(it.readText()) }
                adFilter       = loadFilter(context, R.raw.adblock_bloom)
                adultFilter    = loadFilter(context, R.raw.adult_bloom)
                securityFilter = loadFilter(context, R.raw.security_bloom)
                abusedTlds     = loadAbusedTlds(context)
                isLoaded = true
                com.grayzone.app.GrayzoneLogger.i(
                    com.grayzone.app.LogComponent.VPN,
                    "Blocklists loaded (ads, adult, security, ${abusedTlds.size} TLDs, ${criticalDomains.size} protected names)"
                )
            } catch (e: Exception) {
                // F-026: do not swallow Errors (e.g. OutOfMemoryError) — let them propagate.
                adFilter = null
                adultFilter = null
                securityFilter = null
                abusedTlds = AbusedTldList.EMPTY
                isLoaded = false
                loadStarted.set(false)
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "Blocklist load failed — filtering degraded to fallback domains only",
                    e
                )
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

            var trimmed = domain.trim()
            var hasUpper = false
            for (i in trimmed.indices) {
                if (trimmed[i] in 'A'..'Z') {
                    hasUpper = true
                    break
                }
            }
            if (hasUpper) trimmed = trimmed.lowercase(Locale.US)
            
            if (trimmed.startsWith("http://")) trimmed = trimmed.substring(7)
            else if (trimmed.startsWith("https://")) trimmed = trimmed.substring(8)
            
            val pathIdx = trimmed.indexOf('/')
            if (pathIdx != -1) trimmed = trimmed.substring(0, pathIdx)
            
            val withoutTrailingDot = trimmed.removeSuffix(".")

            if (withoutTrailingDot.isEmpty() || withoutTrailingDot.contains(" ")) {
                synchronized(cacheLock) { normalizedDomainCache[domain] = null }
                return null
            }

            // IPv4 literal (e.g. 8.8.8.8) or IPv6 (contains ':') → reject
            if (withoutTrailingDot.contains(':') || isIpv4Literal(withoutTrailingDot)) {
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

    /** True if the domain should be blocked for any reason (see [blockReason]). */
    fun isBlocked(domain: String): Boolean = blockReason(domain) != null

    /**
     * Why [domain] is blocked, or null when it is allowed.
     * Evaluation order:
     * 1. Hardcoded DoH fallback bypass (always blocked)
     * 2. Hardcoded False-Positive Allowlist (always allowed)
     * 3. Custom Allowlist (takes precedence over everything below, threats included, so a
     *    false positive always has a way out)
     * 4. Custom Denylist
     * 5. Threats: abused TLDs and the security filter
     * 6. Telemetry rules, high-confidence ad / adult rules, ad and adult filters
     *
     * Shipped lists (5 and 6, filters only) never fire on a protected critical name.
     */
    fun blockReason(domain: String): BlockReason? {
        if (isDoHBypass(domain)) return BlockReason.LISTED
        val normalized = normalizeDomain(domain) ?: return null
        if (isUserOrSafeAllowed(normalized)) return null
        synchronized(cacheLock) {
            if (matchesHighConfidence(normalized, customDenylist)) return BlockReason.LISTED
        }

        if (matchesThreatLists(normalized)) return BlockReason.THREAT

        if (blockTelemetry && matchesHighConfidence(normalized, telemetryDomains)) return BlockReason.LISTED
        if (matchesHighConfidence(normalized, highConfidenceAdDomains) ||
            matchesHighConfidence(normalized, highConfidenceAdultDomains)) return BlockReason.LISTED
        if (!isLoaded) return null
        val ad    = adFilter
        val adult = adultFilter
        return if ((ad    != null && matchesFilter(normalized, ad)) ||
                   (adult != null && matchesFilter(normalized, adult))) BlockReason.LISTED else null
    }

    /**
     * True if [domain] is a known malware / phishing / scam host. Used for CNAME targets in
     * upstream answers, where only threats are enforced: the ad lists are tuned for names
     * clients ask for, and applying them to CDN aliases would break ordinary sites.
     */
    fun isThreat(domain: String): Boolean {
        val normalized = normalizeDomain(domain) ?: return false
        if (isUserOrSafeAllowed(normalized)) return false
        return matchesThreatLists(normalized)
    }

    /** True if the user's Allow rules cover [domain] (or a parent zone of it). */
    fun isCustomAllowed(domain: String): Boolean {
        val normalized = normalizeDomain(domain) ?: return false
        synchronized(cacheLock) {
            return matchesHighConfidence(normalized, customAllowlist)
        }
    }

    // ─── private helpers ───────────────────────────────────────────────────

    private fun isUserOrSafeAllowed(normalized: String): Boolean {
        if (matchesHighConfidence(normalized, falsePositiveAllowlist)) return true
        synchronized(cacheLock) {
            return matchesHighConfidence(normalized, customAllowlist)
        }
    }

    private fun matchesThreatLists(normalized: String): Boolean {
        if (!isLoaded) return false
        // Cheaper than ten SHA-256 probes, so it runs ahead of the Bloom filter.
        // A TLD block names no site, so protection covers a critical name's whole zone here
        // (".bike" is abused; rapido.bike and its API hosts are not).
        if (abusedTlds.blocks(normalized) &&
            !DomainZoneMatcher.anyMatch(normalized) { it in criticalDomains }) return true
        val security = securityFilter
        return security != null && matchesFilter(normalized, security)
    }

    /** One domain per line; blank lines and # comments ignored. */
    internal fun parseDomainList(text: String): Set<String> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { normalizeDomain(it) }
            .toHashSet()

    private fun loadFilter(context: Context, resId: Int): GrayzoneBloomFilter {
        val filter = context.resources.openRawResource(resId).use { stream ->
            GrayzoneBloomFilter.readFrom(stream)
        }
        return filter
    }

    private fun loadAbusedTlds(context: Context): AbusedTldList {
        val list = context.resources.openRawResource(R.raw.abused_tlds).use { stream ->
            AbusedTldList.readFrom(stream)
        }
        return list
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
        DomainZoneMatcher.anyListedMatch(domain, criticalDomains) { filter.mightContain(it) }
}
