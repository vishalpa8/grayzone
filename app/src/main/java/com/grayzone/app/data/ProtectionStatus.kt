package com.grayzone.app.data

data class ProtectionStatus(
    val vpnActive: Boolean = false,
    val overlayActive: Boolean = false,
    val accessibilityActive: Boolean = false,
    /** True when the user wants VPN on but the tunnel is currently down. */
    val vpnReconnectPending: Boolean = false
) {
    val isFullyProtected: Boolean
        get() = vpnActive && overlayActive && accessibilityActive

    val isDegraded: Boolean
        get() = !isFullyProtected && (vpnActive || overlayActive || accessibilityActive || vpnReconnectPending)
}
