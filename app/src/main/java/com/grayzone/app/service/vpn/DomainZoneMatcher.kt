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
}
