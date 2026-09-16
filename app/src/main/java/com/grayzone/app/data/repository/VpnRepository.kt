package com.grayzone.app.data.repository

import android.content.SharedPreferences
import com.grayzone.app.PrefsKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnRepository @Inject constructor(
    private val prefs: SharedPreferences
) {
    private val _isVpnEnabled = MutableStateFlow(prefs.getBoolean(PrefsKeys.VPN_ENABLED, false))
    val isVpnEnabled: StateFlow<Boolean> = _isVpnEnabled.asStateFlow()

    fun setVpnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PrefsKeys.VPN_ENABLED, enabled).apply()
        _isVpnEnabled.value = enabled
    }
}
