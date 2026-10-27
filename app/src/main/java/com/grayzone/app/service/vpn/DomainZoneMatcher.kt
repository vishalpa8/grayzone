package com.grayzone.app.service.vpn

/**
 * Shared parent-zone walk used by both high-confidence exact sets and Bloom filters.
 *
 * Given already-normalized [domain] (lowercase, no trailing dot), evaluates [predicate]
 * against the full domain and each parent that still contains a dot — never against a
 * bare TLD like "com". A Bloom false-positive on a TLD would otherwise block every
 * domain under that TLD.
 *
 * Example: "sub.bad-ads.com" → checks "sub.bad-ads.com", then "bad-ads.com" (not "com").
 *
 * Deliberate TLD blocks (".zip", ".mov", …) are handled separately by [AbusedTldList],
 * which is an exact set and therefore cannot produce a false positive.
 */
object DomainZoneMatcher {
    fun anyMatch(domain: String, predicate: (String) -> Boolean): Boolean {
        if (domain.isEmpty()) return false
        if (predicate(domain)) return true

        var dotIndex = domain.indexOf('.')
        while (dotIndex != -1 && dotIndex < domain.length - 1) {
            val parent = domain.substring(dotIndex + 1)
            if (parent.contains('.') && predicate(parent)) return true
            dotIndex = domain.indexOf('.', dotIndex + 1)
        }
        return false
    }

    /**
     * [anyMatch] for list lookups that must never fire on a [protected] name, at any level of the
     * walk: a filter hit on "google.com" can't block "mail.google.com", while a listed tenant such
     * as "evil.web.app" still matches on its own name.
     */
    fun anyListedMatch(domain: String, protected: Set<String>, predicate: (String) -> Boolean): Boolean =
        anyMatch(domain) { it !in protected && predicate(it) }
}
