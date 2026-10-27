package com.grayzone.app.service.vpn

/**
 * When to rebuild the tunnel for a changed split-tunneling list. Rebuilding briefly drops DNS,
 * so it only happens when the list really differs from what the running tunnel uses. A tunnel
 * that isn't up reads the saved list when it next starts, so nothing needs sending then.
 */
object SplitTunnelPolicy {
    fun shouldReload(applied: Set<String>, current: Set<String>, tunnelRunning: Boolean): Boolean =
        tunnelRunning && applied != current
}
