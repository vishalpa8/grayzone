package com.grayzone.app.service.vpn

/**
 * Pure DNS routing decision used by [AdBlockVpnService.handleDnsQuery].
 *
 * Order is safety-critical:
 *  1. Firefox DoH canary → NXDOMAIN (Firefox falls back to system DNS)
 *  2. Known DoH/DoT bypass → NXDOMAIN (force apps back onto DNS-53 we can filter)
 *  3. Malware / phishing / scam hit → NXDOMAIN, reported as a threat
 *  4. Ad/adult blocklist hit → NXDOMAIN
 *  5. Everything else → forward upstream
 *
 * DoH must beat the generic block path: [BlocklistManager.blockReason] also reports
 * DoH domains, but those must get NXDOMAIN_DOH (not a generic ad sinkhole)
 * so browsers disable private DNS rather than treating it as a dead ad host.
 */
object DnsQueryClassifier {

    enum class Action {
        NXDOMAIN_DOH,
        SINKHOLE_THREAT,
        SINKHOLE_BLOCK,
        FORWARD
    }

    const val FIREFOX_DOH_CANARY = "use-application-dns.net"

    fun classify(
        rawDomain: String,
        isDoHBypass: (String) -> Boolean = BlocklistManager::isDoHBypass,
        blockReason: (String) -> BlocklistManager.BlockReason? = BlocklistManager::blockReason,
        normalize: (String) -> String? = BlocklistManager::normalizeDomain
    ): Action {
        if (rawDomain.equals(FIREFOX_DOH_CANARY, ignoreCase = true)) {
            return Action.NXDOMAIN_DOH
        }

        val normalized = normalize(rawDomain) ?: return Action.FORWARD
        if (isDoHBypass(normalized)) return Action.NXDOMAIN_DOH
        return when (blockReason(normalized)) {
            BlocklistManager.BlockReason.THREAT -> Action.SINKHOLE_THREAT
            BlocklistManager.BlockReason.LISTED -> Action.SINKHOLE_BLOCK
            null -> Action.FORWARD
        }
    }
}
