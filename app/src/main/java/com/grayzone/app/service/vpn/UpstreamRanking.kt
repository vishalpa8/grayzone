package com.grayzone.app.service.vpn

/**
 * Orders upstream DNS servers by how fast they actually answer on the current network, so the
 * VPN asks the quickest one first and only hedges to the next when it is slow.
 *
 * Each server keeps a smoothed round-trip time (EWMA, 1/4 weight per sample). A failure (timeout)
 * counts as a very slow answer, so a dead or badly routed server sinks quickly and recovers as
 * soon as it answers well again. Thread-safe.
 */
class UpstreamRanking<T>(servers: List<T>, initialRttMs: Long = 150L) {

    private val rtt = LinkedHashMap<T, Long>().apply { servers.forEach { put(it, initialRttMs) } }

    /** Servers, fastest first (configured order breaks ties). */
    @Synchronized
    fun ordered(): List<T> = rtt.entries.sortedBy { it.value }.map { it.key }

    @Synchronized
    fun recordSuccess(server: T, rttMs: Long) {
        val old = rtt[server] ?: return
        rtt[server] = (old * 3 + rttMs.coerceAtLeast(1L)) / 4
    }

    @Synchronized
    fun recordFailure(server: T) {
        val old = rtt[server] ?: return
        rtt[server] = (old * 2 + FAILURE_PENALTY_MS).coerceAtMost(MAX_RTT_MS)
    }

    /**
     * How long to wait for the fastest server before also asking the next one: a few of its usual
     * round trips, so normal jitter doesn't double traffic but a lost packet costs little.
     */
    @Synchronized
    fun hedgeDelayMs(): Long = ((rtt.values.minOrNull() ?: 150L) * 3).coerceIn(MIN_HEDGE_MS, MAX_HEDGE_MS)

    @Synchronized
    internal fun rttOf(server: T): Long? = rtt[server]

    private companion object {
        const val FAILURE_PENALTY_MS = 2_000L
        const val MAX_RTT_MS = 10_000L
        const val MIN_HEDGE_MS = 60L
        const val MAX_HEDGE_MS = 400L
    }
}
