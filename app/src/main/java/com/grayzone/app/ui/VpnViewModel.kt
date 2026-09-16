package com.grayzone.app.ui

import com.grayzone.app.ui.components.*

import androidx.lifecycle.ViewModel
import com.grayzone.app.data.repository.VpnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val vpnRepository: VpnRepository
) : ViewModel() {
    val isVpnEnabled: StateFlow<Boolean> = vpnRepository.isVpnEnabled

    fun setVpnEnabled(enabled: Boolean) {
        vpnRepository.setVpnEnabled(enabled)
    }
}
