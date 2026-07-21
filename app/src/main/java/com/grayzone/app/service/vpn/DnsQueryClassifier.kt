package com.grayzone.app.service.vpn

/**
 * Pure DNS routing decision used by [AdBlockVpnService.handleDnsQuery].
 *
 * Order is safety-critical:
 *  1. Firefox DoH canary → NXDOMAIN (Firefox falls back to system DNS)
 *  2. Known DoH/DoT bypass → NXDOMAIN (force apps back onto DNS-53 we can filter)
 *  3. Ad/adult blocklist hit → sinkhole / NXDOMAIN negative answer
 *  4. Everything else → forward upstream
 *
 * DoH must beat the generic block path: [BlocklistManager.isBlocked] also returns
 * true for DoH domains, but those must get NXDOMAIN (not a generic ad sinkhole)
 * so browsers disable private DNS rather than treating it as a dead ad host.
 */
object DnsQueryClassifier {

    enum class Action {
        NXDOMAIN_DOH,
        SINKHOLE_BLOCK,
        FORWARD
    }

    const val FIREFOX_DOH_CANARY = "use-application-dns.net"

    fun classify(
        rawDomain: String,
        isDoHBypass: (String) -> Boolean = BlocklistManager::isDoHBypass,
        isBlocked: (String) -> Boolean = BlocklistManager::isBlocked,
        normalize: (String) -> String? = BlocklistManager::normalizeDomain
    ): Action {
        if (rawDomain.equals(FIREFOX_DOH_CANARY, ignoreCase = true)) {
            return Action.NXDOMAIN_DOH
        }

        val normalized = normalize(rawDomain)
        return when {
            normalized != null && isDoHBypass(normalized) -> Action.NXDOMAIN_DOH
            normalized != null && isBlocked(normalized) -> Action.SINKHOLE_BLOCK
            else -> Action.FORWARD
        }
    }
}
