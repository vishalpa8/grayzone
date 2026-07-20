package com.grayzone.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ProtectionHealthRepository {
    private val _status = MutableStateFlow(ProtectionStatus())
    val status: StateFlow<ProtectionStatus> = _status.asStateFlow()

    fun updateVpnStatus(isActive: Boolean) {
        _status.update { it.copy(vpnActive = isActive) }
    }

    fun updateOverlayStatus(isActive: Boolean) {
        _status.update { it.copy(overlayActive = isActive) }
    }

    fun updateAccessibilityStatus(isActive: Boolean) {
        _status.update { it.copy(accessibilityActive = isActive) }
    }
}
