package com.grayzone.app.ui

import com.grayzone.app.ui.components.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grayzone.app.data.repository.SessionRepository
import com.grayzone.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    val themeMode: StateFlow<String> = settingsRepository.themeMode
    val waitSeconds: StateFlow<Int> = settingsRepository.waitSeconds
    val sessionMinutes: StateFlow<Int> = settingsRepository.sessionMinutes
    val lockoutMinutes: StateFlow<Int> = settingsRepository.lockoutMinutes

    val monitoredApps = sessionRepository.getMonitoredApps()

    fun setThemeMode(mode: String) {
        settingsRepository.setThemeMode(mode)
    }

    fun setWaitSeconds(seconds: Int) {
        settingsRepository.setWaitSeconds(seconds)
    }

    fun setSessionMinutes(minutes: Int) {
        settingsRepository.setSessionMinutes(minutes)
    }

    fun setLockoutMinutes(minutes: Int) {
        settingsRepository.setLockoutMinutes(minutes)
    }
}
