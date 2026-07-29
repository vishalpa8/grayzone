package com.grayzone.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ProtectionHealthRepository {
    private val _status = MutableStateFlow(ProtectionStatus())
    val status: StateFlow<ProtectionStatus> = _status.asStateFlow()

    fun updateVpnStatus(isActive: Boolean) {
        _status.update {
            it.copy(
                vpnActive = isActive,
                // Clear reconnect banner once the tunnel is up again.
                vpnReconnectPending = if (isActive) false else it.vpnReconnectPending
            )
        }
    }

    /**
     * Mark that VPN intent is still on but the tunnel dropped (e.g. OS kill, conflict).
     * Cleared automatically when [updateVpnStatus] reports active again.
     */
    fun markVpnReconnectPending(pending: Boolean) {
        _status.update { it.copy(vpnReconnectPending = pending) }
    }

    fun updateOverlayStatus(isActive: Boolean) {
        _status.update { it.copy(overlayActive = isActive) }
    }

    fun updateAccessibilityStatus(isActive: Boolean) {
        _status.update { it.copy(accessibilityActive = isActive) }
    }
}
